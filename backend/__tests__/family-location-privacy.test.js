/**
 * Family location privacy across roles:
 *   - inmate-role tokens NEVER get family.location in history/list responses
 *   - admin/kiosk-admin/warden tokens keep it (their history screens render it)
 *   - GET /calls/:callId keeps it for the inmate too — that response feeds
 *     the live location chip on the active call screen.
 *
 *   cd backend && npx jest family-location-privacy
 */
process.env.FAMILY_WEB_URL = 'http://localhost:5173';
process.env.JWT_SECRET = process.env.JWT_SECRET || 'jest-only-secret-0123456789abcdef';

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
    transact: async () => { throw new Error('not used in tests'); },
    query: async () => ({ rows: [] }),
    ping: async () => true,
    pool: null,
  };
});

const request = require('supertest');
const jwt = require('jsonwebtoken');
const { app } = require('../server');
const { __store: store } = require('../lib/db');

const SECRET = process.env.JWT_SECRET;
const KIOSK = 'KSK-1';
const INMATE = 'INM-1';
const CONTACT = 'CNT-1';
const CALL = 'CALL-LOC-1';
const PRISON = 'PR-1';
const LOCATION = { lat: 19.07, lng: 72.87, area: 'Colaba', city: 'Mumbai' };

const inmateToken = (over = {}) =>
  jwt.sign({ sub: INMATE, role: 'inmate', inmateId: INMATE, kioskId: KIOSK, ...over }, SECRET, { expiresIn: '10m' });
const adminToken = (over = {}) =>
  jwt.sign({ sub: 'ADM-1', role: 'kiosk_admin', kioskId: KIOSK, prisonId: PRISON, ...over }, SECRET, { expiresIn: '10m' });

let xff = 0;
const ip = () => ({ 'X-Forwarded-For': `10.77.${Math.floor(++xff / 250)}.${xff % 250}` });
const data = (res) => res.body.data;
const get = (url, token) => request(app).get(url).set(ip()).set('Authorization', `Bearer ${token}`);

function resetStore() {
  store['inmates.json'] = [{
    inmateId: INMATE, name: 'Test Inmate', status: 'active',
    prisonId: PRISON, assignedKioskId: KIOSK, prisonerNumber: 'P-2001',
  }];
  store['contacts.json'] = [{
    contactId: CONTACT, inmateId: INMATE, name: 'Rita Kumari', status: 'active',
    active: true, approvalStatus: 'approved', phoneNumber: '+919876543210',
  }];
  store['kiosks.json'] = [{
    kioskId: KIOSK, prisonId: PRISON, status: 'active',
    authorizationStatus: 'authorized', deviceSerialNumber: 'SN-0002',
  }];
  store['prisons.json'] = [{ prisonId: PRISON, name: 'Central Jail', status: 'active' }];
  store['calls.json'] = [{
    callId: CALL, roomId: 'RM-LOC-1', inmateId: INMATE, contactId: CONTACT, kioskId: KIOSK,
    prisonId: PRISON, facility: PRISON, status: 'completed', type: 'video',
    startTime: new Date(Date.now() - 3600000).toISOString(),
    mediaConnectedAt: new Date(Date.now() - 3540000).toISOString(),
    endTime: new Date(Date.now() - 3300000).toISOString(),
    durationMinutes: 4, maxDurationMinutes: 10, ratePerMinute: 2.5,
    familyMemberName: 'Rita Kumari',
    family: {
      deviceVerified: true, otpVerified: true,
      doorwayCode: 'D-9',
      location: { ...LOCATION, at: new Date().toISOString() },
    },
  }];
  store['wallets.json'] = [];
  store['transactions.json'] = [];
  store['settings.json'] = [];
  store['pricing.json'] = [];
}

beforeEach(resetStore);

describe('family location in call history', () => {
  test('inmate role: /calls/history/:id has NO family.location', async () => {
    const res = await get(`/calls/history/${INMATE}`, inmateToken());
    expect(res.status).toBe(200);
    const row = data(res)[0];
    expect(row.callId).toBe(CALL);
    expect(row.family).toBeDefined();
    expect(row.family.location).toBeUndefined();
    // Other display fields survive the strip.
    expect(row.family.doorwayCode).toBe('D-9');
    expect(row.contactName).toBe('Rita Kumari');
  });

  test('inmate role: paged /calls/history has NO family.location', async () => {
    const res = await get('/calls/history', inmateToken());
    expect(res.status).toBe(200);
    const row = data(res).calls[0];
    expect(row.callId).toBe(CALL);
    expect(row.family.location).toBeUndefined();
  });

  test('kiosk admin role: paged /calls/history KEEPS family.location', async () => {
    const res = await get('/calls/history', adminToken());
    expect(res.status).toBe(200);
    const row = data(res).calls[0];
    expect(row.family.location).toEqual(expect.objectContaining({ lat: 19.07, lng: 72.87, area: 'Colaba' }));
  });

  test('kiosk admin role: /calls/history/:id KEEPS family.location', async () => {
    const res = await get(`/calls/history/${INMATE}`, adminToken());
    expect(res.status).toBe(200);
    expect(data(res)[0].family.location).toEqual(expect.objectContaining({ lat: 19.07, lng: 72.87 }));
  });

  test('live call chip: GET /calls/:callId keeps location for the inmate role', async () => {
    const res = await get(`/calls/${CALL}`, inmateToken());
    expect(res.status).toBe(200);
    // This is the response the active call screen polls for the location pill.
    expect(data(res).family.location).toEqual(expect.objectContaining({ lat: 19.07, lng: 72.87, area: 'Colaba' }));
  });
});
