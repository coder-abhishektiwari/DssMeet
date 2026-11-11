const express = require('express');
const { query, pool } = require('../lib/db');
const { requireAuth, requireRole } = require('../middleware/auth');
const { sendSuccess, asyncRoute } = require('../lib/response');

const router = express.Router();

/**
 * GET /audit — paged audit trail, newest first.
 *
 * super-admin / super_admin see every jail; an admin or warden is pinned to
 * their own prison (same rule the rest of the API scopes by). Filters are
 * optional: action, actorId, entityType, entityId, from, to (ISO dates).
 */
router.get('/', requireAuth, requireRole('admin', 'warden', 'super-admin', 'super_admin'), asyncRoute(async (req, res) => {
  const limit = Math.min(Math.max(parseInt(req.query.limit, 10) || 20, 1), 100);
  const offset = Math.max(parseInt(req.query.offset, 10) || 0, 0);

  if (!pool) return sendSuccess(res, { items: [], total: 0, limit, offset });

  const role = String(req.auth?.role || '').toLowerCase().replace(/[-_]/g, '');
  const where = [];
  const params = [];

  // Jail scoping: a non-super token only ever reads its own prison's rows.
  if (role !== 'superadmin') {
    const scopePrison = req.auth?.prisonId || null;
    if (!scopePrison) return sendSuccess(res, { items: [], total: 0, limit, offset });
    params.push(scopePrison);
    where.push(`prison_id = $${params.length}`);
  }

  const addEq = (column, value) => {
    if (value == null || value === '') return;
    params.push(value);
    where.push(`${column} = $${params.length}`);
  };
  addEq('action', req.query.action);
  addEq('actor_id', req.query.actorId);
  addEq('entity_type', req.query.entityType);
  addEq('entity_id', req.query.entityId);
  addEq('prison_id', req.query.prisonId);

  const addDate = (column, value, op) => {
    if (!value) return;
    const d = new Date(value);
    if (isNaN(d.getTime())) return;
    params.push(d.toISOString());
    where.push(`${column} ${op} $${params.length}`);
  };
  addDate('created_at', req.query.from, '>=');
  addDate('created_at', req.query.to, '<=');

  const whereSql = where.length ? `WHERE ${where.join(' AND ')}` : '';

  const countRes = await query(`SELECT count(*)::int AS total FROM audit_logs ${whereSql}`, params);
  const total = countRes.rows[0] ? countRes.rows[0].total : 0;

  params.push(limit, offset);
  const { rows } = await query(
    `SELECT id, created_at, actor_id, actor_role, action, entity_type, entity_id,
            prison_id, kiosk_id, ip, user_agent, metadata
       FROM audit_logs
       ${whereSql}
      ORDER BY created_at DESC, id DESC
      LIMIT $${params.length - 1} OFFSET $${params.length}`,
    params
  );

  const items = rows.map((r) => ({
    id: Number(r.id),
    createdAt: r.created_at,
    actorId: r.actor_id,
    actorRole: r.actor_role,
    action: r.action,
    entityType: r.entity_type,
    entityId: r.entity_id,
    prisonId: r.prison_id,
    kioskId: r.kiosk_id,
    ip: r.ip,
    userAgent: r.user_agent,
    metadata: r.metadata || null,
  }));

  return sendSuccess(res, { items, total, limit, offset });
}));

module.exports = router;
