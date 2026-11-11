-- 015: durable counters for rate limiting.
--
-- The express-rate-limit stores and the hand-rolled kiosks.js checkRateLimit
-- used to live in process memory, which meant every restart (deploy, crash)
-- handed an attacker a fresh brute-force budget. These counters survive
-- restarts; rows are deleted lazily once reset_at has passed (store init,
-- plus a periodic sweep in lib/rateLimitStore.js).
CREATE TABLE IF NOT EXISTS rate_limit_counters (
  key      TEXT PRIMARY KEY,
  count    INT NOT NULL DEFAULT 0,
  reset_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rate_limit_counters_reset ON rate_limit_counters (reset_at);
