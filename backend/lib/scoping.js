const { readDb } = require('./db');

const normRole = (r) => String(r || '').toLowerCase().replace(/[-_]/g, '');

// The company operator legitimately sees every prison.
function isSuperAdmin(req) {
  return normRole(req.auth?.role) === 'superadmin';
}

// Roles that administer exactly one prison. Their token must carry a prison;
// a jail-managed account with no prison attached gets "no access" rather than
// falling through to "every jail".
function isJailManaged(req) {
  const role = normRole(req.auth?.role);
  return role === 'warden' || role === 'admin' || role === 'kioskadmin';
}

// An inmate session reaches only its own records, whatever table they live in.
function inOwnerScope(req, record) {
  if (normRole(req.auth?.role) !== 'inmate') return true;
  const mine = req.auth?.inmateId || null;
  if (!mine || !record) return true;
  const owner = record.inmateId || null;
  if (!owner) return true;
  return owner === mine || owner === `INM-${mine}` || `INM-${owner}` === mine;
}

function jailScopeOf(req) {
  return req.auth?.prisonId || req.auth?.jailId || null;
}

function inJailScope(req, record) {
  if (isSuperAdmin(req)) return true;
  const jailId = jailScopeOf(req);
  if (!jailId) return !isJailManaged(req);
  return !!record && (record.prisonId === jailId || record.facility === jailId || record.jailId === jailId);
}

function scopeFilter(req) {
  if (isSuperAdmin(req)) return () => true;
  const jailId = jailScopeOf(req);
  if (!jailId) return () => !isJailManaged(req);
  return (x) => x.prisonId === jailId || x.facility === jailId || x.jailId === jailId;
}

function kioskScopeOf(req) {
  return req.auth?.kioskId || null;
}

function assignedKioskIdsOf(record) {
  if (!record) return [];
  if (Array.isArray(record.assignedKioskIds) && record.assignedKioskIds.length) {
    return [...new Set(record.assignedKioskIds.map((id) => String(id)).filter(Boolean))];
  }
  if (record.assignedKioskId) return [String(record.assignedKioskId)];
  return [];
}

function isAllKiosks(record) {
  return !!(record && record.allKiosks === true);
}

function isInmateAssignedToKiosk(record, kioskId) {
  if (!kioskId) return true;
  if (!record) return false;
  if (isAllKiosks(record)) return true;
  const ids = assignedKioskIdsOf(record);
  return ids.length === 0 || ids.includes(String(kioskId));
}

function normalizeKioskAssignment(input, fallbackKioskId) {
  if (input && input.allKiosks === true) {
    return { allKiosks: true, assignedKioskIds: [], assignedKioskId: null };
  }
  let ids = [];
  if (input && Array.isArray(input.assignedKioskIds)) {
    ids = input.assignedKioskIds.map((id) => String(id || '').trim()).filter(Boolean);
  } else if (input && input.assignedKioskId) {
    ids = [String(input.assignedKioskId).trim()];
  }
  ids = [...new Set(ids)];
  if (!ids.length && fallbackKioskId) ids = [String(fallbackKioskId)];
  return {
    allKiosks: false,
    assignedKioskIds: ids,
    assignedKioskId: ids.length ? ids[0] : null
  };
}

async function validateKioskAssignments(ids, jailId) {
  const clean = [...new Set((ids || []).map((id) => String(id || '').trim()).filter(Boolean))];
  if (!clean.length || !jailId) return { ok: true };
  try {
    const kiosks = await readDb('kiosks.json');
    for (const id of clean) {
      const kiosk = kiosks.find((k) => k.kioskId === id);
      if (!kiosk) return { ok: false, code: 'NOT_FOUND', message: `Assigned kiosk '${id}' does not exist` };
      if (kiosk.prisonId && kiosk.prisonId !== jailId) {
        return { ok: false, code: 'FORBIDDEN', message: 'Cannot assign a kiosk from another prison' };
      }
    }
    return { ok: true };
  } catch (err) {
    return { ok: true };
  }
}

function inKioskScope(req, record) {
  const kioskId = kioskScopeOf(req);
  if (!kioskId) return true;
  return !!record && (isInmateAssignedToKiosk(record, kioskId) || record.kioskId === kioskId);
}

function inAdminScope(req, record) {
  if (isSuperAdmin(req)) return true;
  if (isJailManaged(req) && !jailScopeOf(req)) return false;
  return inJailScope(req, record) && inKioskScope(req, record) && inOwnerScope(req, record);
}

function adminScopeFilter(req) {
  return (x) => inAdminScope(req, x);
}

async function inScopeOf(req, record) {
  if (isSuperAdmin(req)) return true;
  const kioskId = kioskScopeOf(req);
  const jailId = jailScopeOf(req);
  if (isJailManaged(req) && !jailId) return false;
  if (!kioskId && !jailId) return inOwnerScope(req, record);
  if (!record) return false;

  const hasKioskAssignment = isAllKiosks(record) || assignedKioskIdsOf(record).length > 0;
  let recKiosk = record.kioskId || assignedKioskIdsOf(record)[0] || null;
  let recJail = record.prisonId || record.jailId || record.facility || null;
  let refInmateId = record.inmateId || null;

  if (record.walletId) {
    const wallets = await readDb('wallets.json');
    const wallet = wallets.find((w) => w.walletId === record.walletId);
    if (wallet) refInmateId = wallet.inmateId;
  }

  if (refInmateId) {
    const inmates = await readDb('inmates.json');
    const owner = inmates.find((i) => i.inmateId === refInmateId) ||
                  inmates.find((i) => i.inmateId === `INM-${refInmateId}`);
    if (owner) {
      recKiosk = recKiosk || assignedKioskIdsOf(owner)[0] || owner.kioskId;
      recJail = recJail || owner.prisonId;
    }
  }

  if (!recJail && recKiosk) {
    const kiosks = await readDb('kiosks.json');
    const k = kiosks.find((x) => x.kioskId === recKiosk);
    if (k) recJail = k.prisonId;
  }

  if (kioskId && hasKioskAssignment && !isInmateAssignedToKiosk(record, kioskId)) return false;
  if (kioskId && !hasKioskAssignment && recKiosk && recKiosk !== kioskId) return false;
  if (jailId && recJail && recJail !== jailId) return false;
  return inOwnerScope(req, record);
}

async function scopeList(req, records) {
  const out = [];
  for (const r of records) if (await inScopeOf(req, r)) out.push(r);
  return out;
}

module.exports = {
  jailScopeOf, inJailScope, scopeFilter,
  kioskScopeOf, assignedKioskIdsOf, isAllKiosks, isInmateAssignedToKiosk,
  normalizeKioskAssignment, validateKioskAssignments,
  inKioskScope, inAdminScope, adminScopeFilter,
  inScopeOf, scopeList,
  isSuperAdmin, isJailManaged, inOwnerScope
};
