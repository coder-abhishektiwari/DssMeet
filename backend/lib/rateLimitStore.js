/**
 * Durable rate-limit state.
 *
 * Two entry points:
 *  - `createPgStore(windowMs)` → an express-rate-limit v8 Store backed by the
 *    `rate_limit_counters` table (db-schema/015). Pass it as `store:` on a
 *    limiter so restarts no longer hand an attacker a fresh brute-force
 *    budget. Returns undefined when persistence is unavailable, in which case
 *    express-rate-limit falls back to its default per-process MemoryStore —
 *    exactly today's behaviour.
 *  - `checkRateLimit(key, max, windowMs)` → the async replacement for the
 *    hand-rolled in-memory helper in routes/kiosks.js (call sites must await).
 *
 * Rules:
 *  - Tests (NODE_ENV=test) and DB-less dev always get the memory path, so the
 *    jest suite behaves exactly as before.
 *  - SQL failures fail OPEN (warn + memory fallback): a missing migration
 *    must not turn every login into a 500 — the row still can't proceed
 *    without valid credentials.
 */
const { pool } = require('./db');

let storeSeq = 0;

function persistent() {
  return Boolean(pool) && process.env.NODE_ENV !== 'test';
}

// Shared memory fallback used by checkRateLimit (no pool / test / SQL error).
const memoryCounters = new Map();
function memoryIncrement(key, maxAttempts, windowMs) {
  const now = Date.now();
  const record = memoryCounters.get(key) || { count: 0, resetAt: now + windowMs };
  if (now > record.resetAt) { record.count = 0; record.resetAt = now + windowMs; }
  record.count++;
  memoryCounters.set(key, record);
  return record.count <= maxAttempts;
}

/**
 * Upsert one hit. Window restarts (count/reset_at rewritten) when the stored
 * reset_at has passed, otherwise the hit is added to the running window.
 * Returns { count, resetAt } or null when persistence is unavailable/failing.
 */
async function bump(nsKey, windowMs) {
  const { rows } = await pool.query(
    `INSERT INTO rate_limit_counters (key, count, reset_at)
     VALUES ($1, 1, now() + make_interval(secs => $2))
     ON CONFLICT (key) DO UPDATE SET
       count = CASE WHEN rate_limit_counters.reset_at <= now()
                    THEN 1 ELSE rate_limit_counters.count + 1 END,
       reset_at = CASE WHEN rate_limit_counters.reset_at <= now()
                       THEN now() + make_interval(secs => $2)
                       ELSE rate_limit_counters.reset_at END
     RETURNING count, reset_at`,
    [nsKey, Math.max(windowMs, 1000) / 1000]
  );
  const row = rows[0] || { count: 1, reset_at: new Date(Date.now() + windowMs) };
  return { count: Number(row.count), resetAt: new Date(row.reset_at) };
}

/**
 * express-rate-limit Store (v8 shape: increment/decrement/resetKey + init).
 * Each instance namespaces its keys — every limiter gets its own store, and
 * default keys are just the client IP, so sharing one table must never let
 * authLimiter and identifyLimiter count the same request twice.
 */
function createPgStore(windowMs = 60_000) {
  if (!persistent()) return undefined;

  const ns = `s${(storeSeq++).toString(36)}${Date.now().toString(36).slice(-4)}`;
  const prefix = `${ns}:`;
  const local = new Map(); // memory fallback when SQL itself is broken
  let sweep = null;

  const nsKey = (key) => `${prefix}${key}`;

  const memoryHit = (key) => {
    const now = Date.now();
    const record = local.get(key) || { count: 0, resetAt: now + windowMs };
    if (now > record.resetAt) { record.count = 0; record.resetAt = now + windowMs; }
    record.count++;
    local.set(key, record);
    return { totalHits: record.count, resetTime: new Date(record.resetAt) };
  };

  const sweepExpired = () => {
    pool.query('DELETE FROM rate_limit_counters WHERE reset_at < now()').catch(() => {});
  };

  return {
    localKeys: false,
    prefix,
    init() {
      sweepExpired();
      if (!sweep) {
        sweep = setInterval(sweepExpired, 10 * 60 * 1000);
        if (typeof sweep.unref === 'function') sweep.unref();
      }
    },
    shutdown() {
      if (sweep) { clearInterval(sweep); sweep = null; }
    },
    async increment(key) {
      try {
        const { count, resetAt } = await bump(nsKey(key), windowMs);
        return { totalHits: count, resetTime: resetAt };
      } catch (err) {
        console.warn(`[rate-limit] pg increment failed, using memory for this window: ${err.message}`);
        return memoryHit(key);
      }
    },
    async decrement(key) {
      try {
        await pool.query(
          'UPDATE rate_limit_counters SET count = GREATEST(count - 1, 0) WHERE key = $1',
          [nsKey(key)]
        );
      } catch (err) {
        console.warn(`[rate-limit] pg decrement failed: ${err.message}`);
      }
    },
    async resetKey(key) {
      try {
        await pool.query('DELETE FROM rate_limit_counters WHERE key = $1', [nsKey(key)]);
      } catch (err) {
        console.warn(`[rate-limit] pg resetKey failed: ${err.message}`);
      }
      local.delete(key);
    },
  };
}

/**
 * Drop-in async replacement for the old synchronous kiosks.js helper:
 * same "increment then compare against max" window semantics, but the counter
 * lives in Postgres when it can (restart-proof) and in memory otherwise.
 */
async function checkRateLimit(key, maxAttempts = 5, windowMs = 60000) {
  if (!persistent()) return memoryIncrement(key, maxAttempts, windowMs);
  try {
    const { count } = await bump(`rl:${key}`, windowMs);
    return count <= maxAttempts;
  } catch (err) {
    console.warn(`[rate-limit] pg checkRateLimit failed, using memory: ${err.message}`);
    return memoryIncrement(key, maxAttempts, windowMs);
  }
}

module.exports = { createPgStore, checkRateLimit };
