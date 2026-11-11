/**
 * DASHBOARD AUTO-LOGOUT (INACTIVITY) SUITE
 *
 * The warden's own desktop session timeout is a SERVER-STORED policy:
 *   securitySettings.autoLogout = { enabled: boolean, timeoutMinutes: 2|5|10 }
 * The server only stores the value — the countdown runs in the browser.
 * (The inmate kiosk has its own, separate, fixed 2-minute inactivity logout
 * inside the app; it has nothing to do with this setting.)
 *
 * Locks in:
 *   1. A warden CAN write the policy (scoped endpoint) — the generic
 *      PATCH /settings stays vendor-only.
 *   2. The endpoint only ever writes those two keys — nothing else in the
 *      settings document can be touched through it.
 *   3. Invalid timeouts (0, 3, 15, "abc", ...) are rejected with 400.
 *   4. A kiosk/admin token cannot change the policy (403).
 *   5. GET /settings exposes the policy to the dashboard.
 *   6. Every change is written to the audit log.
 *
 *   cd backend && npx jest settings-auto-logout
 */
const request = require('supertest');
const jwt = require('jsonwebtoken');

process.env.JWT_SECRET = process.env.JWT_SECRET || 'auto-logout-suite-secret-0123456789';
process.env.SMS_PROVIDER = 'log';
process.env.FAMILY_WEB_URL = 'http://localhost:5173';

jest.setTimeout(60000);

// uuid v14 is ESM-only; the suites run under CJS jest.
jest.mock('uuid', () => ({ v4: () => '00000000-0000-4000-8000-000000000000' }));

jest.mock('../lib/db', () => {
  const store = {};
  const clone = (v) => (v === undefined ? v : JSON.parse(JSON.stringify(v)));
  return {
    __store: store,
    readDb: async (f) => clone(store[f] || []),
    // Mirror the real lib/db.js contract: updateDb resolves with `result`.
    updateDb: async (f, mutator) => {
      const out = mutator(clone(store[f] || []));
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
const token = (role, over = {}) =>
  jwt.sign({ sub: `${role}-1`, role, ...over }, SECRET, { expiresIn: '10m' });

const wardenToken = token('warden', { prisonId: 'PR-1' });
const vendorToken = token('vendor');
const kioskToken = token('kiosk_admin', { kioskId: 'KSK-1', prisonId: 'PR-1' });

const data = (res) => res.body.data;

function resetStore() {
  store['settings.json'] = [{
    callSettings: { maxCallDurationMinutes: 15 },
    systemSettings: { maintenanceMode: false },
    securitySettings: {},
  }];
  store['audit_logs.json'] = [];
}

beforeEach(resetStore);

describe('kiosk auto-logout policy (server-side)', () => {
  test('warden can enable auto-logout with a 10 minute timeout', async () => {
    const res = await request(app)
      .patch('/settings/auto-logout')
      .set('Authorization', `Bearer ${wardenToken}`)
      .send({ enabled: true, timeoutMinutes: 10 });

    expect(res.status).toBe(200);
    expect(data(res).securitySettings.autoLogout).toEqual({ enabled: true, timeoutMinutes: 10 });
  });

  test('the policy lands in the stored settings document', async () => {
    await request(app)
      .patch('/settings/auto-logout')
      .set('Authorization', `Bearer ${wardenToken}`)
      .send({ enabled: false, timeoutMinutes: 5 });

    const stored = store['settings.json'][0];
    expect(stored.securitySettings.autoLogout.enabled).toBe(false);
    expect(stored.securitySettings.autoLogout.timeoutMinutes).toBe(5);
  });

  test('the endpoint cannot touch anything except the auto-logout keys', async () => {
    await request(app)
      .patch('/settings/auto-logout')
      .set('Authorization', `Bearer ${wardenToken}`)
      .send({
        enabled: true,
        timeoutMinutes: 2,
        // Attempted smuggling of unrelated settings.
        callSettings: { maxCallDurationMinutes: 999 },
        securitySettings: { maxLoginAttempts: 0 },
        pricing: { video: { ratePerMinute: 0 } },
      });

    const stored = store['settings.json'][0];
    expect(stored.callSettings.maxCallDurationMinutes).toBe(15);
    expect(stored.securitySettings.maxLoginAttempts).toBeUndefined();
    expect(stored.pricing).toBeUndefined();
    expect(stored.securitySettings.autoLogout).toEqual({ enabled: true, timeoutMinutes: 2 });
  });

  test('partial updates keep the other value', async () => {
    await request(app)
      .patch('/settings/auto-logout')
      .set('Authorization', `Bearer ${wardenToken}`)
      .send({ enabled: true, timeoutMinutes: 10 });

    const res = await request(app)
      .patch('/settings/auto-logout')
      .set('Authorization', `Bearer ${wardenToken}`)
      .send({ enabled: false });

    expect(res.status).toBe(200);
    expect(data(res).securitySettings.autoLogout).toEqual({ enabled: false, timeoutMinutes: 10 });
  });

  test.each([0, 1, 3, 15, 60, -5, 'abc', null])('rejects an invalid timeout (%p)', async (timeoutMinutes) => {
    const res = await request(app)
      .patch('/settings/auto-logout')
      .set('Authorization', `Bearer ${wardenToken}`)
      .send({ enabled: true, timeoutMinutes });

    expect(res.status).toBe(400);
    expect(store['settings.json'][0].securitySettings?.autoLogout).toBeUndefined();
  });

  test('rejects a non-boolean enabled flag', async () => {
    const res = await request(app)
      .patch('/settings/auto-logout')
      .set('Authorization', `Bearer ${wardenToken}`)
      .send({ enabled: 'yes-please' });

    expect(res.status).toBe(400);
  });

  test('rejects an empty patch', async () => {
    const res = await request(app)
      .patch('/settings/auto-logout')
      .set('Authorization', `Bearer ${wardenToken}`)
      .send({});

    expect(res.status).toBe(400);
  });

  test('a kiosk admin cannot change the policy', async () => {
    const res = await request(app)
      .patch('/settings/auto-logout')
      .set('Authorization', `Bearer ${kioskToken}`)
      .send({ enabled: false, timeoutMinutes: 10 });

    expect(res.status).toBe(403);
    expect(store['settings.json'][0].securitySettings?.autoLogout).toBeUndefined();
  });

  test('an anonymous caller cannot change the policy', async () => {
    const res = await request(app)
      .patch('/settings/auto-logout')
      .send({ enabled: false, timeoutMinutes: 10 });

    expect(res.status).toBe(401);
  });

  test('the vendor may also set it', async () => {
    const res = await request(app)
      .patch('/settings/auto-logout')
      .set('Authorization', `Bearer ${vendorToken}`)
      .send({ enabled: true, timeoutMinutes: 5 });

    expect(res.status).toBe(200);
  });

  test('GET /settings exposes the policy to the dashboard', async () => {
    await request(app)
      .patch('/settings/auto-logout')
      .set('Authorization', `Bearer ${wardenToken}`)
      .send({ enabled: true, timeoutMinutes: 10 });

    const res = await request(app)
      .get('/settings')
      .set('Authorization', `Bearer ${kioskToken}`);

    expect(res.status).toBe(200);
    expect(data(res).securitySettings.autoLogout).toEqual({ enabled: true, timeoutMinutes: 10 });
  });
});
