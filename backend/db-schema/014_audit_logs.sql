-- 014: append-only audit trail for security-relevant actions.
--
-- Every privilege-sensitive mutation (login, password change/reset, kiosk
-- approve/reject/update/delete, admin create/update/delete, prisoner status
-- changes and PIN resets, setup-password rotation) writes one row here so a
-- DIG audit can answer "who did what, when, from where" from the database
-- alone. Writes go through lib/audit.js which never throws — an audit hiccup
-- must not fail the business request, and a missing table must not 500 a login.
CREATE TABLE IF NOT EXISTS audit_logs (
  id          BIGSERIAL PRIMARY KEY,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor_id    TEXT,
  actor_role  TEXT,
  action      TEXT NOT NULL,
  entity_type TEXT,
  entity_id   TEXT,
  prison_id   TEXT,
  kiosk_id    TEXT,
  ip          TEXT,
  user_agent  TEXT,
  metadata    JSONB
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON audit_logs (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_action      ON audit_logs (action);
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor       ON audit_logs (actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_prison      ON audit_logs (prison_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_entity      ON audit_logs (entity_type, entity_id);
