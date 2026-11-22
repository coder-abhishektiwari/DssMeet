/**
 * Fingerprint TEMPLATE STORAGE + DEVICE-SIDE IDENTIFY SUITE
 *
 * The fingerprint login flow was broken by construction: the kiosk's fresh
 * SecuGen capture was bcrypt-hashed on the server and compared with exact
 * equality against the stored digest — a match was impossible. The fix has two
 * halves, and this suite locks both in:
 *
 *   A. /auth/fingerprint-candidates — the server does NOT match. It only hands
 *      the kiosk the enrolled templates it is allowed to compare against
 *      (own prison, assigned to this kiosk or unassigned, active), and the
 *      SecuGen 1:N matcher runs on the device.
 *   B. Templates are stored RAW (base64), never bcrypt-hashed, on every write
 *      path — create, update and the dedicated biometrics endpoint.
 *
 *   C. The warden edit round-trip: the dashboard GETs an inmate (which strips
 *      fingerprintTemplate + rfidToken out of the response), edits unrelated
 *      fields, and PUTs the stripped object straight back. Absent/null secrets
 *      must keep their stored value, or every warden edit silently deletes the
 *      fingerprint and RFID enrolment.
 *
 *   cd backend && npx jest fingerprint-templates
 */
const request = require('supertest');
const jwt = require('jsonwebtoken');
const { createHash } = require('crypto');

process.env.JWT_SECRET = process.env.JWT_SECRET || 'fingerprint-suite-secret-0123456789';
process.env.SMS_PROVIDER = 'log';

jest.setTimeout(60000);

// uuid v14 ships ESM-only; the suites run under CJS jest.
jest.mock('uuid', () => ({ v4: () => '00000000-0000-4000-8000-000000000000' }));

jest.mock('../lib/db', () => {
  const store = {};
  const clone = (v) => (v === undefined ? v : JSON.parse(JSON.stringify(v)));
  return {
    __store: store,
    readDb: async (f) => clone(store[f] || []),
    // Real lib/db.js returns `result`, not the {data, result} envelope —
    // mirroring that is what makes route-level assertions meaningful. The
    // mutator may be async (inmate create hashes the PIN inside it), so it
    // must be awaited or the store never sees the write.
    updateDb: async (f, mutator) => {
      const out = await mutator(clone(store[f] || []));
      if (out && typeof out === 'object' && 'data' in out) store[f] = out.data;
      if (out && typeof out === 'object' && 'result' in out) return out.result;
      return out;
    },
    transact: async () => { throw new Error('not used'); },
    query: async () => ({ rows: [] }),
    ping: async () => true,
    pool: null,
  };
});

const { app } = require('../server');
const { __store: store } = require('../lib/db');

const SECRET = process.env.JWT_SECRET;
const PRISON = 'PR-1';
const PRISON_B = 'PR-2';
const KIOSK = 'KSK-1';
const KIOSK_B = 'KSK-2';

/** 400 raw bytes -> 536-char base64, exactly what a SecuGen SG400 export is. */
const template = (seed) => {
  const chunks = [];
  let size = 0;
  let counter = 0;
  while (size < 400) {
    const chunk = createHash('sha256').update(`${seed}:${counter}`).digest();
    chunks.push(chunk);
    size += chunk.length;
    counter += 1;
  }
  return Buffer.concat(chunks).subarray(0, 400).toString('base64');
};
const TEMPLATE = template(7);
const TEMPLATE_NEW = template(9);
const TEMPLATE_2 = template(11);
const TEMPLATE_3 = template(13);
const REAL_WORLD_SG400_TEMPLATE = '3NPvPuAAzrdwgbMgdFQIFYQsdv4eTIIG8i4Zu9JJWBLqZwA7FjVkyFRz4zbv8EkxdVOfZ6m7huKoWQIfx6ry/YpagHu7tK6NQUz3k8Mhn653KAV55hw2IBonUpxcJYyvI3dnkSCdfNnUwR6jn+MB5rcGgfbAnmW3dadg8dAWXk+IPjAjb8ML9+LLjqlbC7ZoJdD/3t4b8vxnAMSQf123+sPs/c8CoyCun05NDPcKx6S74ZtowWcHXZL0PkH1qGCKmix9/RI+n4Uml9Cq0G5hKX9GO2uQQAIbTvAiwnWBEbnmspCBWzJjxAKkG1qzW2LwGy3J668NX2AqZP9H8Gz5fBstyeuvDV9gKmT/R/Bs+XwbLcnrrw1fYCpk/0fwbPl8Gy3J668NX2AqZP9H8Gz5fBstyeuvDV9gKmT/R/Bs+XwbLcnrrw1fYCpk/0fwbPl8Gy3J668NX2AqZP9H8Gz5fBstyeuvDV9gKmT/R/Bs+XwbLcnrrw1fYCpk/0fwbPl8Gy3J668NX2AqZP9H8Gz5fA==';
const SG400_PADDED_TEMPLATE = 'vF8DiQE1fRZWKW9uCczcAYjPZ0oY6TIB4SyL46rzT8A2INWYPykDwqWMAT+d9pKaAeG1Ure1WBFL4kfaWMDYyb9UbLVCkCr/4Oggo7f5wacX8Ywy9vxJGvLmaYYv42Qfs1D81cnD1PCs/HS4ZEo1WtKkekVS6xC1D1lqPp0YX6S8YFiwVub0UzbSqZWQFHhA8cAL8LmQEw2xaScQL73BtRP4yMxcORzc4YQdZ5UhxbDb3HSGtbewxNoRzJaZQrrtAXr1SSzOxRwNSQDAUtwm97Uljg8m7Uee/2bjCFEbtJescpaCwu1DyaB0RhkPDqTzqW8uXXsC46V77+PJj5EnTRstyeuvDV9gKmT/R/Bs+XwbLcnrrw1fYCpk/0fwbPl8Gy3J668NX2AqZP9H8Gz5fBstyeuvDV9gKmT/R/Bs+XwbLcnrrw1fYCpk/0fwbPl8Gy3J668NX2AqZP9H8Gz5fBstyeuvDV9gKmT/R/Bs+XwbLcnrrw1fYCpk/0fwbPl8Gy3J668NX2AqZP9H8Gz5fA==';
const BCRYPT_FP = '$2b$10$stalefingerprintdigest0000000000000000000000000000000';
const RFID_BCRYPT = '$2b$10$stalerfidtokenhash00000000000000000000000000000000';

const superToken = (over = {}) =>
  jwt.sign({ sub: 'SUDO-1', role: 'super-admin', ...over }, SECRET, { expiresIn: '10m' });

/** The credential /kiosks/verify issues — the only one this route accepts. */
const deviceToken = (kioskId, over = {}) =>
  jwt.sign({ sub: kioskId, role: 'kiosk', kioskId, prisonId: PRISON, device: true, ...over }, SECRET, { expiresIn: '10m' });

let xff = 0;
const ip = () => ({ 'X-Forwarded-For': `10.55.${Math.floor(++xff / 250)}.${xff % 250}` });

const data = (res) => res.body.data;

/** @param token null = send no Authorization header at all. */
const candidates = (kioskId, token = deviceToken(kioskId)) => {
  const req = request(app).post('/auth/fingerprint-candidates').set(ip()).send({ kioskId });
  return token ? req.set('Authorization', `Bearer ${token}`) : req;
};

const inmate = (over = {}) => ({
  inmateId: 'INM-OK',
  name: 'Asha Kumar',
  status: 'active',
  prisonId: PRISON,
  assignedKioskId: KIOSK,
  prisonerNumber: 'P-1001',
  facility: 'A-Wing',
  walletId: 'WAL-OK',
  pin: '$2b$10$legacyinmatepinhashfakefake',
  biometricData: {
    fingerprintRegistered: true,
    fingerprintTemplate: TEMPLATE,
    rfidRegistered: true,
    rfidToken: RFID_BCRYPT,
    rfidCardNumber: '4111111111',
    lastBiometricUpdate: '2026-01-01T00:00:00.000Z',
  },
  ...over,
});

function resetStore() {
  store['prisons.json'] = [
    { prisonId: PRISON, name: 'Central Jail', status: 'active' },
    { prisonId: PRISON_B, name: 'Other Jail', status: 'active' },
  ];
  store['kiosks.json'] = [
    { kioskId: KIOSK, prisonId: PRISON, status: 'active', authorizationStatus: 'authorized', deviceSerialNumber: 'SN-1' },
    { kioskId: KIOSK_B, prisonId: PRISON_B, status: 'active', authorizationStatus: 'authorized', deviceSerialNumber: 'SN-2' },
  ];
  store['wallets.json'] = [];
  store['inmates.json'] = [
    // In scope: same prison, assigned to THIS kiosk, active, enrolled.
    inmate(),
    // In scope: same prison, UNASSIGNED, active, enrolled — the kiosk must
    // still see it, otherwise unassigned inmates can never log in anywhere.
    inmate({ inmateId: 'INM-UNASSIGNED', name: 'Unassigned Pati', assignedKioskId: undefined, prisonerNumber: 'P-1002' }),
    // Out: different prison.
    inmate({ inmateId: 'INM-OTHER-PRISON', name: 'Other Jail Pati', prisonId: PRISON_B, assignedKioskId: KIOSK_B, prisonerNumber: 'P-1003' }),
    // Out: same prison but assigned to a different kiosk.
    inmate({ inmateId: 'INM-OTHER-KIOSK', name: 'Other Kiosk Pati', assignedKioskId: KIOSK_B, prisonerNumber: 'P-1004' }),
    // Out: not active.
    inmate({ inmateId: 'INM-INACTIVE', name: 'Released Pati', status: 'released', prisonerNumber: 'P-1005' }),
    // Out: fingerprint never enrolled.
    inmate({
      inmateId: 'INM-NO-FP',
      name: 'No Print Pati',
      prisonerNumber: 'P-1006',
      biometricData: { fingerprintRegistered: false, rfidRegistered: false },
    }),
    // Out: legacy bcrypt digest — can never match a live capture, so handing
    // it to the kiosk would just guarantee a failed login.
    inmate({
      inmateId: 'INM-STALE',
      name: 'Stale Print Pati',
      prisonerNumber: 'P-1007',
      biometricData: { fingerprintRegistered: true, fingerprintTemplate: BCRYPT_FP, rfidRegistered: false },
    }),
  ];
}

beforeEach(resetStore);

describe('POST /auth/fingerprint-candidates', () => {
  test('requires kioskId', async () => {
    const res = await request(app).post('/auth/fingerprint-candidates').set(ip()).send({});
    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('INVALID_REQUEST');
  });

  test('unknown kiosk is 404, not an empty roster', async () => {
    const res = await candidates('KSK-NOPE');
    expect(res.status).toBe(404);
    expect(res.body.error.code).toBe('NOT_FOUND');
  });

  test('returns only in-scope inmates for this kiosk', async () => {
    const res = await candidates(KIOSK);
    expect(res.status).toBe(200);
    const ids = data(res).map((c) => c.inmateId).sort();
    expect(ids).toEqual(['INM-OK', 'INM-UNASSIGNED']);
  });

  test('other jail sees only its own roster', async () => {
    const res = await candidates(KIOSK_B);
    expect(res.status).toBe(200);
    expect(data(res).map((c) => c.inmateId)).toEqual(['INM-OTHER-PRISON']);
  });

  test('every candidate carries the RAW template the device matcher needs', async () => {
    const res = await candidates(KIOSK);
    const ok = data(res).find((c) => c.inmateId === 'INM-OK');
    expect(ok.fingerprintTemplate).toBe(TEMPLATE);
    expect(ok.fingerprintTemplate).not.toMatch(/^\$2[aby]\$/);
  });

  test('candidates expose only the whitelisted fields — no PIN, no RFID secret', async () => {
    const res = await candidates(KIOSK);
    for (const c of data(res)) {
      expect(Object.keys(c).sort()).toEqual([
        'facility', 'fingerprintTemplate', 'inmateId', 'name',
        'photoUrl', 'prisonId', 'status',
      ]);
      expect(c).not.toHaveProperty('pin');
      expect(c).not.toHaveProperty('biometricData');
      expect(c).not.toHaveProperty('rfidToken');
      expect(c).not.toHaveProperty('walletId');
    }
  });

  test('stale bcrypt templates are skipped so the inmate gets re-enrolled', async () => {
    const res = await candidates(KIOSK);
    const ids = data(res).map((c) => c.inmateId);
    expect(ids).not.toContain('INM-STALE');
    expect(ids).not.toContain('INM-NO-FP');
  });

  test('malformed templates are skipped before the kiosk matcher sees them', async () => {
    store['inmates.json'].push(inmate({
      inmateId: 'INM-MALFORMED',
      biometricData: {
        ...inmate().biometricData,
        fingerprintTemplate: Buffer.alloc(20, 3).toString('base64'),
      },
    }));
    const res = await candidates(KIOSK);
    expect(data(res).map((c) => c.inmateId)).not.toContain('INM-MALFORMED');
  });

  test('blank 400-byte templates are skipped before native matching', async () => {
    store['inmates.json'].push(inmate({
      inmateId: 'INM-BLANK',
      biometricData: {
        ...inmate().biometricData,
        fingerprintTemplate: Buffer.alloc(400).toString('base64'),
      },
    }));
    const res = await candidates(KIOSK);
    expect(data(res).map((c) => c.inmateId)).not.toContain('INM-BLANK');
  });

  test('SG400 templates with repeated base64 padding blocks are preserved', async () => {
    store['inmates.json'].push(inmate({
      inmateId: 'INM-REPEATED-BASE64',
      biometricData: {
        ...inmate().biometricData,
        fingerprintTemplate: REAL_WORLD_SG400_TEMPLATE,
      },
    }));
    const res = await candidates(KIOSK);
    expect(data(res).map((c) => c.inmateId)).toContain('INM-REPEATED-BASE64');
  });

  test('SG400 templates with localized short-period padding are preserved', async () => {
    store['inmates.json'].push(inmate({
      inmateId: 'INM-LOCAL-PERIOD',
      biometricData: {
        ...inmate().biometricData,
        fingerprintTemplate: SG400_PADDED_TEMPLATE,
      },
    }));
    const res = await candidates(KIOSK);
    expect(data(res).map((c) => c.inmateId)).toContain('INM-LOCAL-PERIOD');
  });
});

describe('POST /auth/fingerprint-candidates requires the device credential', () => {
  test('no Authorization header at all -> 401', async () => {
    const res = await candidates(KIOSK, null);
    expect(res.status).toBe(401);
    expect(res.body.error.code).toBe('UNAUTHORIZED');
    expect(data(res)).toBeUndefined();
  });

  test('garbage / expired token -> 401', async () => {
    const expired = jwt.sign(
      { sub: KIOSK, role: 'kiosk', kioskId: KIOSK, device: true },
      SECRET, { expiresIn: '-10s' }
    );
    expect((await candidates(KIOSK, expired)).status).toBe(401);
    expect((await candidates(KIOSK, 'not-a-jwt')).status).toBe(401);
  });

  test('a logged-in user session token is NOT accepted', async () => {
    const res = await candidates(KIOSK, superToken());
    expect(res.status).toBe(401);
    expect(data(res)).toBeUndefined();
  });

  test('another jail\'s device token cannot read this jail\'s templates', async () => {
    const res = await candidates(KIOSK, deviceToken(KIOSK_B));
    expect(res.status).toBe(403);
    expect(res.body.error.code).toBe('FORBIDDEN');
    expect(data(res)).toBeUndefined();
  });

  test('the right device token still gets the roster', async () => {
    const res = await candidates(KIOSK, deviceToken(KIOSK));
    expect(res.status).toBe(200);
    expect(data(res).length).toBeGreaterThan(0);
  });

  test('missing kioskId is still a 400, not an auth error', async () => {
    const res = await request(app)
      .post('/auth/fingerprint-candidates').set(ip())
      .set('Authorization', `Bearer ${deviceToken(KIOSK)}`)
      .send({});
    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('INVALID_REQUEST');
  });
});

describe('POST /auth/fingerprint-login', () => {
  const login = (inmateId = 'INM-OK', kioskId = KIOSK, token = deviceToken(kioskId)) => {
    const req = request(app)
      .post('/auth/fingerprint-login').set(ip())
      .send({ inmateId, kioskId });
    return token ? req.set('Authorization', `Bearer ${token}`) : req;
  };

  test('device credential is required', async () => {
    expect((await login('INM-OK', KIOSK, null)).status).toBe(401);
    expect((await login('INM-OK', KIOSK, superToken())).status).toBe(401);
  });

  test('a kiosk cannot mint a session for another kiosk', async () => {
    expect((await login('INM-OK', KIOSK, deviceToken(KIOSK_B))).status).toBe(403);
  });

  test('a matched enrolled inmate gets the same inmate session as PIN login', async () => {
    const res = await login();
    expect(res.status).toBe(200);
    expect(data(res).inmateId).toBe('INM-OK');
    expect(data(res).kioskId).toBe(KIOSK);
    expect(data(res).accessToken).toEqual(expect.any(String));
    expect(data(res).refreshToken).toEqual(expect.any(String));
    const claims = jwt.verify(data(res).accessToken, SECRET);
    expect(claims).toMatchObject({ role: 'inmate', inmateId: 'INM-OK', kioskId: KIOSK });
  });

  test('a stale template never becomes a session', async () => {
    const res = await login('INM-STALE');
    expect(res.status).toBe(404);
    expect(data(res)).toBeUndefined();
  });
});

describe('fingerprint templates are stored RAW, never bcrypt', () => {
  const bioUrl = (id) => `/admin/prisoners/${id}/biometrics`;
  const token = () => ({ Authorization: `Bearer ${superToken()}` });

  test('POST /admin/prisoners/:id/biometrics stores the exact capture', async () => {
    const res = await request(app)
      .post(bioUrl('INM-OK')).set(ip()).set(token())
      .send({ type: 'fingerprint', capture: TEMPLATE_NEW });
    expect(res.status).toBe(200);

    const stored = store['inmates.json'].find((i) => i.inmateId === 'INM-OK');
    expect(stored.biometricData.fingerprintRegistered).toBe(true);
    expect(stored.biometricData.fingerprintTemplates.map((e) => e.template)).toEqual([TEMPLATE, TEMPLATE_NEW]);
    expect(stored.biometricData.fingerprintTemplate).not.toMatch(/^\$2[aby]\$/);
  });

  test('re-enrolling appends the next template', async () => {
    await request(app)
      .post(bioUrl('INM-OK')).set(ip()).set(token())
      .send({ type: 'fingerprint', capture: TEMPLATE_NEW });
    const stored = store['inmates.json'].find((i) => i.inmateId === 'INM-OK');
    expect(stored.biometricData.fingerprintTemplates.map((e) => e.template)).toEqual([TEMPLATE, TEMPLATE_NEW]);
    expect(stored.biometricData.fingerprintTemplate).toBe(TEMPLATE);
  });

  test('a legacy bcrypt digest is rejected — it could never match a capture', async () => {
    const res = await request(app)
      .post(bioUrl('INM-OK')).set(ip()).set(token())
      .send({ type: 'fingerprint', capture: BCRYPT_FP });
    expect(res.status).toBe(422);
    expect(res.body.error.message).toMatch(/re-captured/);
    const stored = store['inmates.json'].find((i) => i.inmateId === 'INM-OK');
    expect(stored.biometricData.fingerprintTemplate).toBe(TEMPLATE);
  });

  test('non-base64 and out-of-range captures are rejected', async () => {
    const short = await request(app)
      .post(bioUrl('INM-OK')).set(ip()).set(token())
      .send({ type: 'fingerprint', capture: 'nope!' });
    expect(short.status).toBe(422);

    const long = await request(app)
      .post(bioUrl('INM-OK')).set(ip()).set(token())
      .send({ type: 'fingerprint', capture: '!'.repeat(5000) });
    expect(long.status).toBe(422);

    const stored = store['inmates.json'].find((i) => i.inmateId === 'INM-OK');
    expect(stored.biometricData.fingerprintTemplate).toBe(TEMPLATE);
  });

  test('valid base64 of the wrong length is rejected', async () => {
    const res = await request(app)
      .post(bioUrl('INM-OK')).set(ip()).set(token())
      .send({ type: 'fingerprint', capture: Buffer.alloc(20, 3).toString('base64') });
    expect(res.status).toBe(422);
    expect(res.body.error.message).toMatch(/SG400/);
    const stored = store['inmates.json'].find((i) => i.inmateId === 'INM-OK');
    expect(stored.biometricData.fingerprintTemplate).toBe(TEMPLATE);
  });

  test('RFID is still hashed at rest — only the fingerprint changed', async () => {
    await request(app)
      .post(bioUrl('INM-OK')).set(ip()).set(token())
      .send({ type: 'rfid', rfidToken: '9876543210' });
    const stored = store['inmates.json'].find((i) => i.inmateId === 'INM-OK');
    expect(stored.biometricData.rfidToken).toMatch(/^\$2[aby]\$/);
    expect(stored.biometricData.rfidCardNumber).toBe('9876543210');
  });
});

describe('PUT round-trip never loses an enrolled biometric', () => {
  const token = () => ({ Authorization: `Bearer ${superToken()}` });

  // Exactly what the warden dashboard sends back after GET /inmates/:id —
  // normalizeInmate already stripped fingerprintTemplate and rfidToken out of
  // the payload the browser is holding.
  const wardenEdit = (over = {}) => ({
    name: 'Asha Kumar (Edited)',
    status: 'active',
    facility: 'B-Wing',
    biometricData: {
      fingerprintRegistered: true,
      rfidRegistered: true,
      rfidCardNumber: '4111111111',
      lastBiometricUpdate: '2026-01-01T00:00:00.000Z',
    },
    ...over,
  });

  test('stripped biometricData keeps the stored fingerprint + RFID', async () => {
    const res = await request(app)
      .put('/inmates/admin/prisoners/INM-OK').set(ip()).set(token())
      .send(wardenEdit());
    expect(res.status).toBe(200);

    const stored = store['inmates.json'].find((i) => i.inmateId === 'INM-OK');
    expect(stored.name).toBe('Asha Kumar (Edited)');
    expect(stored.facility).toBe('B-Wing');
    expect(stored.biometricData.fingerprintTemplate).toBe(TEMPLATE);
    expect(stored.biometricData.rfidToken).toBe(RFID_BCRYPT);
  });

  test('an explicit null fingerprintTemplate is treated as "not sent"', async () => {
    const res = await request(app)
      .put('/inmates/admin/prisoners/INM-OK').set(ip()).set(token())
      .send(wardenEdit({ fingerprintTemplate: null }));
    expect(res.status).toBe(200);

    const stored = store['inmates.json'].find((i) => i.inmateId === 'INM-OK');
    expect(stored.biometricData.fingerprintTemplate).toBe(TEMPLATE);
    // The stray top-level key must never persist either.
    expect(stored).not.toHaveProperty('fingerprintTemplate');
  });

  test('a top-level capture from a kiosk edit folds into biometricData', async () => {
    // Kiosk edits send the template at the TOP level, not inside biometricData.
    store['inmates.json'][0].biometricData = { fingerprintRegistered: false, rfidRegistered: false };

    const res = await request(app)
      .put('/inmates/admin/prisoners/INM-OK').set(ip()).set(token())
      .send({ name: 'Kiosk Edit', fingerprintTemplate: TEMPLATE_NEW });
    expect(res.status).toBe(200);

    const stored = store['inmates.json'].find((i) => i.inmateId === 'INM-OK');
    expect(stored.biometricData.fingerprintRegistered).toBe(true);
    expect(stored.biometricData.fingerprintTemplate).toBe(TEMPLATE_NEW);
    expect(stored).not.toHaveProperty('fingerprintTemplate');
  });

  test('create with a top-level capture enrols raw and stores no stray field', async () => {
    const res = await request(app)
      .post('/inmates').set(ip()).set(token())
      .send({
        inmateId: 'INM-NEW',
        name: 'New Pati',
        prisonId: PRISON,
        assignedKioskId: KIOSK,
        status: 'active',
        fingerprintTemplate: TEMPLATE_NEW,
      });
    expect(res.status).toBe(201);

    const created = store['inmates.json'].find((i) => i.inmateId === 'INM-NEW');
    expect(created.biometricData.fingerprintRegistered).toBe(true);
    expect(created.biometricData.fingerprintTemplate).toBe(TEMPLATE_NEW);
    expect(created).not.toHaveProperty('fingerprintTemplate');
  });

  test('no client response ever echoes a fingerprint template back', async () => {
    const res = await request(app).get('/inmates/INM-OK').set(ip()).set(token());
    expect(res.status).toBe(200);
    expect(data(res).biometricData).not.toHaveProperty('fingerprintTemplate');
    expect(data(res).biometricData).not.toHaveProperty('rfidToken');
    expect(res.text).not.toContain(TEMPLATE);
  });
});

describe('multiple fingerprints and kiosk assignments', () => {
  const token = () => ({ Authorization: `Bearer ${superToken()}` });
  const bioUrl = (id) => `/admin/prisoners/${id}/biometrics`;

  test('candidates emit one record per enrolled fingerprint with the unchanged contract', async () => {
    store['inmates.json'][0].biometricData = {
      fingerprintRegistered: true,
      fingerprintTemplate: TEMPLATE_2,
      fingerprintTemplates: [
        { biometricId: 'BIO-INM-OK-FGP-1', template: TEMPLATE_2 },
        { biometricId: 'BIO-INM-OK-FGP-2', template: TEMPLATE_3 }
      ],
      rfidRegistered: false
    };

    const res = await candidates(KIOSK);
    expect(res.status).toBe(200);
    const mine = data(res).filter((c) => c.inmateId === 'INM-OK');
    expect(mine.map((c) => c.fingerprintTemplate)).toEqual([TEMPLATE_2, TEMPLATE_3]);
    expect(Object.keys(mine[0]).sort()).toEqual([
      'fingerprintTemplate', 'facility', 'inmateId', 'name', 'photoUrl', 'prisonId', 'status'
    ].sort());
  });

  test('biometrics endpoint appends up to three fingerprints and rejects the fourth', async () => {
    const first = await request(app).post(bioUrl('INM-OK')).set(ip()).set(token())
      .send({ type: 'fingerprint', capture: TEMPLATE_2 });
    expect(first.status).toBe(200);

    const second = await request(app).post(bioUrl('INM-OK')).set(ip()).set(token())
      .send({ type: 'fingerprint', capture: TEMPLATE_3 });
    expect(second.status).toBe(200);

    const third = await request(app).post(bioUrl('INM-OK')).set(ip()).set(token())
      .send({ type: 'fingerprint', capture: template(17) });
    expect(third.status).toBe(409);
    expect(third.body.error.code).toBe('FINGERPRINT_LIMIT');

    const stored = store['inmates.json'].find((i) => i.inmateId === 'INM-OK');
    expect(stored.biometricData.fingerprintTemplates.map((e) => e.template)).toEqual([TEMPLATE, TEMPLATE_2, TEMPLATE_3]);
    expect(stored.biometricData.fingerprintTemplate).toBe(TEMPLATE);
  });

  test('deleting one fingerprint keeps the others', async () => {
    await request(app).post(bioUrl('INM-OK')).set(ip()).set(token())
      .send({ type: 'fingerprint', capture: TEMPLATE_2 });
    await request(app).post(bioUrl('INM-OK')).set(ip()).set(token())
      .send({ type: 'fingerprint', capture: TEMPLATE_3 });

    const res = await request(app)
      .delete(`/admin/biometrics/BIO-INM-OK-FGP-2?prisonerId=INM-OK`).set(ip()).set(token());
    expect(res.status).toBe(200);

    const stored = store['inmates.json'].find((i) => i.inmateId === 'INM-OK');
    expect(stored.biometricData.fingerprintTemplates.map((e) => e.template)).toEqual([TEMPLATE, TEMPLATE_3]);
    expect(stored.biometricData.fingerprintTemplate).toBe(TEMPLATE);
    expect(stored.biometricData.fingerprintRegistered).toBe(true);

    const listed = await request(app).get(bioUrl('INM-OK')).set(ip()).set(token());
    expect(listed.status).toBe(200);
    expect(data(listed).filter((b) => b.type === 'fingerprint')).toHaveLength(2);
  });

  test('create accepts multiple top-level templates and multi-kiosk assignment', async () => {
    const res = await request(app).post('/inmates').set(ip()).set(token()).send({
      inmateId: 'INM-MULTI',
      name: 'Multi Pati',
      prisonId: PRISON,
      assignedKioskIds: [KIOSK, KIOSK],
      status: 'active',
      fingerprintTemplates: [TEMPLATE_2, TEMPLATE_3]
    });
    expect(res.status).toBe(201);

    const stored = store['inmates.json'].find((i) => i.inmateId === 'INM-MULTI');
    expect(stored.assignedKioskIds).toEqual([KIOSK]);
    expect(stored.assignedKioskId).toBe(KIOSK);
    expect(stored.allKiosks).toBe(false);
    expect(stored.biometricData.fingerprintTemplates.map((e) => e.template)).toEqual([TEMPLATE_2, TEMPLATE_3]);
    expect(stored.biometricData.fingerprintTemplate).toBe(TEMPLATE_2);
    expect(stored).not.toHaveProperty('fingerprintTemplates');
    expect(data(res).fingerprintCount).toBe(2);
    expect(res.text).not.toContain(TEMPLATE_2);
  });

  test('allKiosks clears the list and makes the inmate visible from another kiosk', async () => {
    store['kiosks.json'].find((k) => k.kioskId === KIOSK_B).prisonId = PRISON;
    const res = await request(app).post('/inmates').set(ip()).set(token()).send({
      inmateId: 'INM-ALL',
      name: 'All Kiosk Pati',
      prisonId: PRISON,
      allKiosks: true,
      assignedKioskIds: [KIOSK],
      status: 'active',
      fingerprintTemplates: [TEMPLATE_2]
    });
    expect(res.status).toBe(201);

    const stored = store['inmates.json'].find((i) => i.inmateId === 'INM-ALL');
    expect(stored.allKiosks).toBe(true);
    expect(stored.assignedKioskIds).toEqual([]);
    expect(stored.assignedKioskId).toBeNull();

    const listed = await candidates(KIOSK_B);
    expect(listed.status).toBe(200);
    expect(data(listed).some((c) => c.inmateId === 'INM-ALL')).toBe(true);
  });
});

describe('kiosk admin fingerprints', () => {
  const adminToken = () => ({ Authorization: `Bearer ${superToken()}` });

  beforeEach(() => {
    store['admins.json'] = [{
      adminId: 'ADM-1',
      employeeId: 'empsa001',
      name: 'Admin One',
      email: 'admin@dssmeet.io',
      role: 'kiosk_admin',
      status: 'active',
      prisonId: PRISON,
      kioskId: KIOSK,
      assignedKioskIds: [KIOSK],
      password: '$2b$10$staleadminpasswordhashfakefakefakefakefakefakefa'
    }];
  });

  test('add, list, match and remove an admin fingerprint', async () => {
    const added = await request(app)
      .post('/kiosk-admins/ADM-1/fingerprints').set(ip()).set(adminToken())
      .send({ capture: TEMPLATE });
    expect(added.status).toBe(201);
    expect(data(added).fingerprintCount).toBe(1);
    expect(added.text).not.toContain(TEMPLATE);
    const biometricId = data(added).fingerprints[0].biometricId;

    const listed = await request(app).get('/kiosk-admins').set(ip()).set(adminToken());
    expect(listed.status).toBe(200);
    const admin = data(listed).find((a) => a.adminId === 'ADM-1');
    expect(admin.fingerprintCount).toBe(1);
    expect(admin.fingerprints[0]).toMatchObject({ biometricId });
    expect(admin.fingerprints[0]).not.toHaveProperty('template');
    expect(listed.text).not.toContain(TEMPLATE);

    const candidatesRes = await request(app)
      .post('/auth/admin/fingerprint-candidates').set(ip())
      .set('Authorization', `Bearer ${deviceToken(KIOSK)}`)
      .send({ kioskId: KIOSK });
    expect(candidatesRes.status).toBe(200);
    const candidate = data(candidatesRes).find((c) => c.adminId === 'ADM-1');
    expect(candidate.fingerprintTemplate).toBe(TEMPLATE);

    const login = await request(app)
      .post('/auth/admin/fingerprint-login').set(ip())
      .set('Authorization', `Bearer ${deviceToken(KIOSK)}`)
      .send({ adminId: 'ADM-1', kioskId: KIOSK });
    expect(login.status).toBe(200);
    expect(data(login).adminId).toBe('ADM-1');
    expect(data(login).accessToken).toEqual(expect.any(String));

    const removed = await request(app)
      .delete(`/kiosk-admins/ADM-1/fingerprints/${biometricId}`).set(ip()).set(adminToken());
    expect(removed.status).toBe(200);
    expect(data(removed).fingerprintCount).toBe(0);

    const after = await request(app)
      .post('/auth/admin/fingerprint-candidates').set(ip())
      .set('Authorization', `Bearer ${deviceToken(KIOSK)}`)
      .send({ kioskId: KIOSK });
    expect(data(after).some((c) => c.adminId === 'ADM-1')).toBe(false);
  });

  test('admin fingerprint enrolment stops at three', async () => {
    for (const capture of [TEMPLATE, TEMPLATE_2, TEMPLATE_3]) {
      const res = await request(app)
        .post('/kiosk-admins/ADM-1/fingerprints').set(ip()).set(adminToken())
        .send({ capture });
      expect(res.status).toBe(201);
    }

    const fourth = await request(app)
      .post('/kiosk-admins/ADM-1/fingerprints').set(ip()).set(adminToken())
      .send({ capture: TEMPLATE_NEW });
    expect(fourth.status).toBe(409);
    expect(fourth.body.error.code).toBe('FINGERPRINT_LIMIT');
  });
});
