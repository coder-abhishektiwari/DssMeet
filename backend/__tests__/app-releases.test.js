/**
 * APP RELEASE REGISTRY SUITE
 *
 * The registry tells a client (kiosk APK / warden desktop) whether a newer
 * build exists, whether it is MANDATORY (block the screen until updated) or
 * optional (just a notice), and where to get it + the sha256 to verify it.
 *
 * Locks in:
 *   1. Publishing is warden/admin only, with an allowlisted body.
 *   2. Invalid app/platform/version/url/sha256 are rejected with 400.
 *   3. Publishing a new release supersedes the previous one for that
 *      app+platform (and clears its mandatory flag).
 *   4. GET /latest needs no auth (a device must learn about a mandatory
 *      update before anyone signs in) and returns the newest release.
 *   5. The list endpoint stays behind auth.
 *   6. The kiosk heartbeat carries the manifest (no extra request).
 *
 *   cd backend && npx jest app-releases
 */
const request = require('supertest');
const jwt = require('jsonwebtoken');

process.env.JWT_SECRET = process.env.JWT_SECRET || 'app-releases-suite-secret-0123456789';
process.env.SMS_PROVIDER = 'log';
process.env.FAMILY_WEB_URL = 'http://localhost:5173';

jest.setTimeout(60000);

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
const wardenToken = jwt.sign({ sub: 'WRD-1', role: 'warden', prisonId: 'PR-1' }, SECRET, { expiresIn: '10m' });
const kioskToken = jwt.sign({ sub: 'KSK-1', role: 'kiosk_admin', kioskId: 'KSK-1', prisonId: 'PR-1' }, SECRET, { expiresIn: '10m' });

const data = (res) => res.body.data;
const APK_URL = 'https://github.com/coder-abhishektiwari/DssMeet/releases/download/kiosk-1.1.0/kiosk.apk';

function resetStore() {
  store['app-releases.json'] = [];
  store['kiosks.json'] = [{
    kioskId: 'KSK-1', prisonId: 'PR-1', status: 'active',
    authorizationStatus: 'authorized', deviceSerialNumber: 'SN-0001',
  }];
  store['audit_logs.json'] = [];
}

beforeEach(resetStore);

describe('app release registry', () => {
  test('a warden publishes a release', async () => {
    const res = await request(app)
      .post('/app-releases')
      .set('Authorization', `Bearer ${wardenToken}`)
      .send({
        app: 'kiosk', platform: 'android', version: '1.1.0', versionCode: 2,
        mandatory: true, downloadUrl: APK_URL, sha256: 'a'.repeat(64),
        notes: 'Security fix',
      });

    expect(res.status).toBe(201);
    expect(data(res)).toMatchObject({
      app: 'kiosk', platform: 'android', version: '1.1.0', versionCode: 2,
      mandatory: true, downloadUrl: APK_URL, notes: 'Security fix',
    });
    expect(store['app-releases.json']).toHaveLength(1);
  });

  test('the published release is readable by a device without a session', async () => {
    await request(app)
      .post('/app-releases')
      .set('Authorization', `Bearer ${wardenToken}`)
      .send({ app: 'kiosk', platform: 'android', version: '1.1.0', versionCode: 2, downloadUrl: APK_URL });

    const res = await request(app).get('/app-releases/latest?app=kiosk&platform=android');
    expect(res.status).toBe(200);
    expect(data(res).update.version).toBe('1.1.0');
    expect(data(res).update.versionCode).toBe(2);
  });

  test('GET /latest returns null when nothing was published', async () => {
    const res = await request(app).get('/app-releases/latest?app=kiosk&platform=android');
    expect(res.status).toBe(200);
    expect(data(res).update).toBeNull();
  });

  test('GET /latest rejects an unknown app', async () => {
    const res = await request(app).get('/app-releases/latest?app=tv&platform=android');
    expect(res.status).toBe(400);
  });

  test('a new release supersedes the old one (and clears its mandatory flag)', async () => {
    await request(app).post('/app-releases')
      .set('Authorization', `Bearer ${wardenToken}`)
      .send({ app: 'kiosk', platform: 'android', version: '1.1.0', versionCode: 2, mandatory: true, downloadUrl: APK_URL });

    await request(app).post('/app-releases')
      .set('Authorization', `Bearer ${wardenToken}`)
      .send({ app: 'kiosk', platform: 'android', version: '1.2.0', versionCode: 3, downloadUrl: APK_URL });

    const rows = store['app-releases.json'];
    expect(rows).toHaveLength(2);
    expect(rows[0].mandatory).toBe(false);
    expect(rows[1].version).toBe('1.2.0');

    const res = await request(app).get('/app-releases/latest?app=kiosk&platform=android');
    expect(data(res).update.version).toBe('1.2.0');
    expect(data(res).update.mandatory).toBe(false);
  });

  test('the highest versionCode wins, even when it is older by date', async () => {
    store['app-releases.json'] = [
      { releaseId: 'REL-A', app: 'kiosk', platform: 'android', version: '1.1.0', versionCode: 2, mandatory: false, downloadUrl: APK_URL, releasedAt: '2026-01-02T00:00:00.000Z' },
      { releaseId: 'REL-B', app: 'kiosk', platform: 'android', version: '1.3.0', versionCode: 5, mandatory: true, downloadUrl: APK_URL, releasedAt: '2026-01-01T00:00:00.000Z' },
    ];
    const res = await request(app).get('/app-releases/latest?app=kiosk&platform=android');
    expect(data(res).update.version).toBe('1.3.0');
    expect(data(res).update.mandatory).toBe(true);
  });

  test.each([
    ['unknown app', { app: 'tv', platform: 'android', version: '1.0.0', downloadUrl: APK_URL }],
    ['unknown platform', { app: 'kiosk', platform: 'symbian', version: '1.0.0', downloadUrl: APK_URL }],
    ['missing version', { app: 'kiosk', platform: 'android', downloadUrl: APK_URL }],
    ['http download url', { app: 'kiosk', platform: 'android', version: '1.0.0', downloadUrl: 'http://x/y.apk' }],
    ['bad sha256', { app: 'kiosk', platform: 'android', version: '1.0.0', downloadUrl: APK_URL, sha256: 'not-a-hash' }],
    ['bad versionCode', { app: 'kiosk', platform: 'android', version: '1.0.0', downloadUrl: APK_URL, versionCode: -3 }],
  ])('rejects an invalid publish (%s)', async (_label, payload) => {
    const res = await request(app)
      .post('/app-releases')
      .set('Authorization', `Bearer ${wardenToken}`)
      .send(payload);

    expect(res.status).toBe(400);
    expect(store['app-releases.json']).toHaveLength(0);
  });

  test('a kiosk device cannot publish a release', async () => {
    const res = await request(app)
      .post('/app-releases')
      .set('Authorization', `Bearer ${kioskToken}`)
      .send({ app: 'kiosk', platform: 'android', version: '9.9.9', downloadUrl: APK_URL });

    expect(res.status).toBe(403);
    expect(store['app-releases.json']).toHaveLength(0);
  });

  test('the list endpoint needs a session', async () => {
    const res = await request(app).get('/app-releases');
    expect(res.status).toBe(401);
  });

  test('a warden can flip a release to mandatory', async () => {
    const created = await request(app).post('/app-releases')
      .set('Authorization', `Bearer ${wardenToken}`)
      .send({ app: 'kiosk', platform: 'android', version: '1.1.0', versionCode: 2, downloadUrl: APK_URL });

    const res = await request(app)
      .patch(`/app-releases/${data(created).releaseId}`)
      .set('Authorization', `Bearer ${wardenToken}`)
      .send({ mandatory: true });

    expect(res.status).toBe(200);
    expect(data(res).mandatory).toBe(true);
    expect(store['app-releases.json'][0].mandatory).toBe(true);
  });

  test('the kiosk heartbeat carries the update manifest (no extra request)', async () => {
    await request(app).post('/app-releases')
      .set('Authorization', `Bearer ${wardenToken}`)
      .send({ app: 'kiosk', platform: 'android', version: '1.1.0', versionCode: 2, mandatory: true, downloadUrl: APK_URL });

    const res = await request(app)
      .post('/kiosks/heartbeat')
      .set('Authorization', `Bearer ${kioskToken}`)
      .send({ deviceSerialNumber: 'SN-0001', appVersion: '1.0.0' });

    expect(res.status).toBe(200);
    expect(data(res).update).toMatchObject({
      app: 'kiosk', version: '1.1.0', versionCode: 2, mandatory: true,
    });
  });
});

describe('CI publisher token', () => {
  const TOKEN = 'ci-publish-token-0123456789';

  beforeEach(() => {
    process.env.RELEASE_PUBLISH_TOKEN = TOKEN;
    resetStore();
  });

  afterEach(() => {
    delete process.env.RELEASE_PUBLISH_TOKEN;
  });

  test('the build publishes its own release with the token (no user session)', async () => {
    const res = await request(app)
      .post('/app-releases')
      .set('X-Release-Token', TOKEN)
      .send({
        app: 'kiosk', platform: 'android', version: '2.0.0', versionCode: 7,
        mandatory: true, downloadUrl: APK_URL, sha256: 'c'.repeat(64),
      });

    expect(res.status).toBe(201);
    expect(data(res)).toMatchObject({ app: 'kiosk', version: '2.0.0', versionCode: 7, mandatory: true });
    expect(store['app-releases.json']).toHaveLength(1);
  });

  test('a wrong token is rejected', async () => {
    const res = await request(app)
      .post('/app-releases')
      .set('X-Release-Token', 'nope')
      .send({ app: 'kiosk', platform: 'android', version: '2.0.0', downloadUrl: APK_URL });

    expect(res.status).toBe(401);
    expect(store['app-releases.json']).toHaveLength(0);
  });

  test('the token still cannot write nonsense (validation applies)', async () => {
    const res = await request(app)
      .post('/app-releases')
      .set('X-Release-Token', TOKEN)
      .send({ app: 'tv', platform: 'android', version: '2.0.0', downloadUrl: APK_URL });

    expect(res.status).toBe(400);
    expect(store['app-releases.json']).toHaveLength(0);
  });

  test('without the env var, machine publishing is off', async () => {
    delete process.env.RELEASE_PUBLISH_TOKEN;
    const res = await request(app)
      .post('/app-releases')
      .set('X-Release-Token', TOKEN)
      .send({ app: 'kiosk', platform: 'android', version: '2.0.0', downloadUrl: APK_URL });

    expect(res.status).toBe(401);
  });
});
