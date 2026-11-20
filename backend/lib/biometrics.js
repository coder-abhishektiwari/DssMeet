/**
 * Shared RFID card-number rules — every write path enforces the same
 * strict 10-digit format from registration through storage.
 */

const BCRYPT_RE = /^\$2[aby]\$/;
const RFID_10_DIGITS = /^\d{10}$/;
const SG400_BASE64_RE = /^[A-Za-z0-9+/]{534}==$/;

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
function hasShortPeriod(bytes) {
  for (let period = 4; period <= 32; period += 1) {
    let matches = 0;
    for (let i = period; i < bytes.length; i += 1) {
      if (bytes[i] === bytes[i - period]) matches += 1;
    }
    const total = bytes.length - period;
    if (matches * 100 >= total * 60) return true;
  }
  return false;
}

function hasRepeatedBase64Chunk(value, chunkSize = 64, minOccurrences = 3) {
  const clean = String(value || '').trim();
  if (clean.length < chunkSize * minOccurrences) return false;
  const counts = new Map();
  for (let i = 0; i <= clean.length - chunkSize; i += 1) {
    const chunk = clean.slice(i, i + chunkSize);
    const count = (counts.get(chunk) || 0) + 1;
    if (count >= minOccurrences) return true;
    counts.set(chunk, count);
  }
  return false;
}

function isValidFingerprintTemplate(capture) {
  if (capture == null || capture === '') return false;
  const value = String(capture).trim();
  if (!SG400_BASE64_RE.test(value)) return false;
  if (hasRepeatedBase64Chunk(value)) return false;
  const bytes = Buffer.from(value, 'base64');
  return bytes.length === 400 && !hasShortPeriod(bytes);
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

module.exports = {
  isValidRfidNumber,
  rfidCardNumberFor,
  biometricValidationError,
  isValidFingerprintTemplate,
  hasShortPeriod,
  hasRepeatedBase64Chunk,
  fingerprintTemplateValidationError,
  normalizeFingerprintTemplate,
  BCRYPT_RE
};
