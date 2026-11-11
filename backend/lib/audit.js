/**
 * Append-only audit trail writer.
 *
 * One row in `audit_logs` per security-relevant action (see
 * db-schema/014_audit_logs.sql). Contract:
 *  - NEVER throws: an audit write problem must not fail the business request.
 *    Failures are console.warn'ed so they still show up in the container log.
 *  - Pre-auth callers (failed login, setup-password brute force) have no
 *    req.auth — actor fields simply stay NULL.
 *  - metadata is JSONB and capped so a chatty caller cannot bloat the table.
 */
const { query, pool } = require('./db');

const MAX_METADATA_CHARS = 4000;
const MAX_USER_AGENT = 300;

function safeMetadata(metadata) {
  if (metadata == null) return null;
  try {
    const raw = JSON.stringify(metadata);
    if (raw == null) return null;
    if (raw.length <= MAX_METADATA_CHARS) return raw;
    return JSON.stringify({ truncated: true, preview: raw.slice(0, MAX_METADATA_CHARS) });
  } catch (e) {
    return JSON.stringify({ unserializable: true });
  }
}

/**
 * Write one audit row. Resolves even when the write fails (or no DB is
 * configured), so callers may `await` it without a try/catch.
 *
 * entry: { actorId, actorRole, action, entityType, entityId, prisonId,
 *          kioskId, ip, userAgent, metadata }
 */
async function audit(entry) {
  try {
    if (!pool || !entry || !entry.action) return;
    await query(
      `INSERT INTO audit_logs
         (actor_id, actor_role, action, entity_type, entity_id, prison_id, kiosk_id, ip, user_agent, metadata)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
      [
        entry.actorId || null,
        entry.actorRole || null,
        String(entry.action).slice(0, 100),
        entry.entityType || null,
        entry.entityId != null ? String(entry.entityId).slice(0, 120) : null,
        entry.prisonId || null,
        entry.kioskId || null,
        entry.ip || null,
        entry.userAgent ? String(entry.userAgent).slice(0, MAX_USER_AGENT) : null,
        safeMetadata(entry.metadata),
      ]
    );
  } catch (err) {
    console.warn(`[audit] write failed (${entry && entry.action}): ${err.message}`);
  }
}

/**
 * Convenience wrapper that fills actor/ip/user-agent from the request.
 *
 *   await auditFromReq(req, 'kiosk.approved', {
 *     entityType: 'kiosk', entityId: requestId, prisonId: kiosk.prisonId,
 *     metadata: { reason: 'ok' }
 *   });
 *
 * `extra.actorId` overrides the token subject (e.g. the target of a failed
 * credential check, recorded as the attempted identifier).
 */
async function auditFromReq(req, action, extra = {}) {
  const auth = (req && req.auth) || {};
  let userAgent = null;
  try { userAgent = (req && req.get && req.get('user-agent')) || null; } catch (e) { userAgent = null; }
  return audit({
    actorId: extra.actorId !== undefined ? extra.actorId : (auth.sub || null),
    actorRole: extra.actorRole !== undefined ? extra.actorRole : (auth.role || null),
    action,
    entityType: extra.entityType || null,
    entityId: extra.entityId,
    prisonId: extra.prisonId !== undefined ? extra.prisonId : (auth.prisonId || null),
    kioskId: extra.kioskId !== undefined ? extra.kioskId : (auth.kioskId || null),
    ip: (req && req.ip) || null,
    userAgent,
    metadata: extra.metadata,
  });
}

module.exports = { audit, auditFromReq };
