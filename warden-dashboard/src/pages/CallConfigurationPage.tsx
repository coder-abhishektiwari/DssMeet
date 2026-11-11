import { useEffect, useState } from 'react';
import { Card } from '@/components/Card';
import { wardenApi, cacheKeys, Settings, SetupPasswordData, PrisonInfo, AutoLogoutSettings } from '@/services/api/wardenApi';
import { useCachedResource } from '@/hooks/useCachedResource';
import { getStoredUser } from '@/services/auth/tokenStorage';
import { useToast } from '@/hooks/useToast';
import { ToastContainer } from '@/components/ToastContainer';
import { usePageHeader } from '@/context/PageHeaderContext';
import { Skeleton } from '@/components/Skeleton';

/** Must match the server's accepted timeouts (backend/routes/settings.js). */
const AUTO_LOGOUT_OPTIONS = [2, 5, 10];

export function CallConfigurationPage() {
  const { data: settings, error, refresh } = useCachedResource<Settings>(
    cacheKeys.settings(),
    () => wardenApi.getSettings(),
    { ttl: 60_000 },
  );
  const storedUser = getStoredUser();
  const prisonId = storedUser?.prisonId || '';

  // Kiosk inactivity logout — the policy lives on the SERVER (the kiosk app
  // only reads it), so this page never decides anything on its own.
  const [autoLogout, setAutoLogout] = useState<AutoLogoutSettings>({ enabled: false, timeoutMinutes: 5 });
  const [savingAutoLogout, setSavingAutoLogout] = useState(false);

  useEffect(() => {
    const current = settings?.securitySettings?.autoLogout;
    if (current) setAutoLogout({ enabled: current.enabled, timeoutMinutes: current.timeoutMinutes });
  }, [settings]);

  const { data: prison, error: prisonError, refresh: refreshPrison } = useCachedResource<PrisonInfo>(
    cacheKeys.prison(prisonId),
    () => wardenApi.getPrison(prisonId),
    { ttl: 60_000 },
  );

  const { data: passwordData, refresh: refreshPassword } = useCachedResource<SetupPasswordData>(
    `kiosks:password:${prisonId}`,
    () => wardenApi.getSetupPassword(prisonId),
    { ttl: 60_000 },
  );

  const { toasts, success: toastSuccess, error: toastError, removeToast } = useToast();
  const [passwordInput, setPasswordInput] = useState('');
  const [editing, setEditing] = useState(false);
  const [savingPassword, setSavingPassword] = useState(false);

  const [prisonEditing, setPrisonEditing] = useState(false);
  const [savingPrison, setSavingPrison] = useState(false);
  const [prisonForm, setPrisonForm] = useState({ name: '', state: '', district: '', address: '' });

  const passwordSet = !!passwordData?.passwordSet;
  const inputEnabled = !passwordSet || editing;

  usePageHeader({
    title: 'Configurations',
    subtitle: 'Manage facility setup & kiosk configuration',
  });

  const startPrisonEdit = () => {
    setPrisonForm({
      name: prison?.name ?? '',
      state: prison?.state ?? '',
      district: prison?.district ?? '',
      address: prison?.address ?? '',
    });
    setPrisonEditing(true);
  };

  const savePrison = async () => {
    if (savingPrison) return;
    const name = prisonForm.name.trim();
    if (!name) {
      toastError('Jail name is required.');
      return;
    }
    setSavingPrison(true);
    try {
      await wardenApi.updatePrison(prisonId, {
        name,
        state: prisonForm.state.trim(),
        district: prisonForm.district.trim(),
        address: prisonForm.address.trim(),
      });
      toastSuccess('Jail information updated.');
      setPrisonEditing(false);
      await refreshPrison();
    } catch {
      toastError('Failed to update jail information.');
    } finally {
      setSavingPrison(false);
    }
  };

  // Mirrors the backend policy: 8-64 chars, at least one letter and one digit.
  const isStrongPassword = (v: string) => v.length >= 8 && v.length <= 64 && /[A-Za-z]/.test(v) && /\d/.test(v);
  const savePassword = async () => {
    if (!isStrongPassword(passwordInput) || savingPassword) return;
    setSavingPassword(true);
    try {
      await wardenApi.updateSetupPassword(prisonId, passwordInput);
      toastSuccess('Setup password updated. New kiosks must use the new password.');
      setPasswordInput('');
      setEditing(false);
      await refreshPassword();
    } catch {
      toastError('Failed to update password. Must be 8-64 characters with letters and numbers.');
    } finally {
      setSavingPassword(false);
    }
  };

  const saveAutoLogout = async () => {
    if (savingAutoLogout) return;
    setSavingAutoLogout(true);
    try {
      await wardenApi.updateAutoLogout({
        enabled: autoLogout.enabled,
        timeoutMinutes: autoLogout.timeoutMinutes,
      });
      toastSuccess(
        autoLogout.enabled
          ? `Dashboard will sign out after ${autoLogout.timeoutMinutes} minute(s) of inactivity.`
          : 'Auto logout disabled — the dashboard stays signed in.',
      );
      await refresh();
    } catch {
      toastError('Failed to update auto logout settings.');
    } finally {
      setSavingAutoLogout(false);
    }
  };

  if (error && settings === undefined) {
    return (
      <div className="space-y-6">
        <Card>
          <div className="text-center py-12">
            <p className="text-neutral-600">{error}</p>
            <button onClick={refresh} className="mt-4 px-4 py-2 bg-primary-600 text-white rounded-lg text-sm hover:bg-primary-700">Retry</button>
          </div>
        </Card>
      </div>
    );
  }

  const viewField = (label: string, value?: string | null) => (
    <div key={label}>
      <span className="block text-xs font-semibold text-neutral-700 uppercase tracking-wide mb-1">{label}</span>
      <p className="text-sm text-neutral-900 min-h-[1.25rem]">{value && value.trim() ? value : '—'}</p>
    </div>
  );

  const editField = (
    label: string,
    value: string,
    onChange: (v: string) => void,
    opts?: { type?: string; inputMode?: 'numeric'; maxLength?: number; required?: boolean },
  ) => (
    <div key={label}>
      <label className="block text-xs font-semibold text-neutral-700 uppercase tracking-wide mb-1">
        {label} {opts?.required && <span className="text-error">*</span>}
      </label>
      <input
        type={opts?.type || 'text'}
        inputMode={opts?.inputMode}
        maxLength={opts?.maxLength}
        value={value}
        disabled={savingPrison}
        onChange={(e) => onChange(e.target.value)}
        className="w-full px-3 py-2.5 border-2 border-neutral-300 rounded-lg text-sm text-neutral-900 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500 bg-white disabled:bg-neutral-100"
      />
    </div>
  );

  const statusActive = (prison?.status || 'active') === 'active';

  return (
    <div className="space-y-6">
      <ToastContainer toasts={toasts} onDismiss={removeToast} />

      <Card>
        <div className="space-y-6">
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-lg bg-neutral-900 text-white flex items-center justify-center">
                <span className="material-icons text-xl">account_balance</span>
              </div>
              <div>
                <h3 className="font-bold text-neutral-900">Jail Information</h3>
                <p className="text-sm text-neutral-500">Facility profile shown across the dashboard</p>
              </div>
            </div>
            {!prisonEditing ? (
              <button
                onClick={startPrisonEdit}
                disabled={!prison}
                title="Edit jail information"
                className="w-9 h-9 flex items-center justify-center bg-white border-2 border-neutral-300 text-neutral-600 rounded-lg hover:border-primary-400 hover:text-primary-400 transition disabled:opacity-40"
              >
                <span className="material-icons text-lg">edit</span>
              </button>
            ) : (
              <div className="flex items-center gap-2">
                <button
                  onClick={() => setPrisonEditing(false)}
                  disabled={savingPrison}
                  title="Cancel"
                  className="w-9 h-9 flex items-center justify-center bg-white border-2 border-neutral-300 text-neutral-600 rounded-lg hover:border-error hover:text-error transition disabled:opacity-40"
                >
                  <span className="material-icons text-lg">close</span>
                </button>
                <button
                  onClick={savePrison}
                  disabled={savingPrison || !prisonForm.name.trim()}
                  title="Save"
                  className="w-9 h-9 flex items-center justify-center bg-success text-white rounded-lg hover:opacity-90 transition disabled:opacity-50"
                >
                  <span className="material-icons text-lg">{savingPrison ? 'hourglass_top' : 'check'}</span>
                </button>
              </div>
            )}
          </div>

          {prisonError && !prison ? (
            <div className="text-center py-6">
              <p className="text-sm text-neutral-600">{prisonError}</p>
              <button onClick={refreshPrison} className="mt-3 px-4 py-2 bg-primary-600 text-white rounded-lg text-sm hover:bg-primary-700">Retry</button>
            </div>
          ) : !prison ? (
            <div className="space-y-6">
              <div className="bg-neutral-50 border border-neutral-200 rounded-xl p-4 flex flex-wrap items-center justify-between gap-3">
                <div className="space-y-2.5">
                  <Skeleton className="h-3.5 w-20 blur-[1px] opacity-80" />
                  <Skeleton className="h-5 w-36 rounded-md blur-[1px] opacity-80" />
                </div>
                <div className="flex items-center gap-4">
                  <Skeleton className="h-7 w-20 rounded-full blur-[1px] opacity-80" />
                  <Skeleton className="h-4 w-20 rounded-md blur-[1px] opacity-80" />
                  <Skeleton className="h-4 w-16 rounded-md blur-[1px] opacity-80" />
                </div>
              </div>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {Array.from({ length: 4 }).map((_, i) => (
                  <div key={i} className="space-y-2">
                    <Skeleton className="h-3.5 w-24 blur-[1px] opacity-80" />
                    <Skeleton className="h-10 w-full rounded-lg blur-[1px] opacity-80" />
                  </div>
                ))}
                <div className="md:col-span-2 space-y-2">
                  <Skeleton className="h-3.5 w-20 blur-[1px] opacity-80" />
                  <Skeleton className="h-16 w-full rounded-lg blur-[1px] opacity-80" />
                </div>
              </div>
            </div>
          ) : (
            <>
              <div className="bg-neutral-50 border border-neutral-200 rounded-xl p-4 flex flex-wrap items-center justify-between gap-3">
                <div>
                  <span className="block text-xs font-semibold text-neutral-700 uppercase tracking-wide mb-1">Prison ID</span>
                  <span className="font-mono font-bold text-neutral-900">{prison.prisonId || prisonId || '—'}</span>
                </div>
                <div className="flex items-center gap-4 text-xs text-neutral-500">
                  <span className={`px-2 py-1 rounded-full font-semibold text-white ${statusActive ? 'bg-success' : 'bg-neutral-400'}`}>
                    {prison.status || 'active'}
                  </span>
                  <span>{prison.currentInmateCount ?? 0} inmates</span>
                  <span>{(prison.kioskIds ?? []).length} kiosks</span>
                  <span className="flex items-center gap-1" title="Prison ID is permanent and cannot be changed">
                    <span className="material-icons text-sm text-neutral-400">lock</span> Read-only
                  </span>
                </div>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {prisonEditing ? (
                  <>
                    {editField('Jail Name', prisonForm.name, (v) => setPrisonForm({ ...prisonForm, name: v }), { required: true })}
                    {editField('State', prisonForm.state, (v) => setPrisonForm({ ...prisonForm, state: v }))}
                    {editField('District', prisonForm.district, (v) => setPrisonForm({ ...prisonForm, district: v }))}
                    <div className="hidden md:block" />
                    <div className="md:col-span-2">
                      <label className="block text-xs font-semibold text-neutral-700 uppercase tracking-wide mb-1">Address</label>
                      <textarea
                        rows={2}
                        value={prisonForm.address}
                        disabled={savingPrison}
                        onChange={(e) => setPrisonForm({ ...prisonForm, address: e.target.value })}
                        className="w-full px-3 py-2.5 border-2 border-neutral-300 rounded-lg text-sm text-neutral-900 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500 bg-white disabled:bg-neutral-100 resize-none"
                      />
                    </div>
                  </>
                ) : (
                  <>
                    {viewField('Jail Name', prison.name)}
                    {viewField('State', prison.state)}
                    {viewField('District', prison.district)}
                    {viewField('Address', prison.address)}
                  </>
                )}
              </div>
            </>
          )}
        </div>
      </Card>

      <Card>
        <div className="space-y-6">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-neutral-900 text-white flex items-center justify-center">
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
              </svg>
            </div>
            <div>
              <h3 className="font-bold text-neutral-900">Facility Setup Password</h3>
              <p className="text-sm text-neutral-500">Required for first-time kiosk hardware provisioning</p>
            </div>
          </div>

          {passwordData === undefined ? (
            <div className="bg-neutral-50 border border-neutral-200 rounded-xl p-6 max-w-md space-y-3">
              <Skeleton className="h-3.5 w-24 blur-[1px] opacity-80" />
              <Skeleton className="h-14 w-full rounded-lg blur-[1px] opacity-80" />
              <Skeleton className="h-3.5 w-3/4 blur-[1px] opacity-80" />
            </div>
          ) : (
            <div className="bg-neutral-50 border border-neutral-200 rounded-xl p-6 max-w-md">
              <label className="block text-xs font-semibold text-neutral-700 uppercase tracking-wide mb-2">
                Setup Password <span className="text-error">*</span>
              </label>
              <div className="relative">
                <input
                  type="password"
                  maxLength={64}
                  disabled={!inputEnabled || savingPassword}
                  value={inputEnabled ? passwordInput : '••••••••'}
                  placeholder="••••••••"
                  onChange={(e) => setPasswordInput(e.target.value.slice(0, 64))}
                  className="w-full px-4 py-3 pr-24 border-2 border-neutral-300 rounded-lg font-mono font-bold text-center tracking-[0.35em] text-xl focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500 bg-white disabled:bg-neutral-100 disabled:text-neutral-500 disabled:cursor-not-allowed"
                />
                {inputEnabled ? (
                  isStrongPassword(passwordInput) && (
                    <button
                      onClick={savePassword}
                      disabled={savingPassword}
                      title="Save password"
                      className="absolute right-2 top-1/2 -translate-y-1/2 w-9 h-9 flex items-center justify-center bg-success text-white rounded-lg hover:opacity-90 transition disabled:opacity-50"
                    >
                      <span className="material-icons text-lg">{savingPassword ? 'hourglass_top' : 'check'}</span>
                    </button>
                  )
                ) : (
                  <button
                    onClick={() => { setEditing(true); setPasswordInput(''); }}
                    title="Edit password"
                    className="absolute right-2 top-1/2 -translate-y-1/2 w-9 h-9 flex items-center justify-center bg-white border-2 border-neutral-300 text-neutral-600 rounded-lg hover:border-primary-400 hover:text-primary-400 transition"
                  >
                    <span className="material-icons text-lg">edit</span>
                  </button>
                )}
              </div>
              <p className="text-xs text-neutral-500 mt-3">
                {!passwordSet
                  ? '8-64 characters with at least one letter and one number, then tap the green tick to save.'
                  : inputEnabled
                    ? `Current password is set. Enter a new password, then tap the green tick.`
                    : 'Password is set. Tap the pencil to rotate it.'}
              </p>
            </div>
          )}

          <div className="text-xs text-neutral-600 bg-white border border-neutral-200 rounded-lg p-4 max-w-md">
            <p className="font-semibold text-neutral-900 mb-1">Security note</p>
            Password is bcrypt-hashed server-side and never returned in GET. Only <code className="bg-neutral-100 px-1 rounded">passwordSet: true/false</code> is readable. Rotate periodically.
          </div>
        </div>
      </Card>

      <Card>
        <div className="space-y-6">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-neutral-900 text-white flex items-center justify-center">
              <span className="material-icons text-xl">timer</span>
            </div>
            <div>
              <h3 className="font-bold text-neutral-900">Auto Logout (Dashboard Inactivity)</h3>
              <p className="text-sm text-neutral-500">Sign this dashboard out after the selected idle time</p>
            </div>
          </div>

          <div className="bg-neutral-50 border border-neutral-200 rounded-xl p-5 space-y-5 max-w-md">
            <div className="flex items-center justify-between gap-4">
              <div>
                <span className="block text-xs font-semibold text-neutral-700 uppercase tracking-wide mb-1">
                  Enable auto logout
                </span>
              <p className="text-xs text-neutral-500">
                {autoLogout.enabled
                  ? `This dashboard signs out after ${autoLogout.timeoutMinutes} minute(s) without any interaction.`
                  : 'This dashboard stays signed in until you log out manually.'}
              </p>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={autoLogout.enabled}
                aria-label="Enable auto logout"
                title={autoLogout.enabled ? 'Enabled - click to disable' : 'Disabled - click to enable'}
                onClick={() => setAutoLogout({ ...autoLogout, enabled: !autoLogout.enabled })}
                disabled={savingAutoLogout}
                className={`relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition disabled:opacity-50 ${
                  autoLogout.enabled ? 'bg-success' : 'bg-neutral-300'
                }`}
              >
                <span
                  className={`inline-block h-4 w-4 rounded-full bg-white shadow-sm transition-transform ${
                    autoLogout.enabled ? 'translate-x-[18px]' : 'translate-x-[2px]'
                  }`}
                />
              </button>
            </div>

            <div>
              <label className="block text-xs font-semibold text-neutral-700 uppercase tracking-wide mb-1">
                Inactivity timeout
              </label>
              <select
                value={String(autoLogout.timeoutMinutes)}
                onChange={(e) => setAutoLogout({ ...autoLogout, timeoutMinutes: Number(e.target.value) })}
                disabled={!autoLogout.enabled || savingAutoLogout}
                className="w-full px-3 py-2.5 border-2 border-neutral-300 rounded-lg text-sm text-neutral-900 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500 bg-white disabled:bg-neutral-100 disabled:text-neutral-500"
              >
                {AUTO_LOGOUT_OPTIONS.map((minutes) => (
                  <option key={minutes} value={minutes}>
                    {minutes} minute{minutes > 1 ? 's' : ''}
                  </option>
                ))}
              </select>
              <p className="text-xs text-neutral-500 mt-2">
                The countdown restarts on every click, keypress, scroll or mouse move.
              </p>
            </div>

            <div className="flex items-center justify-end gap-3">
              <span className="text-xs text-neutral-500">
                {autoLogout.enabled ? `Auto logout: ${autoLogout.timeoutMinutes} min` : 'Auto logout: off'}
              </span>
              <button
                onClick={saveAutoLogout}
                disabled={savingAutoLogout}
                title="Save auto logout settings"
                className="w-9 h-9 flex items-center justify-center bg-success text-white rounded-lg hover:opacity-90 transition disabled:opacity-50"
              >
                <span className="material-icons text-lg">{savingAutoLogout ? 'hourglass_top' : 'check'}</span>
              </button>
            </div>
          </div>

          <div className="text-xs text-neutral-600 bg-white border border-neutral-200 rounded-lg p-4 max-w-md">
            <p className="font-semibold text-neutral-900 mb-1">Where this applies</p>
            This setting is stored on the server, so it applies to every warden who opens this dashboard —
            the countdown itself runs in the browser. The inmate kiosk has its own, separate inactivity logout
            (a fixed 2 minutes inside the app) and is not affected by this setting.
          </div>
        </div>
      </Card>
    </div>
  );
}
