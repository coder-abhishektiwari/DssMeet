const express = require('express');
const crypto = require('crypto');
const { v4: uuidv4 } = require('uuid');
const { readDb, updateDb } = require('../lib/db');
const { requireAuth, requireRole } = require('../middleware/auth');
const { sendSuccess, sendError, asyncRoute } = require('../lib/response');
const { auditFromReq } = require('../lib/audit');
const { APPS, PLATFORMS, publicRelease, latestRelease } = require('../lib/appReleases');

const router = express.Router();

/**
 * CI publisher token.
 *
 * The GitHub Actions build publishes its own release to this registry, so the
 * version only has to be written ONCE (in the build). The token lives in the
 * repo secret RELEASE_PUBLISH_TOKEN and on the backend as the env var of the
 * same name. When it is not configured, machine publishing is simply off.
 */
function ciPublisher(req, res, next) {
  const configured = process.env.RELEASE_PUBLISH_TOKEN || '';
  if (!configured) return next();
  const provided = req.get('x-release-token') || '';
  if (!provided) return next();
  const a = Buffer.from(provided);
  const b = Buffer.from(configured);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return next();
  // Treated as a super-admin publisher — the same role the human publishers
  // need, no more.
  req.auth = { sub: 'ci-publisher', role: 'super_admin', via: 'release-token' };
  return next();
}

function createAppReleasesRouter(broadcastEvent) {
  // ==================== LATEST (device side — no auth) ====================
  // The kiosk checks this before anyone is logged in (a mandatory update must
  // be able to block a device that never signed in). The payload is public
  // build metadata only: version, whether it is mandatory, where to download
  // it and the sha256 to verify the download with.
  router.get('/latest', asyncRoute(async (req, res) => {
    const app = String(req.query.app || '').toLowerCase();
    const platform = String(req.query.platform || '').toLowerCase();
    if (!APPS.includes(app)) {
      return sendError(res, 'INVALID_REQUEST', `app must be one of: ${APPS.join(', ')}`, 400);
    }
    if (platform && !PLATFORMS.includes(platform)) {
      return sendError(res, 'INVALID_REQUEST', `platform must be one of: ${PLATFORMS.join(', ')}`, 400);
    }

    const latest = await latestRelease(app, platform);
    return sendSuccess(res, {
      app,
      platform: platform || null,
      update: latest,
    });
  }));

  // ==================== LIST (warden / admin) ====================
  router.get('/', requireAuth,
    requireRole('admin', 'warden', 'vendor', 'super-admin', 'super_admin'),
    asyncRoute(async (req, res) => {
      const app = String(req.query.app || '').toLowerCase();
      const platform = String(req.query.platform || '').toLowerCase();
      let releases = await readDb('app-releases.json');
      if (app) releases = releases.filter((r) => r.app === app);
      if (platform) releases = releases.filter((r) => r.platform === platform);
      releases = [...releases].sort((a, b) => {
        const at = Date.parse(a.releasedAt || a.createdAt || '') || 0;
        const bt = Date.parse(b.releasedAt || b.createdAt || '') || 0;
        return bt - at;
      });
      return sendSuccess(res, releases.map(publicRelease));
    }));

  // ==================== PUBLISH (warden / admin) ====================
  // Allowlisted fields only — a client must never be able to plant an
  // arbitrary document in this collection.
  router.post('/', ciPublisher, requireAuth,
    requireRole('admin', 'warden', 'vendor', 'super-admin', 'super_admin'),
    asyncRoute(async (req, res) => {
      const body = (req.body && typeof req.body === 'object' && !Array.isArray(req.body)) ? req.body : {};
      const app = String(body.app || '').toLowerCase();
      const platform = String(body.platform || '').toLowerCase();
      if (!APPS.includes(app)) {
        return sendError(res, 'INVALID_REQUEST', `app must be one of: ${APPS.join(', ')}`, 400);
      }
      if (!PLATFORMS.includes(platform)) {
        return sendError(res, 'INVALID_REQUEST', `platform must be one of: ${PLATFORMS.join(', ')}`, 400);
      }
      const version = String(body.version || '').trim();
      if (!version) return sendError(res, 'INVALID_REQUEST', 'version is required', 400);
      const downloadUrl = String(body.downloadUrl || '').trim();
      if (!/^https:\/\//i.test(downloadUrl)) {
        return sendError(res, 'INVALID_REQUEST', 'downloadUrl must be an https URL', 400);
      }
      let versionCode = null;
      if (body.versionCode !== undefined && body.versionCode !== null && body.versionCode !== '') {
        versionCode = Number(body.versionCode);
        if (!Number.isInteger(versionCode) || versionCode < 0) {
          return sendError(res, 'INVALID_REQUEST', 'versionCode must be a non-negative integer', 400);
        }
      }
      const sha256 = body.sha256 ? String(body.sha256).trim().toLowerCase() : null;
      if (sha256 && !/^[a-f0-9]{64}$/.test(sha256)) {
        return sendError(res, 'INVALID_REQUEST', 'sha256 must be 64 hex characters', 400);
      }
      const sizeBytes = body.sizeBytes !== undefined && body.sizeBytes !== null && body.sizeBytes !== ''
        ? Number(body.sizeBytes) : null;
      if (sizeBytes !== null && (!Number.isFinite(sizeBytes) || sizeBytes < 0)) {
        return sendError(res, 'INVALID_REQUEST', 'sizeBytes must be a non-negative number', 400);
      }

      const record = {
        releaseId: `REL-${uuidv4().substring(0, 8).toUpperCase()}`,
        app,
        platform,
        version,
        versionCode,
        mandatory: body.mandatory === true,
        downloadUrl,
        sha256,
        sizeBytes,
        notes: body.notes ? String(body.notes).slice(0, 500) : null,
        releasedAt: new Date().toISOString(),
        createdAt: new Date().toISOString(),
      };

      const created = await updateDb('app-releases.json', (rows) => {
        // One live release per app+platform: publishing a new one supersedes
        // the old (it stays in the list for the audit trail, but the clients
        // only ever read the latest).
        const kept = rows.map((r) => (
          r.app === app && r.platform === platform ? { ...r, mandatory: false } : r
        ));
        return { data: [...kept, record], result: record };
      });

      await auditFromReq(req, 'app_release.published', {
        entityType: 'app_release', entityId: created.releaseId,
        metadata: {
          app, platform, version, versionCode,
          mandatory: created.mandatory, sha256: created.sha256,
        }
      });
      broadcastEvent('app-release-published', publicRelease(created));
      return sendSuccess(res, publicRelease(created), 201);
    }));

  // ==================== TOGGLE MANDATORY (warden / admin) ====================
  router.patch('/:releaseId', requireAuth,
    requireRole('admin', 'warden', 'vendor', 'super-admin', 'super_admin'),
    asyncRoute(async (req, res) => {
      const body = (req.body && typeof req.body === 'object' && !Array.isArray(req.body)) ? req.body : {};
      const existing = (await readDb('app-releases.json')).find((r) => r.releaseId === req.params.releaseId);
      if (!existing) return sendError(res, 'NOT_FOUND', 'Release not found', 404);
      if (body.mandatory !== undefined && typeof body.mandatory !== 'boolean') {
        return sendError(res, 'INVALID_REQUEST', 'mandatory must be a boolean', 400);
      }
      if (body.notes !== undefined && body.notes !== null && typeof body.notes !== 'string') {
        return sendError(res, 'INVALID_REQUEST', 'notes must be a string', 400);
      }

      const updated = await updateDb('app-releases.json', (rows) => {
        const idx = rows.findIndex((r) => r.releaseId === req.params.releaseId);
        if (idx === -1) return { data: rows, result: null };
        const next = { ...rows[idx] };
        if (body.mandatory !== undefined) next.mandatory = body.mandatory;
        if (body.notes !== undefined) next.notes = body.notes ? String(body.notes).slice(0, 500) : null;
        rows[idx] = next;
        return { data: rows, result: next };
      });
      if (!updated) return sendError(res, 'NOT_FOUND', 'Release not found', 404);

      await auditFromReq(req, 'app_release.updated', {
        entityType: 'app_release', entityId: updated.releaseId,
        metadata: {
          app: updated.app, platform: updated.platform, version: updated.version,
          mandatory: updated.mandatory,
        }
      });
      broadcastEvent('app-release-published', publicRelease(updated));
      return sendSuccess(res, publicRelease(updated));
    }));

  return router;
}

module.exports = createAppReleasesRouter;
