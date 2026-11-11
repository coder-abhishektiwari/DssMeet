/**
 * KIOSK FIRST-ADMIN BOOTSTRAP ("Create Admin") SUITE
 *
 * Locks in the contract behind the kiosk's Create Admin button:
 *   1. GET  /kiosk-admins/bootstrap-status — device token only; needsBootstrap
 *      is true exactly while the kiosk's jail has no active, non-super admin
 *      (the same predicate /auth/admin/identify uses).
 *   2. POST /kiosk-admins/bootstrap — creates a warden-dashboard-shaped
 *      kiosk_admin scoped to the DEVICE's jail, and signs the creator in.
 *   3. Preconditions are re-checked inside the write: a second call (or a
 *      racing kiosk) cannot create a second "first" admin — 409.
 *   4. Field rules match POST /kiosk-admins exactly (username regex, min-6
 *      password, unique username/email).
 *   5. A user (non-device) session token can never call either route.
 *
 *   cd backend && npx jest kiosk-bootstrap
 */
const request = require('supertest');
const jwt = require('jsonwebtoken');

process.env.JWT_SECRET = process.env.JWT_SECRET || 'bootstrap-suite-secret-0123456789abcdef';
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
    // mirroring that is what makes route-level assertions meaningful.
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
const PRISON = 'PR-BOOT';
const PRISON_B = 'PR-OTHER';
const KIOSK = 'KSK-BOOT';
const KIOSK_B = 'KSK-OTHER';

const deviceToken = (kioskId, prisonId, over = {}) =>
  jwt.sign({ sub: kioskId, role: 'kiosk', kioskId, prisonId, device: true, ...over }, SECRET, { expiresIn: '10m' });

const userToken = (over = {}) =>
  jwt.sign({ sub: 'USR-1', role: 'warden', prisonId: PRISON, ...over }, SECRET, { expiresIn: '10m' });

let xff = 0;
const ip = () => ({ 'X-Forwarded-For': `10.77.${Math.floor(++xff / 250)}.${xff % 250}` });

const data = (res) => res.body.data;

function resetStore() {
  store['prisons.json'] = [
    { prisonId: PRISON, name: 'Bootstrap Jail', status: 'active' },
    { prisonId: PRISON_B, name: 'Other Jail', status: 'active' },
  ];
  store['kiosks.json'] = [
    { kioskId: KIOSK, prisonId: PRISON, status: 'active', authorizationStatus: 'authorized', deviceSerialNumber: 'SN-BOOT' },
    { kioskId: KIOSK_B, prisonId: PRISON_B, status: 'active', authorizationStatus: 'authorized', deviceSerialNumber: 'SN-OTHER' },
  ];
  store['admins.json'] = [];
}

const status = (tok) => request(app).get('/kiosk-admins/bootstrap-status').set(ip()).set('Authorization', `Bearer ${tok}`);

const create = (tok, body) => request(app).post('/kiosk-admins/bootstrap').set(ip()).set('Authorization', `Bearer ${tok}`).send(body);

const validBody = (over = {}) => ({
  name: 'Neha Gupta',
  employeeId: 'empsa003',
  email: 'neha@dssmeet.io',
  password: 'secret1',
  ...over,
});

beforeEach(resetStore);

describe('GET /kiosk-admins/bootstrap-status', () => {
  test('device token + empty jail → needsBootstrap true', async () => {
    const res = await status(deviceToken(KIOSK, PRISON));
    expect(res.status).toBe(200);
    expect(data(res)).toMatchObject({ needsBootstrap: true, kioskId: KIOSK, prisonId: PRISON });
  });

  test('jail already has an active kiosk admin → needsBootstrap false', async () => {
    store['admins.json'] = [{
      adminId: 'ADM-EXIST', employeeId: 'ops1', name: 'Ops', email: null,
      role: 'kiosk_admin', status: 'active', prisonId: PRISON, password: '$2b$10$fakefakefakefakefakefak',
    }];
    const res = await status(deviceToken(KIOSK, PRISON));
    expect(res.status).toBe(200);
    expect(data(res).needsBootstrap).toBe(false);
  });

  test('admins of ANOTHER jail do not count — second jail still bootstraps', async () => {
    store['admins.json'] = [{
      adminId: 'ADM-EXIST', employeeId: 'ops1', name: 'Ops', email: null,
      role: 'kiosk_admin', status: 'active', prisonId: PRISON, password: '$2b$10$fakefakefakefakefakefak',
    }];
    const res = await status(deviceToken(KIOSK_B, PRISON_B));
    expect(res.status).toBe(200);
    expect(data(res).needsBootstrap).toBe(true);
  });

  test('inactive and super admins do not count as bootstrapped', async () => {
    store['admins.json'] = [
      { adminId: 'A1', role: 'kiosk_admin', status: 'inactive', prisonId: PRISON },
      { adminId: 'A2', role: 'super_admin', status: 'active', prisonId: PRISON },
    ];
    const res = await status(deviceToken(KIOSK, PRISON));
    expect(res.status).toBe(200);
    expect(data(res).needsBootstrap).toBe(true);
  });

  test('user session token (not a device token) is rejected', async () => {
    const res = await status(userToken({ kioskId: KIOSK }));
    expect(res.status).toBe(403);
    expect(res.body.error.code).toBe('FORBIDDEN');
  });

  test('missing token is rejected', async () => {
    const res = await request(app).get('/kiosk-admins/bootstrap-status').set(ip());
    expect(res.status).toBe(401);
  });

  test('unauthorized kiosk device is rejected', async () => {
    store['kiosks.json'][0] = { ...store['kiosks.json'][0], authorizationStatus: 'pending' };
    const res = await status(deviceToken(KIOSK, PRISON));
    expect(res.status).toBe(403);
    expect(res.body.error.code).toBe('UNAUTHORIZED');
  });
});

describe('POST /kiosk-admins/bootstrap', () => {
  test('creates a warden-dashboard-shaped kiosk_admin scoped to the DEVICE jail and signs in', async () => {
    const res = await create(deviceToken(KIOSK, PRISON), validBody());
    expect(res.status).toBe(201);

    const body = data(res);
    expect(body.accessToken).toBeTruthy();
    expect(body.refreshToken).toBeTruthy();
    expect(body.expiresIn).toBe(3600);
    expect(body.role).toBe('kiosk_admin');
    expect(body.prisonId).toBe(PRISON);
    expect(body.kioskId).toBe(KIOSK);
    expect(body.profile).toMatchObject({ employeeId: 'empsa003', name: 'Neha Gupta', email: 'neha@dssmeet.io', status: 'active' });
    // Credential material never leaves the API.
    expect(JSON.stringify(body)).not.toContain('$2b$');

    // Token is a real session for this admin + kiosk.
    const claims = jwt.verify(body.accessToken, SECRET);
    expect(claims).toMatchObject({ sub: body.adminId, role: 'kiosk_admin', kioskId: KIOSK, prisonId: PRISON });

    const row = store['admins.json'][0];
    expect(row).toMatchObject({ role: 'kiosk_admin', status: 'active', prisonId: PRISON, employeeId: 'empsa003' });
    expect(row.adminId).toBe(body.adminId);
    expect(row.password).toMatch(/^\$2[aby]\$/);
    expect(row.password).not.toBe('secret1');
  });

  test('same request body the warden dashboard sends (no email) is accepted', async () => {
    const res = await create(deviceToken(KIOSK, PRISON), validBody({ email: undefined }));
    expect(res.status).toBe(201);
    expect(data(res).profile.email).toBe('');
    expect(store['admins.json'][0].email).toBeNull();
  });

  test('second call (any device of that jail) → 409 NOT_BOOTSTRAPPABLE', async () => {
    await create(deviceToken(KIOSK, PRISON), validBody());

    const second = await create(deviceToken(KIOSK, PRISON), validBody({ employeeId: 'empsa004' }));
    expect(second.status).toBe(409);
    expect(second.body.error.code).toBe('NOT_BOOTSTRAPPABLE');
    expect(store['admins.json']).toHaveLength(1);
  });

  test('password < 6 chars → 400, nothing written', async () => {
    const res = await create(deviceToken(KIOSK, PRISON), validBody({ password: 'abc' }));
    expect(res.status).toBe(400);
    expect(store['admins.json']).toHaveLength(0);
  });

  test('username outside EMPLOYEE_ID_RE → 400', async () => {
    const res = await create(deviceToken(KIOSK, PRISON), validBody({ employeeId: 'a b' }));
    expect(res.status).toBe(400);
    expect(store['admins.json']).toHaveLength(0);
  });

  test('malformed email → 400', async () => {
    const res = await create(deviceToken(KIOSK, PRISON), validBody({ email: 'not-an-email' }));
    expect(res.status).toBe(400);
    expect(store['admins.json']).toHaveLength(0);
  });

  test('missing required field → 400', async () => {
    const res = await create(deviceToken(KIOSK, PRISON), validBody({ employeeId: '' }));
    expect(res.status).toBe(400);
    expect(store['admins.json']).toHaveLength(0);
  });

  test('username taken by an admin of ANOTHER jail → 409 DUPLICATE (global uniqueness)', async () => {
    store['admins.json'] = [{
      adminId: 'ADM-EXIST', employeeId: 'takenss', name: 'Elsewhere', email: null,
      role: 'kiosk_admin', status: 'active', prisonId: PRISON_B, password: '$2b$10$fakefakefakefakefakefak',
    }];
    const res = await create(deviceToken(KIOSK, PRISON), validBody({ employeeId: 'TakenSS' }));
    expect(res.status).toBe(409);
    expect(res.body.error.code).toBe('DUPLICATE');
    expect(store['admins.json']).toHaveLength(1);
  });

  test('email taken by an admin of ANOTHER jail → 409 DUPLICATE', async () => {
    store['admins.json'] = [{
      adminId: 'ADM-EXIST', employeeId: 'someone', name: 'Elsewhere', email: 'neha@dssmeet.io',
      role: 'kiosk_admin', status: 'active', prisonId: PRISON_B, password: '$2b$10$fakefakefakefakefakefak',
    }];
    const res = await create(deviceToken(KIOSK, PRISON), validBody());
    expect(res.status).toBe(409);
    expect(res.body.error.code).toBe('DUPLICATE');
    expect(store['admins.json']).toHaveLength(1);
  });

  test('a row without prisonId counts as an existing admin (identify would let it in)', async () => {
    store['admins.json'] = [{
      adminId: 'ADM-NOJAIL', employeeId: 'wanderer', name: 'Wanderer', email: null,
      role: 'kiosk_admin', status: 'active', prisonId: null, password: '$2b$10$fakefakefakefakefakefak',
    }];
    const res = await create(deviceToken(KIOSK, PRISON), validBody());
    expect(res.status).toBe(409);
    expect(res.body.error.code).toBe('NOT_BOOTSTRAPPABLE');
  });

  test('user session token (not a device token) is rejected and writes nothing', async () => {
    const res = await create(userToken({ kioskId: KIOSK }), validBody());
    expect(res.status).toBe(403);
    expect(store['admins.json']).toHaveLength(0);
  });

  test('device token jail is taken from the token identity, never the body', async () => {
    const res = await create(deviceToken(KIOSK, PRISON), validBody({ prisonId: PRISON_B }));
    expect(res.status).toBe(201);
    expect(store['admins.json'][0].prisonId).toBe(PRISON);
    expect(data(res).prisonId).toBe(PRISON);
  });

  test('bootstrap route is NOT reachable through the generic admin create (auth still required there)', async () => {
    // Sanity: the session-less caller cannot reach POST /kiosk-admins either.
    const res = await request(app).post('/kiosk-admins').set(ip()).send(validBody());
    expect(res.status).toBe(401);
    expect(store['admins.json']).toHaveLength(0);
  });
});
