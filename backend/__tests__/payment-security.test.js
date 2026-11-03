/**
 * Payment-security suite: webhook forgery, replay/double-credit, checkout
 * tampering, cross-link access, origin fail-closed, body-size bypass, caps.
 *
 *   cd backend && npm test
 *
 * Requires the module-export patch at the bottom of server.js (require.main
 * guard) so the app can be imported without binding a port or starting sweeps.
 */
const crypto = require('crypto');
const http = require('http');
const request = require('supertest');

process.env.RAZORPAY_KEY_ID = 'rzp_test_secunit0001';
process.env.RAZORPAY_KEY_SECRET = 'unit_key_secret_0000000000';
process.env.RAZORPAY_WEBHOOK_SECRET = 'unit_webhook_secret_00000000';
process.env.FAMILY_WEB_URL = 'http://localhost:5173';
process.env.WALLET_MIN_RUPEES = '10';
process.env.WALLET_MAX_RUPEES = '2000';
process.env.WALLET_DAILY_CAP_RUPEES = '5000';
process.env.JWT_SECRET = process.env.JWT_SECRET || 'jest-only-secret-0123456789abcdef';

// uuid v14 is ESM-only; Jest's CJS runtime cannot require it. A deterministic
// stub is safe here - no route under test generates real UUIDs.
jest.mock('uuid', () => ({ v4: () => '00000000-0000-4000-8000-000000000000' }));

jest.mock('../lib/db', () => {
  const store = {};
  const clone = (v) => (v === undefined ? v : JSON.parse(JSON.stringify(v)));
  return {
    __store: store,
    readDb: async (f) => clone(store[f] || []),
    updateDb: async (f, mutator) => {
      const out = mutator(clone(store[f] || []));
      if (out && typeof out === 'object' && 'data' in out) store[f] = out.data;
      // lib/db.js returns ONLY the mutator's `result` (the envelope is an
      // internal shape). Handing tests the envelope would let routes read a
      // `{data, result}` object that production never returns.
      if (out && typeof out === 'object' && 'result' in out) return out.result;
      return out;
    },
    transact: async () => {
      throw new Error('not used in tests');
    },
    query: async () => ({ rows: [] }),
    ping: async () => true,
    pool: null,
  };
});

jest.mock('../lib/razorpay', () => {
  const actual = jest.requireActual('../lib/razorpay');
  return {
    ...actual,
    createOrder: jest.fn(async ({ amountPaise, receipt }) => ({
      id: 'order_mock_' + receipt,
      amount: amountPaise,
      currency: 'INR',
    })),
    fetchPayment: jest.fn(),
    capturePayment: jest.fn(),
    fetchPaymentsForOrder: jest.fn(async () => []),
  };
});

const KEY_SECRET = process.env.RAZORPAY_KEY_SECRET;
const WH_SECRET = process.env.RAZORPAY_WEBHOOK_SECRET;
const { app } = require('../server');
const { __store: store } = require('../lib/db');
const razorpay = require('../lib/razorpay');

const TOKEN_A = 'tok_A_0123456789abcdefghij';
const TOKEN_B = 'tok_B_0123456789abcdefghij';
const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');
const whSign = (raw) => crypto.createHmac('sha256', WH_SECRET).update(raw).digest('hex');
const paySign = (o, p) => crypto.createHmac('sha256', KEY_SECRET).update(`${o}|${p}`).digest('hex');

const ORDER = 'order_secunit00000001';
const PAYMENT = 'pay_secunit00000001';
const GROSS = 100000, FEE = 1180, TAX = 212, NET = GROSS - FEE - TAX;

let xff = 0;
const ip = () => ({ 'X-Forwarded-For': `10.9.${Math.floor(++xff / 250)}.${xff % 250}` });

function capturedEntity(over = {}) {
  return { id: PAYMENT, order_id: ORDER, status: 'captured', amount: GROSS, fee: FEE, tax: TAX, currency: 'INR', ...over };
}

function resetStore() {
  store['inmates.json'] = [{ inmateId: 'INM-1', name: 'Asha Kumar', firstName: 'Asha', lastName: 'Kumar', status: 'active' }];
  store['contacts.json'] = [{ contactId: 'CNT-1', inmateId: 'INM-1', approvalStatus: 'approved', status: 'active', active: true }];
  store['wallet-links.json'] = [
    { linkId: 'WL-A', tokenHash: sha256(TOKEN_A), contactId: 'CNT-1', inmateId: 'INM-1', status: 'active' },
    { linkId: 'WL-B', tokenHash: sha256(TOKEN_B), contactId: 'CNT-9', inmateId: 'INM-9', status: 'active' },
  ];
  store['wallet-payments.json'] = [{
    paymentId: 'PAY-A', orderId: ORDER, linkId: 'WL-A', contactId: 'CNT-1', inmateId: 'INM-1',
    amountPaise: GROSS, currency: 'INR', status: 'created', razorpayPaymentId: null,
    transactionId: null, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
  }];
  store['wallets.json'] = [];
  store['transactions.json'] = [];
  razorpay.createOrder.mockClear();
  razorpay.fetchPayment.mockReset();
  razorpay.capturePayment.mockReset();
}

beforeEach(resetStore);

const webhookBody = (over = {}) => JSON.stringify({
  event: 'payment.captured',
  payload: { payment: { entity: { id: PAYMENT, order_id: ORDER, amount: GROSS, status: 'captured', fee: FEE, tax: TAX, currency: 'INR', ...over } } },
});
const postWebhook = (raw, sig) => {
  const r = request(app).post('/api/webhooks/razorpay').set('Content-Type', 'application/json');
  if (sig !== undefined) r.set('X-Razorpay-Signature', sig);
  return r.send(raw);
};

describe('webhook forgery and signature validation', () => {
  test('W1 no signature header -> 400, nothing credited', async () => {
    const res = await postWebhook(webhookBody());
    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('SIGNATURE_INVALID');
    expect(store['transactions.json']).toHaveLength(0);
  });

  test('W2/W3 garbage or wrong-secret signature -> 400', async () => {
    const raw = webhookBody();
    expect((await postWebhook(raw, 'deadbeef')).status).toBe(400);
    const wrong = crypto.createHmac('sha256', 'another_secret').update(raw).digest('hex');
    expect((await postWebhook(raw, wrong)).status).toBe(400);
  });

  test('W4 body tampered after signing -> 400', async () => {
    const raw = webhookBody();
    const tampered = webhookBody({ amount: GROSS * 10 });
    expect((await postWebhook(tampered, whSign(raw))).status).toBe(400);
    expect(store['transactions.json']).toHaveLength(0);
  });

  test('W5 whitespace variant of signed body -> 400 (raw-byte HMAC)', async () => {
    const raw = webhookBody();
    const spaced = JSON.stringify(JSON.parse(raw), null, 2);
    expect((await postWebhook(spaced, whSign(raw))).status).toBe(400);
  });

  test('W6 valid signed webhook credits net exactly once', async () => {
    const res = await postWebhook(webhookBody(), whSign(webhookBody()));
    expect(res.status).toBe(200);
    expect(store['transactions.json']).toHaveLength(1);
    expect(store['transactions.json'][0].amount).toBe(NET);
    expect(store['wallets.json'][0].balance).toBe(NET);
    expect(store['wallet-payments.json'][0].status).toBe('credited');
  });

  test('W7 sequential replay credits once', async () => {
    const raw = webhookBody();
    for (let i = 0; i < 5; i++) expect((await postWebhook(raw, whSign(raw))).status).toBe(200);
    expect(store['transactions.json']).toHaveLength(1);
    expect(store['wallets.json'][0].balance).toBe(NET);
  });

  test('W8 10 concurrent replays credit exactly once (race)', async () => {
    const raw = webhookBody();
    const results = await Promise.all(Array.from({ length: 10 }, () => postWebhook(raw, whSign(raw))));
    results.forEach((r) => expect(r.status).toBe(200));
    expect(store['transactions.json']).toHaveLength(1);
    expect(store['wallets.json']).toHaveLength(1);
    expect(store['wallets.json'][0].balance).toBe(NET);
  });

  test('W9 forged amount with valid signature (leaked-secret scenario) -> 200 but NOT credited', async () => {
    const raw = webhookBody({ amount: GROSS * 10, fee: 0, tax: 0 });
    expect((await postWebhook(raw, whSign(raw))).status).toBe(200);
    expect(store['transactions.json']).toHaveLength(0);
    expect(store['wallet-payments.json'][0].status).toBe('created');
  });

  test('W10 unknown order -> 200, no credit, no oracle', async () => {
    const raw = JSON.stringify({ event: 'payment.captured', payload: { payment: { entity: { id: PAYMENT, order_id: 'order_unknown999', amount: GROSS, status: 'captured', fee: FEE, tax: TAX } } } });
    expect((await postWebhook(raw, whSign(raw))).status).toBe(200);
    expect(store['transactions.json']).toHaveLength(0);
  });

  test('W11 malformed but correctly signed JSON -> 400 BAD_REQUEST', async () => {
    const raw = '{"event":"payment.captured",';
    expect((await postWebhook(raw, whSign(raw))).status).toBe(400);
  });

  test('W12 wrong content-type -> 400 (no rawBody)', async () => {
    const res = await request(app).post('/api/webhooks/razorpay')
      .set('Content-Type', 'application/x-www-form-urlencoded')
      .set('X-Razorpay-Signature', whSign('a=b'))
      .send('a=b');
    expect(res.status).toBe(400);
  });

  test('W13 missing webhook secret -> 503 fails closed', async () => {
    const saved = process.env.RAZORPAY_WEBHOOK_SECRET;
    delete process.env.RAZORPAY_WEBHOOK_SECRET;
    const res = await postWebhook(webhookBody(), 'any');
    process.env.RAZORPAY_WEBHOOK_SECRET = saved;
    expect(res.status).toBe(503);
    expect(res.body.error.code).toBe('WEBHOOK_NOT_CONFIGURED');
  });

  test('W14 payment.failed marks attempt failed, no money moves', async () => {
    const raw = JSON.stringify({ event: 'payment.failed', payload: { payment: { entity: { id: PAYMENT, order_id: ORDER, status: 'failed', error_description: 'bank declined' } } } });
    expect((await postWebhook(raw, whSign(raw))).status).toBe(200);
    expect(store['wallet-payments.json'][0].status).toBe('failed');
    expect(store['transactions.json']).toHaveLength(0);
  });
});

describe('backend security headers', () => {
  test('every API response carries hardening headers', async () => {
    const res = await postWebhook(webhookBody());
    expect(res.headers['x-content-type-options']).toBe('nosniff');
    expect(res.headers['x-frame-options']).toBe('DENY');
    expect(res.headers['referrer-policy']).toBe('no-referrer');
    expect(res.headers['content-security-policy']).toContain("default-src 'none'");
  });
});

describe('signature primitives (lib/razorpay)', () => {
  const r = jest.requireActual('../lib/razorpay');
  const raw = webhookBody();

  test('verifyWebhookSignature: valid / tampered / empty / short', () => {
    expect(r.verifyWebhookSignature(raw, whSign(raw))).toBe(true);
    expect(r.verifyWebhookSignature(raw + ' ', whSign(raw))).toBe(false);
    expect(r.verifyWebhookSignature(raw, '')).toBe(false);
    expect(r.verifyWebhookSignature(raw, undefined)).toBe(false);
    expect(r.verifyWebhookSignature(raw, 'short')).toBe(false);
  });

  test('verifyPaymentSignature: valid / swapped / empty', () => {
    expect(r.verifyPaymentSignature(ORDER, PAYMENT, paySign(ORDER, PAYMENT))).toBe(true);
    expect(r.verifyPaymentSignature(PAYMENT, ORDER, paySign(ORDER, PAYMENT))).toBe(false);
    expect(r.verifyPaymentSignature(ORDER, PAYMENT, '')).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// Call-creation balance gate. The kiosk's disabled lobby button is only UX;
// the server must refuse calls the wallet cannot pay for (end-of-call clamp
// would otherwise write them off 100%).
// ---------------------------------------------------------------------------
describe('call creation balance gate', () => {
  const { signAccessToken } = require('../lib/auth');

  const KIOSK = 'KIOSK-GATE';
  const INMATE = 'INM-GATE';
  const CONTACT = 'CNT-GATE';
  const kioskAuth = () => ({
    Authorization: `Bearer ${signAccessToken({ sub: KIOSK, role: 'kiosk', kioskId: KIOSK, prisonId: 'PR-1' })}`,
  });

  beforeEach(() => {
    store['kiosks.json'] = [{ kioskId: KIOSK, prisonId: 'PR-1', status: 'active', authorizationStatus: 'authorized' }];
    store['inmates.json'] = [{ inmateId: INMATE, assignedKioskId: KIOSK, prisonId: 'PR-1', firstName: 'Gate', lastName: 'Test', status: 'active' }];
    store['contacts.json'] = [{ contactId: CONTACT, inmateId: INMATE, firstName: 'Fam', lastName: 'One', phone: '+911111111111', approvalStatus: 'approved', status: 'active', active: true }];
    store['calls.json'] = [];
    store['rooms.json'] = [];
    store['schedule.json'] = [];
    store['settings.json'] = [];
    store['pricing.json'] = [{ audio: { ratePerMinute: 1 }, video: { ratePerMinute: 2.5 } }];
    store['wallets.json'] = [];
    store['transactions.json'] = [];
  });

  const startCall = (over = {}) =>
    request(app)
      .post('/calls')
      .set(ip())
      .set(kioskAuth())
      .set('Content-Type', 'application/json')
      .send({ inmateId: INMATE, contactId: CONTACT, kioskId: KIOSK, type: 'audio', ...over });

  const fundWallet = (paise) => {
    store['wallets.json'] = [{ walletId: 'W-GATE', inmateId: INMATE, balance: paise, currency: 'INR' }];
    store['transactions.json'] = [{
      transactionId: 'TXN-GATE', walletId: 'W-GATE', inmateId: INMATE,
      type: 'recharge', amount: paise, status: 'completed', timestamp: new Date().toISOString(),
    }];
  };

  test('no wallet at all -> 402 INSUFFICIENT_BALANCE, nothing created', async () => {
    const res = await startCall();
    expect(res.status).toBe(402);
    expect(res.body.success).toBe(false);
    expect(res.body.error.code).toBe('INSUFFICIENT_BALANCE');
    expect(store['calls.json']).toHaveLength(0);
    expect(store['rooms.json']).toHaveLength(0);
  });

  test('balance below the first billed minute -> 402', async () => {
    fundWallet(99); // audio rate 1/min => first minute costs 100 paise
    const res = await startCall();
    expect(res.status).toBe(402);
    expect(store['calls.json']).toHaveLength(0);
  });

  test('balance covering the first minute -> call created (201)', async () => {
    fundWallet(100);
    const res = await startCall();
    expect(res.status).toBe(201);
    expect(res.body.success).toBe(true);
    expect(res.body.data.callId).toBeTruthy();
    expect(store['calls.json']).toHaveLength(1);
    expect(store['rooms.json']).toHaveLength(1);
  });

  test('zero-rate pricing stays free at zero balance', async () => {
    store['pricing.json'] = [{ audio: { ratePerMinute: 0 } }];
    const res = await startCall();
    expect(res.status).toBe(201);
  });
});

// ---------------------------------------------------------------------------
// Call-session wallet (routes/call-wallet.js): JWT-authenticated post-call
// recharge. The token is the OTP-verified call session, not an SMS link.
// ---------------------------------------------------------------------------
describe('call-wallet: JWT auth and session binding', () => {
  const { signAccessToken } = require('../lib/auth');
  const CW_ORDER = 'order_callwallet0001';
  const CW_PAY = 'pay_callwallet0001';

  const CONTACT = 'CNT-CW';
  const INMATE = 'INM-CW';
  const CALL_ID = 'CALL-CW-1';
  const KIOSK = 'KIOSK-CW';

  const callJwt = (over = {}, secret = process.env.JWT_SECRET) =>
    require('jsonwebtoken').sign(
      { sub: CONTACT, role: 'family', callId: CALL_ID, roomId: 'RM-CW', kioskId: KIOSK, ...over },
      secret,
      { expiresIn: '5m' },
    );

  beforeEach(() => {
    store['inmates.json'] = [{ inmateId: INMATE, firstName: 'Call', lastName: 'Wallet', status: 'active' }];
    store['contacts.json'] = [{ contactId: CONTACT, inmateId: INMATE, firstName: 'Fam', lastName: 'CW', phone: '+912222222222', approvalStatus: 'approved', status: 'active', active: true }];
    store['calls.json'] = [{
      callId: CALL_ID, contactId: CONTACT, inmateId: INMATE, kioskId: KIOSK,
      roomId: 'RM-CW', status: 'completed', type: 'audio',
    }];
    store['wallets.json'] = [];
    store['transactions.json'] = [];
    store['wallet-payments.json'] = [];
  });

  const info = (token) =>
    request(app).get('/family/call-wallet/info').set(ip()).set('Authorization', `Bearer ${token}`);
  const order = (token, body = { amount: 100 }, headers = {}) =>
    request(app).post('/family/call-wallet/order').set(ip()).set(headers).set('Authorization', `Bearer ${token}`).set('Content-Type', 'application/json').send(body);
  const verify = (token, body, headers = {}) =>
    request(app).post('/family/call-wallet/verify').set(ip()).set(headers).set('Authorization', `Bearer ${token}`).set('Content-Type', 'application/json').send(body);

  test('CW1 missing Authorization header -> 401', async () => {
    const res = await request(app).get('/family/call-wallet/info').set(ip());
    expect(res.status).toBe(401);
    expect(res.body.error.code).toBe('UNAUTHORIZED');
  });

  test('CW2 garbage token -> 401', async () => {
    const res = await info('not.a.jwt');
    expect(res.status).toBe(401);
    expect(res.body.error.code).toBe('UNAUTHORIZED');
  });

  test('CW3 token signed with wrong secret -> 401', async () => {
    const res = await info(callJwt({}, 'wrong-secret'));
    expect(res.status).toBe(401);
  });

  test('CW4 missing callId claim -> 401', async () => {
    const token = signAccessToken({ sub: CONTACT, role: 'family', callId: null, roomId: 'RM-CW', kioskId: KIOSK }, '5m');
    const res = await info(token);
    expect(res.status).toBe(401);
  });

  test('CW5 wrong role claim -> 401', async () => {
    const token = signAccessToken({ sub: CONTACT, role: 'kiosk', callId: CALL_ID, roomId: 'RM-CW', kioskId: KIOSK }, '5m');
    const res = await info(token);
    expect(res.status).toBe(401);
  });

  test('CW6 valid JWT -> info returns inmate name, balance, limits', async () => {
    // getStatement derives balance from the LEDGER (recharges minus charges),
    // not the wallet.balance cache — seed a settled recharge transaction.
    store['wallets.json'] = [{ walletId: 'W-CW', inmateId: INMATE, balance: 5000, currency: 'INR' }];
    store['transactions.json'] = [{
      transactionId: 'TXN-CW-SEED', walletId: 'W-CW', inmateId: INMATE,
      type: 'recharge', amount: 5000, status: 'completed', timestamp: new Date().toISOString(),
    }];
    const res = await info(callJwt());
    expect(res.status).toBe(200);
    expect(res.body.data.inmateName).toBeTruthy();
    expect(res.body.data.balance).toBe(5000);
    expect(res.body.data.limits).toEqual({ minRupees: 10, maxRupees: 2000 });
    expect(res.headers['cache-control']).toBe('no-store');
  });

  test('CW7 cross-contact: JWT for contact A, call belongs to contact B -> 404', async () => {
    store['calls.json'][0].contactId = 'CNT-OTHER';
    const res = await info(callJwt());
    expect(res.status).toBe(404);
  });

  test('CW8 disabled contact -> 403', async () => {
    store['contacts.json'][0].status = 'blocked';
    const res = await info(callJwt());
    expect(res.status).toBe(403);
    expect(res.body.error.code).toBe('CONTACT_DISABLED');
  });

  test('CW9 expired token -> 401', async () => {
    const token = require('jsonwebtoken').sign(
      { sub: CONTACT, role: 'family', callId: CALL_ID, roomId: 'RM-CW', kioskId: KIOSK },
      process.env.JWT_SECRET,
      { expiresIn: '-1s' },
    );
    const res = await info(token);
    expect(res.status).toBe(401);
  });

  test('CW10 order: valid amount -> 200, orderId returned, attempt has linkId null', async () => {
    const res = await order(callJwt());
    expect(res.status).toBe(200);
    expect(res.body.data.orderId).toBeTruthy();
    expect(res.body.data.amountPaise).toBe(10000);
    expect(razorpay.createOrder).toHaveBeenCalled();
    const attempt = store['wallet-payments.json'].find((p) => p.orderId === res.body.data.orderId);
    expect(attempt.linkId).toBeNull();
    expect(attempt.contactId).toBe(CONTACT);
    expect(attempt.inmateId).toBe(INMATE);
  });

  test.each([[0], [-1], [100.5], [null], [5000], [99999999]])
    ('CW11 order amount %p -> 400 INVALID_AMOUNT', async (amount) => {
      const res = await order(callJwt(), { amount });
      expect(res.status).toBe(400);
      expect(res.body.error.code).toBe('INVALID_AMOUNT');
      expect(razorpay.createOrder).not.toHaveBeenCalled();
    });

  test('CW12 order: foreign Origin -> 403', async () => {
    const res = await order(callJwt(), { amount: 100 }, { Origin: 'https://evil.example' });
    expect(res.status).toBe(403);
  });

  test('CW13 order: daily cap shared with wallet-link -> 400', async () => {
    // Commit 491000 paise via an existing attempt for the same contact.
    // 491000 + 10000 = 501000 > 500000 (5000 Rs cap).
    store['wallet-payments.json'] = [{
      paymentId: 'PAY-CW-CAP', orderId: 'order_existing_cap', linkId: null,
      contactId: CONTACT, inmateId: INMATE, amountPaise: 491000, status: 'created',
      createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    }];
    const res = await order(callJwt(), { amount: 100 }); // 10000 paise -> 501000 > 500000 cap
    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('DAILY_LIMIT');
  });

  test('CW14 verify: valid signature credits net exactly once', async () => {
    // Seed an attempt created via the call-wallet order path.
    store['wallet-payments.json'] = [{
      paymentId: 'PAY-CW-V', orderId: CW_ORDER, linkId: null,
      contactId: CONTACT, inmateId: INMATE, amountPaise: GROSS, status: 'created',
      createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    }];
    razorpay.fetchPayment.mockResolvedValue(capturedEntity({ id: CW_PAY, order_id: CW_ORDER }));
    const res = await verify(callJwt(), {
      razorpayOrderId: CW_ORDER, razorpayPaymentId: CW_PAY,
      razorpaySignature: paySign(CW_ORDER, CW_PAY),
    });
    expect(res.status).toBe(200);
    expect(res.body.data.creditedPaise).toBe(NET);
    expect(store['wallets.json'][0].balance).toBe(NET);
    expect(store['wallet-payments.json'][0].linkId).toBeNull(); // stays null, no SMS link
  });

  test('CW15 verify: replay does not double-credit', async () => {
    store['wallet-payments.json'] = [{
      paymentId: 'PAY-CW-R', orderId: CW_ORDER, linkId: null,
      contactId: CONTACT, inmateId: INMATE, amountPaise: GROSS, status: 'created',
      createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    }];
    razorpay.fetchPayment.mockResolvedValue(capturedEntity({ id: CW_PAY, order_id: CW_ORDER }));
    const body = { razorpayOrderId: CW_ORDER, razorpayPaymentId: CW_PAY, razorpaySignature: paySign(CW_ORDER, CW_PAY) };
    await verify(callJwt(), body);
    await verify(callJwt(), body);
    expect(store['transactions.json']).toHaveLength(1);
  });

  test('CW16 verify: cross-contact attempt -> 404', async () => {
    store['wallet-payments.json'] = [{
      paymentId: 'PAY-CW-X', orderId: CW_ORDER, linkId: null,
      contactId: 'CNT-OTHER', inmateId: INMATE, amountPaise: GROSS, status: 'created',
      createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    }];
    razorpay.fetchPayment.mockResolvedValue(capturedEntity({ id: CW_PAY, order_id: CW_ORDER }));
    const res = await verify(callJwt(), {
      razorpayOrderId: CW_ORDER, razorpayPaymentId: CW_PAY,
      razorpaySignature: paySign(CW_ORDER, CW_PAY),
    });
    expect(res.status).toBe(404);
    expect(store['transactions.json']).toHaveLength(0);
  });

  test('CW17 verify: invalid signature -> 400, no credit', async () => {
    store['wallet-payments.json'] = [{
      paymentId: 'PAY-CW-S', orderId: CW_ORDER, linkId: null,
      contactId: CONTACT, inmateId: INMATE, amountPaise: GROSS, status: 'created',
      createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    }];
    razorpay.fetchPayment.mockResolvedValue(capturedEntity({ id: CW_PAY, order_id: CW_ORDER }));
    const res = await verify(callJwt(), {
      razorpayOrderId: CW_ORDER, razorpayPaymentId: CW_PAY,
      razorpaySignature: 'f'.repeat(64),
    });
    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('SIGNATURE_INVALID');
    expect(store['transactions.json']).toHaveLength(0);
  });

  test('CW18 verify: missing fields -> 400', async () => {
    const res = await verify(callJwt(), { razorpayOrderId: CW_ORDER });
    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('BAD_REQUEST');
  });

  test('CW19 no secrets leak in any response body', async () => {
    store['wallet-payments.json'] = [{
      paymentId: 'PAY-CW-L', orderId: CW_ORDER, linkId: null,
      contactId: CONTACT, inmateId: INMATE, amountPaise: GROSS, status: 'created',
      createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    }];
    razorpay.fetchPayment.mockResolvedValue(capturedEntity({ id: CW_PAY, order_id: CW_ORDER }));
    const dump = JSON.stringify([
      (await info(callJwt())).body,
      (await order(callJwt())).body,
      (await verify(callJwt(), {
        razorpayOrderId: CW_ORDER, razorpayPaymentId: CW_PAY,
        razorpaySignature: paySign(CW_ORDER, CW_PAY),
      })).body,
    ]);
    expect(dump).not.toContain(KEY_SECRET);
    expect(dump).not.toContain(WH_SECRET);
    expect(dump).not.toContain(process.env.JWT_SECRET);
  });
});
