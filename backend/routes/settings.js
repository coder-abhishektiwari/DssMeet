const express = require('express');
const { readDb, updateDb } = require('../lib/db');
const { requireAuth, requireRole } = require('../middleware/auth');
const { sendSuccess, sendError, asyncRoute, deepMerge } = require('../lib/response');
const { auditFromReq } = require('../lib/audit');

const router = express.Router();

// DASHBOARD auto-logout — the warden's own desktop session, NOT the kiosk.
// The kiosk's inactivity timeout is a fixed local value in the app itself.
// The server only STORES this policy; the timer runs in the browser.
const AUTO_LOGOUT_TIMEOUTS = [2, 5, 10];
const AUTO_LOGOUT_DEFAULT = { enabled: false, timeoutMinutes: 5 };

function createSettingsRouter(broadcastEvent) {
  // Settings
  // NOTE: singletons are stored as 1-element arrays after any PATCH
  // ({ data: [result] }) — always unwrap so clients get the object itself.
  router.get('/', requireAuth, asyncRoute(async (req, res) => {
    const raw = await readDb('settings.json');
    return sendSuccess(res, Array.isArray(raw) ? (raw[0] || {}) : raw);
  }));

  // Call limits & facility settings — VENDOR (super admin) ONLY.
  // Wardens may read (chips on Live Calls / Call Logs) but never change them.
  router.patch('/', requireAuth, requireRole('vendor', 'super-admin', 'super_admin'), asyncRoute(async (req, res) => {
    const merged = await updateDb('settings.json', (all) => {
      const patch = { ...req.body };
      const base = all.length ? { ...all[0] } : {};
      const result = deepMerge(base, patch);
      return { data: [result], result };
    });
    await auditFromReq(req, 'settings.updated', {
      entityType: 'settings', entityId: 'settings',
      metadata: { keys: Array.isArray(req.body) ? [] : Object.keys(req.body || {}).slice(0, 30) }
    });
    broadcastEvent('settings-updated', merged);
    return sendSuccess(res, merged);
  }));

  // ==================== DASHBOARD AUTO-LOGOUT (INACTIVITY) ====================
  // Scoped endpoint on purpose: the generic PATCH /settings above is vendor-only
  // and merges an arbitrary body, while a warden must be able to set the
  // dashboard's idle timeout. Only these two keys are writable here — every
  // other key in the settings document stays untouched.
  // The timer itself is LOCAL to the browser: this endpoint only stores the
  // value, it never polls or counts anything.
  router.patch('/auto-logout', requireAuth,
    requireRole('warden', 'admin', 'vendor', 'super-admin', 'super_admin'),
    asyncRoute(async (req, res) => {
      const body = (req.body && typeof req.body === 'object' && !Array.isArray(req.body)) ? req.body : {};
      const hasEnabled = body.enabled !== undefined;
      const hasTimeout = body.timeoutMinutes !== undefined;

      if (!hasEnabled && !hasTimeout) {
        return sendError(res, 'INVALID_REQUEST', 'enabled or timeoutMinutes is required', 400);
      }
      let enabled = AUTO_LOGOUT_DEFAULT.enabled;
      if (hasEnabled) {
        if (typeof body.enabled !== 'boolean') {
          return sendError(res, 'INVALID_REQUEST', 'enabled must be a boolean', 400);
        }
        enabled = body.enabled;
      }
      let timeoutMinutes = AUTO_LOGOUT_DEFAULT.timeoutMinutes;
      if (hasTimeout) {
        timeoutMinutes = Number(body.timeoutMinutes);
        if (!AUTO_LOGOUT_TIMEOUTS.includes(timeoutMinutes)) {
          return sendError(
            res, 'INVALID_REQUEST',
            `timeoutMinutes must be one of: ${AUTO_LOGOUT_TIMEOUTS.join(', ')}`, 400
          );
        }
      }

      const merged = await updateDb('settings.json', (all) => {
        // The singleton is stored as a 1-element array after any PATCH.
        const base = Array.isArray(all) ? { ...(all[0] || {}) } : { ...(all || {}) };
        const current = base.securitySettings?.autoLogout || { ...AUTO_LOGOUT_DEFAULT };
        const next = {
          enabled: hasEnabled ? enabled : (current.enabled ?? AUTO_LOGOUT_DEFAULT.enabled),
          timeoutMinutes: hasTimeout ? timeoutMinutes : (current.timeoutMinutes ?? AUTO_LOGOUT_DEFAULT.timeoutMinutes),
        };
        const result = deepMerge(base, { securitySettings: { autoLogout: next } });
        return { data: [result], result };
      });

      await auditFromReq(req, 'settings.auto_logout_updated', {
        entityType: 'settings', entityId: 'settings.autoLogout',
        metadata: {
          enabled: merged?.securitySettings?.autoLogout?.enabled ?? null,
          timeoutMinutes: merged?.securitySettings?.autoLogout?.timeoutMinutes ?? null,
        }
      });
      broadcastEvent('settings-updated', merged);
      return sendSuccess(res, merged);
    }));

  return router;
}

module.exports = createSettingsRouter;
