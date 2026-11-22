/**
 * Shared RFID card-number rules — every write path enforces the same
 * strict 10-digit format from registration through storage.
 */

const BCRYPT_RE = /^\$2[aby]\$/;
const RFID_10_DIGITS = /^\d{10}$/;
const SG400_BASE64_RE = /^[A-Za-z0-9+/]{534}==$/;
const MAX_FINGERPRINT_TEMPLATES = 3;

/** Strict format check: exactly 10 digits, nothing else. */
function isValidRfidNumber(value) {
  return RFID_10_DIGITS.test(value == null ? '' : String(value));
}

/**
 * Display copy of the RFID card number for inmate details. Registration keeps
 * a plain rfidCardNumber next to the bcrypt-hashed rfidToken; legacy rows hold
 * the raw value in rfidToken itself. Hashed-only rows return null.
 */
function rfidCardNumberFor(i) {
  const bio = i.biometricData || {};
  if (!bio.rfidRegistered) return null;
  if (bio.rfidCardNumber) return String(bio.rfidCardNumber);
  const token = bio.rfidToken;
  if (token && !BCRYPT_RE.test(String(token))) return String(token);
  return null;
}

/**
 * Validates inline biometricData before it is hashed/persisted.
 * Returns an error message, or null when the payload is acceptable.
 * Already-bcrypt tokens (admin edits round-tripping stored data) pass
 * through untouched so legacy rows keep saving.
 */
function biometricValidationError(biometricData) {
  const token = biometricData && biometricData.rfidToken;
  if (!token) return null;
  if (BCRYPT_RE.test(String(token))) return null;
  if (!isValidRfidNumber(token)) return 'RFID card number must be exactly 10 digits';
  return null;
}

/**
 * Fingerprint templates are NOT hashed at rest. The kiosk matches a fresh
 * capture against the stored template with the SecuGen matcher (routes/auth.js
 * /fingerprint-candidates -> device-side 1:N), and a bcrypt digest is a
 * one-way function that can never match a new capture. All we can do is make
 * sure the value we persist is a sane base64 template.
 *
 * Returns an error message, or null when the payload is acceptable.
 */
function isValidFingerprintTemplate(capture) {
  if (capture == null || capture === '') return false;
  const value = String(capture).trim();
  if (!SG400_BASE64_RE.test(value)) return false;
  const bytes = Buffer.from(value, 'base64');
  return bytes.length === 400 && new Set(bytes).size > 1;
}

function fingerprintTemplateValidationError(capture) {
  if (capture == null || capture === '') return null;
  const value = String(capture).trim();
  if (BCRYPT_RE.test(value)) return 'Stored fingerprint template is stale and must be re-captured';
  if (!isValidFingerprintTemplate(value)) {
    return 'Fingerprint template must be a 536-character base64 SG400 template';
  }
  return null;
}

/** Normalized template for storage, or null when there is nothing to store. */
function normalizeFingerprintTemplate(capture) {
  if (capture == null) return null;
  const value = String(capture).trim();
  return value ? value : null;
}

function fingerprintEntries(inmateOrBio, fallbackInmateId) {
  const source = inmateOrBio && inmateOrBio.biometricData ? inmateOrBio.biometricData : inmateOrBio;
  if (!source) return [];
  const inmateId = fallbackInmateId || (inmateOrBio && (inmateOrBio.inmateId || inmateOrBio.id || inmateOrBio.adminId)) || '';
  if (Array.isArray(source.fingerprintTemplates) && source.fingerprintTemplates.length) {
    return source.fingerprintTemplates.map((entry, index) => {
      if (typeof entry === 'string') {
        return { biometricId: `BIO-${inmateId}-FGP-${index + 1}`, template: entry };
      }
      return {
        biometricId: entry && entry.biometricId ? entry.biometricId : `BIO-${inmateId}-FGP-${index + 1}`,
        template: entry && entry.template ? entry.template : ''
      };
    }).filter((entry) => entry.template).slice(0, MAX_FINGERPRINT_TEMPLATES);
  }
  if (source.fingerprintTemplate) {
    return [{ biometricId: `BIO-${inmateId}-FGP`, template: source.fingerprintTemplate }];
  }
  return [];
}

function fingerprintTemplateStrings(inmate) {
  return fingerprintEntries(inmate).map((entry) => entry.template);
}

function hasFingerprintTemplate(inmate) {
  return fingerprintEntries(inmate).some((entry) => isValidFingerprintTemplate(entry.template));
}

function fingerprintCapacityError(inmateOrBio) {
  const source = inmateOrBio && inmateOrBio.biometricData ? inmateOrBio.biometricData : inmateOrBio;
  const count = source && Array.isArray(source.fingerprintTemplates) ? source.fingerprintTemplates.length : 0;
  return count >= MAX_FINGERPRINT_TEMPLATES ? `Maximum ${MAX_FINGERPRINT_TEMPLATES} fingerprint templates are allowed` : null;
}

function fingerprintTemplatesValidationError(inmateOrBio, fallbackInmateId) {
  const capacityError = fingerprintCapacityError(inmateOrBio);
  if (capacityError) return capacityError;
  for (const entry of fingerprintEntries(inmateOrBio, fallbackInmateId)) {
    const error = fingerprintTemplateValidationError(entry.template);
    if (error) return error;
  }
  return null;
}

function normalizeFingerprintStorage(biometricData, inmateId) {
  const out = { ...(biometricData || {}) };
  const source = Array.isArray(out.fingerprintTemplates) && out.fingerprintTemplates.length
    ? out.fingerprintTemplates
    : (out.fingerprintTemplate ? [out.fingerprintTemplate] : []);
  out.fingerprintTemplates = source.slice(0, MAX_FINGERPRINT_TEMPLATES).map((entry, index) => {
    if (typeof entry === 'string') {
      return {
        biometricId: `BIO-${inmateId}-FGP-${index + 1}`,
        template: normalizeFingerprintTemplate(entry),
        registeredAt: out.lastBiometricUpdate || null,
        label: `Fingerprint ${index + 1}`
      };
    }
    const item = entry || {};
    return {
      ...item,
      biometricId: item.biometricId || `BIO-${inmateId}-FGP-${index + 1}`,
      template: normalizeFingerprintTemplate(item.template),
      label: item.label || `Fingerprint ${index + 1}`
    };
  }).filter((entry) => entry.template);
  out.fingerprintTemplate = out.fingerprintTemplates.length ? out.fingerprintTemplates[0].template : null;
  out.fingerprintRegistered = out.fingerprintTemplates.length > 0;
  return out;
}

function addFingerprintTemplate(inmate, capture) {
  const inmateId = inmate.inmateId || inmate.id || inmate.adminId;
  const biometricData = normalizeFingerprintStorage(inmate.biometricData, inmateId);
  const index = biometricData.fingerprintTemplates.length + 1;
  const registeredAt = new Date().toISOString();
  const entry = {
    biometricId: `BIO-${inmateId}-FGP-${index}`,
    template: normalizeFingerprintTemplate(capture),
    registeredAt,
    label: `Fingerprint ${index}`
  };
  biometricData.fingerprintTemplates = [...biometricData.fingerprintTemplates, entry];
  biometricData.fingerprintTemplate = biometricData.fingerprintTemplates[0].template;
  biometricData.fingerprintRegistered = true;
  biometricData.lastBiometricUpdate = registeredAt;
  return {
    biometricData,
    record: {
      biometricId: entry.biometricId,
      prisonerId: inmateId,
      type: 'fingerprint',
      status: 'registered',
      registeredAt,
      label: entry.label
    }
  };
}

function removeFingerprintTemplate(inmate, biometricId) {
  const inmateId = inmate.inmateId || inmate.id || inmate.adminId;
  const biometricData = normalizeFingerprintStorage(inmate.biometricData, inmateId);
  const rawId = String(biometricId || '');
  const target = /-FGP$/i.test(rawId) ? `${rawId}-1` : rawId;
  const removed = biometricData.fingerprintTemplates.find((entry) => entry.biometricId === target);
  biometricData.fingerprintTemplates = biometricData.fingerprintTemplates
    .filter((entry) => entry.biometricId !== target)
    .map((entry, index) => ({ ...entry, label: `Fingerprint ${index + 1}` }));
  biometricData.fingerprintTemplate = biometricData.fingerprintTemplates.length ? biometricData.fingerprintTemplates[0].template : null;
  biometricData.fingerprintRegistered = biometricData.fingerprintTemplates.length > 0;
  biometricData.lastBiometricUpdate = new Date().toISOString();
  return { biometricData, removed };
}

function biometricRecordsFor(inmateId, biometricData, registeredAt) {
  const bio = biometricData || {};
  const fingerprints = fingerprintEntries(bio, inmateId).map((entry, index) => ({
    biometricId: entry.biometricId || `BIO-${inmateId}-FGP-${index + 1}`,
    prisonerId: inmateId,
    type: 'fingerprint',
    status: 'registered',
    registeredAt,
    label: entry.label || `Fingerprint ${index + 1}`
  }));
  const rfid = bio.rfidRegistered ? [{
    biometricId: `BIO-${inmateId}-RFID`,
    prisonerId: inmateId,
    type: 'rfid',
    status: 'registered',
    registeredAt
  }] : [];
  return [...fingerprints, ...rfid];
}

module.exports = {
  isValidRfidNumber,
  rfidCardNumberFor,
  biometricValidationError,
  isValidFingerprintTemplate,
  fingerprintTemplateValidationError,
  normalizeFingerprintTemplate,
  fingerprintEntries,
  fingerprintTemplateStrings,
  hasFingerprintTemplate,
  fingerprintCapacityError,
  fingerprintTemplatesValidationError,
  normalizeFingerprintStorage,
  addFingerprintTemplate,
  removeFingerprintTemplate,
  biometricRecordsFor,
  MAX_FINGERPRINT_TEMPLATES,
  BCRYPT_RE
};
