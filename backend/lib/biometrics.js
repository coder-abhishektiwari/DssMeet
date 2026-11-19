/**
 * Shared RFID card-number rules — every write path enforces the same
 * strict 10-digit format from registration through storage.
 */

const BCRYPT_RE = /^\$2[aby]\$/;
const RFID_10_DIGITS = /^\d{10}$/;
// A SecuGen template is base64 (SG400 is a fixed 400 bytes -> 536 chars,
// ANSI378/ISO19794-2 are variable but always well under 4 KB once encoded).
const BASE64_RE = /^[A-Za-z0-9+/=_-]+$/;
const FINGERPRINT_MIN_LEN = 16;
const FINGERPRINT_MAX_LEN = 4096;

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
function fingerprintTemplateValidationError(capture) {
  if (capture == null || capture === '') return null;
  const value = String(capture).trim();
  if (value.length < FINGERPRINT_MIN_LEN || value.length > FINGERPRINT_MAX_LEN) {
    return 'Fingerprint template must be a base64 template between 16 and 4096 characters';
  }
  // A legacy bcrypt digest can never match a live capture — accepting it would
  // silently enroll an inmate whose fingerprint login can only ever fail.
  if (BCRYPT_RE.test(value)) return 'Stored fingerprint template is stale and must be re-captured';
  if (!BASE64_RE.test(value)) return 'Fingerprint template must be base64';
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
  fingerprintTemplateValidationError,
  normalizeFingerprintTemplate,
  BCRYPT_RE
};
