/**
 * Balance-enforcement sweep: a live call is cut once the wallet can no
 * longer pay for it, the room close carries reason "insufficient_balance",
 * and every non-eligible call (lobby-only, free rate, missing wallet,
 * already-finalized) is left alone.
 *
 *   cd backend && npx jest balance-sweep
 */
process.env.FAMILY_WEB_URL = 'http://localhost:5173';
process.env.JWT_SECRET = process.env.JWT_SECRET || 'jest-only-secret-0123456789abcdef';

// uuid v14 is ESM-only; Jest's CJS runtime cannot require it.
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
    transact: async () => {
      throw new Error('not used in tests');
    },
    query: async () => ({ rows: [] }),
    ping: async () => true,
    pool: null,
  };
});

const { sweepExhaustedBalance } = require('../routes/calls');
const { __store: store } = require('../lib/db');

const NOW = Date.now();
const iso = (ms) => new Date(ms).toISOString();

function resetStore(call = {}, wallet = {}) {
  store['calls.json'] = [{
    callId: 'CALL-SWEEP-1',
    roomId: 'ROOM-SWEEP-1',
    inmateId: 'INM-1',
    status: 'active',
    startTime: iso(NOW - 10 * 60000),
    mediaConnectedAt: iso(NOW - 3.5 * 60000),
    maxDurationMinutes: 30,
    ratePerMinute: 2,
    linkToken: null,
    ...call,
  }];
  store['wallets.json'] = [{
    walletId: 'W-1',
    inmateId: 'INM-1',
    balance: 500,
    totalSpent: 0,
    currency: 'INR',
    ...wallet,
  }];
  store['inmates.json'] = [{ inmateId: 'INM-1', name: 'Test Inmate', status: 'active' }];
  store['transactions.json'] = [];
  store['rooms.json'] = [{ roomId: 'ROOM-SWEEP-1', status: 'active', participants: ['p1', 'p2'], participantCount: 2 }];
}

let signaling;
let broadcast;

beforeEach(() => {
  jest.clearAllMocks();
  signaling = jest.fn(async () => ({ success: true }));
  broadcast = jest.fn();
  resetStore();
});

describe('sweepExhaustedBalance', () => {
  // Connected 3.5 min ago, ₹5 left, ₹2/min -> allowance 2.5 min -> cut.
  // 3.5 min is safely off the whole-minute ceil boundary (60s of slack below).
  test('cuts a live call the wallet can no longer afford', async () => {
    await sweepExhaustedBalance(broadcast, signaling);

    const call = store['calls.json'][0];
    expect(call.status).toBe('completed');
    expect(call.endReason).toBe('insufficient_balance');
    expect(call.endReasonDescription).toBe('Call ended — wallet balance exhausted');
    // ceil(3.5 min) = 4 billed minutes @ ₹2 = 800 paise.
    expect(call.chargeAmount).toBe(800);
    expect(store['wallets.json'][0].balance).toBe(0);
    expect(store['transactions.json']).toHaveLength(1);
    expect(store['transactions.json'][0].type).toBe('charge');

    expect(signaling).toHaveBeenCalledWith('POST', '/api/rooms/ROOM-SWEEP-1/close', { reason: 'insufficient_balance' });
    expect(broadcast).toHaveBeenCalledWith('call-ended', { callId: 'CALL-SWEEP-1', status: 'completed', reason: 'insufficient_balance' });

    const room = store['rooms.json'][0];
    expect(room.status).toBe('idle');
    expect(room.participants).toEqual([]);
    expect(room.participantCount).toBe(0);
  });

  test('a mid-call top-up extends the allowance - call untouched', async () => {
    resetStore({}, { balance: 10000 }); // ₹100 @ ₹2/min = 50 min allowance
    await sweepExhaustedBalance(broadcast, signaling);

    expect(store['calls.json'][0].status).toBe('active');
    expect(store['wallets.json'][0].balance).toBe(10000);
    expect(signaling).not.toHaveBeenCalled();
    expect(broadcast).not.toHaveBeenCalled();
  });

  test('call within its allowance is untouched', async () => {
    // Connected only 1 min ago; ₹5 @ ₹2/min covers 2.5 min.
    resetStore({ mediaConnectedAt: iso(NOW - 60000) });
    await sweepExhaustedBalance(broadcast, signaling);

    expect(store['calls.json'][0].status).toBe('active');
    expect(signaling).not.toHaveBeenCalled();
  });

  test('lobby-only call (media never connected) is never cut, even at zero balance', async () => {
    resetStore({ mediaConnectedAt: null, startTime: iso(NOW - 30 * 60000) }, { balance: 0 });
    await sweepExhaustedBalance(broadcast, signaling);

    expect(store['calls.json'][0].status).toBe('active');
    expect(signaling).not.toHaveBeenCalled();
  });

  test('free call (rate 0) is never cut', async () => {
    resetStore({ ratePerMinute: 0 }, { balance: 0 });
    await sweepExhaustedBalance(broadcast, signaling);

    expect(store['calls.json'][0].status).toBe('active');
    expect(signaling).not.toHaveBeenCalled();
  });

  test('missing wallet row never kills a live call', async () => {
    store['wallets.json'] = [];
    await sweepExhaustedBalance(broadcast, signaling);

    expect(store['calls.json'][0].status).toBe('active');
    expect(signaling).not.toHaveBeenCalled();
  });

  test('already-finalized row is skipped: no close, no broadcast', async () => {
    resetStore({ finalizedAt: iso(NOW - 60000) }, { balance: 0 });
    await sweepExhaustedBalance(broadcast, signaling);

    expect(signaling).not.toHaveBeenCalled();
    expect(broadcast).not.toHaveBeenCalled();
  });

  test('inactive calls are out of scope entirely', async () => {
    resetStore({ status: 'completed' }, { balance: 0 });
    await sweepExhaustedBalance(broadcast, signaling);

    expect(signaling).not.toHaveBeenCalled();
    expect(broadcast).not.toHaveBeenCalled();
  });
});
