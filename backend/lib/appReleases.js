const { readDb } = require('./db');

// Apps that can have a published release, and the platforms each ships on.
const APPS = ['kiosk', 'warden'];
const PLATFORMS = ['android', 'windows', 'linux'];

/**
 * The release metadata a client is allowed to see. No secrets live in this
 * table, but the list endpoint still stays behind auth.
 */
function publicRelease(r) {
  if (!r) return null;
  return {
    releaseId: r.releaseId,
    app: r.app,
    platform: r.platform,
    version: r.version,
    versionCode: r.versionCode ?? null,
    mandatory: !!r.mandatory,
    downloadUrl: r.downloadUrl,
    sha256: r.sha256 || null,
    sizeBytes: r.sizeBytes ?? null,
    notes: r.notes || null,
    releasedAt: r.releasedAt || r.createdAt || null,
  };
}

/**
 * The newest release for an app+platform, or null.
 * versionCode wins when both rows have one (it is monotonic); otherwise the
 * newest released_at wins.
 */
async function latestRelease(app, platform) {
  const releases = await readDb('app-releases.json');
  const candidates = releases
    .filter((r) => r.app === app && (!platform || r.platform === platform))
    .sort((a, b) => {
      const ac = Number(a.versionCode);
      const bc = Number(b.versionCode);
      if (Number.isFinite(ac) && Number.isFinite(bc) && ac !== bc) return bc - ac;
      const at = Date.parse(a.releasedAt || a.createdAt || '') || 0;
      const bt = Date.parse(b.releasedAt || b.createdAt || '') || 0;
      return bt - at;
    });
  return publicRelease(candidates[0]);
}

module.exports = { APPS, PLATFORMS, publicRelease, latestRelease };
