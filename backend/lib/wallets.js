const { readDb, updateDb } = require('./db');

/**
 * Wallet provisioning.
 *
 * An inmate without a wallet row is a silent money leak: the call-charge path
 * in routes/calls.js resolves a wallet, finds nothing and skips the debit, so
 * the inmate talks for free. Every code path that can produce or encounter an
 * inmate goes through these helpers instead of hand-rolling the lookup.
 */

/**
 * Canonical wallet shape — mirrors the row routes/wallets.js creates on first
 * recharge, so list/summary code sees one shape no matter which path created it.
 */
function newWalletRecord(inmateId, walletId) {
  const now = new Date().toISOString();
  return {
    walletId: walletId || `WAL-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    inmateId,
    balance: 0,
    currency: 'INR',
    status: 'active',
    totalSpent: 0,
    totalRecharged: 0,
    remainingMinutes: 0,
    createdAt: now,
    updatedAt: now,
  };
}

/**
 * Resolve an inmate's wallet using the same precedence everywhere:
 * explicit walletId -> inmateId -> the legacy `INM-` prefixed inmateId.
 */
function pickWallet(wallets, inmateId, walletId) {
  return (
    wallets.find((w) => walletId && w.walletId === walletId) ||
    wallets.find((w) => w.inmateId === inmateId) ||
    wallets.find((w) => w.inmateId === `INM-${inmateId}`) ||
    null
  );
}

/**
 * Idempotent: return the wallet for `inmateId`, creating it (balance 0) if it
 * does not exist yet. Safe to call concurrently — the inner check runs inside
 * the wallets.json mutex.
 *
 * @param {string} inmateId
 * @param {string} [walletId] Pre-allocated id from the inmate record, if any.
 * @returns {Promise<object|null>}
 */
async function ensureWallet(inmateId, walletId) {
  if (!inmateId) return null;

  const existing = pickWallet(await readDb('wallets.json'), inmateId, walletId);
  if (existing) return existing;

  const record = newWalletRecord(inmateId, walletId);
  const created = await updateDb('wallets.json', (all) => {
    // Re-check under the mutex: a parallel ensure may have won.
    const again = pickWallet(all, inmateId, walletId);
    if (again) return { data: all, result: again };
    all.push(record);
    return { data: all, result: record };
  });
  // updateDb hands back `{data, result}` when there is no pool — only trust a
  // real wallet row.
  return created && created.walletId ? created : record;
}

/**
 * One-time welcome credit granted the moment an inmate account is created so
 * the family can place the first call without a recharge. The ledger row is
 * the source of truth (routes/wallets.js derives balance from it); the
 * wallets.balance bump is only the cache the charge path reads.
 *
 * Idempotent on `orderId = WELCOME-<inmateId>`: a retried create handler or
 * a parallel ensure can never double-credit.
 *
 * @param {string} inmateId
 * @param {string} [walletId] Pre-allocated id from the inmate record, if any.
 * @param {number} [amountPaise] Defaults to ₹5 (500 paise).
 * @returns {Promise<object|null>} the wallet row, or null on failure.
 */
const WELCOME_BONUS_PAISE = 500; // ₹5

async function creditWelcomeBonus(inmateId, walletId, amountPaise = WELCOME_BONUS_PAISE) {
  if (!inmateId || !(amountPaise > 0)) return null;

  const wallet = await ensureWallet(inmateId, walletId);
  if (!wallet) return null;

  const orderId = `WELCOME-${inmateId}`;
  const now = new Date().toISOString();

  // Ledger-first, same crash model as lib/wallet-credit.js: insert the recharge
  // row under the transactions mutex, then bump the cache guarded by the txn id.
  const ledgerOut = await updateDb('transactions.json', (all) => {
    const existing = all.find((t) => t.orderId === orderId);
    if (existing) return { data: all, result: { inserted: false, transaction: existing } };
    const transaction = {
      transactionId: `TXN-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      walletId: wallet.walletId || null,
      inmateId,
      type: 'recharge',
      status: 'completed',
      amount: amountPaise,
      grossAmount: amountPaise,
      fee: 0,
      tax: 0,
      gateway: 'welcome',
      orderId,
      description: 'Welcome balance on account creation',
      timestamp: now,
      performedBy: 'system',
    };
    all.push(transaction);
    return { data: all, result: { inserted: true, transaction } };
  });
  const ledger = ledgerOut && typeof ledgerOut === 'object' && 'result' in ledgerOut
    ? ledgerOut.result
    : ledgerOut;
  const transaction = ledger && ledger.transaction;
  if (!transaction) return wallet;

  await updateDb('wallets.json', (all) => {
    const idx = all.findIndex((w) => w.walletId === wallet.walletId);
    if (idx === -1) return { data: all, result: null };
    const w = all[idx];
    if (w.lastCreditTxnId === transaction.transactionId) return { data: all, result: w };
    w.balance = (Number(w.balance) || 0) + amountPaise;
    w.totalRecharged = (Number(w.totalRecharged) || 0) + amountPaise;
    w.lastRecharge = now;
    w.lastRechargeAmount = amountPaise;
    w.lastCreditTxnId = transaction.transactionId;
    w.updatedAt = now;
    return { data: all, result: w };
  });

  return pickWallet(await readDb('wallets.json'), inmateId, wallet.walletId) || wallet;
}

module.exports = { newWalletRecord, pickWallet, ensureWallet, creditWelcomeBonus, WELCOME_BONUS_PAISE };
