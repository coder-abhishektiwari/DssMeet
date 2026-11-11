import { useEffect, useState } from 'react';
import { Card } from '@/components/Card';
import { wardenApi, AppRelease } from '@/services/api/wardenApi';
import { useCachedResource } from '@/hooks/useCachedResource';
import { useToast } from '@/hooks/useToast';
import { ToastContainer } from '@/components/ToastContainer';
import { usePageHeader } from '@/context/PageHeaderContext';
import { Skeleton } from '@/components/Skeleton';

const APPS: { id: AppRelease['app']; label: string; platforms: AppRelease['platform'][] }[] = [
  { id: 'warden', label: 'Warden Desktop', platforms: ['windows', 'linux'] },
  { id: 'kiosk', label: 'Kiosk APK', platforms: ['android'] },
];

const emptyForm = {
  app: 'warden' as AppRelease['app'],
  platform: 'windows' as AppRelease['platform'],
  version: '',
  versionCode: '',
  mandatory: false,
  downloadUrl: '',
  sha256: '',
  notes: '',
};

export function UpdatesPage() {
  const { data: releases, error, refresh } = useCachedResource<AppRelease[]>(
    'app-releases:all:all',
    () => wardenApi.getAppReleases(),
    { ttl: 30_000 }
  );

  const { toasts, success: toastSuccess, error: toastError, removeToast } = useToast();
  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);

  usePageHeader({
    title: 'Updates',
    subtitle: 'Publish new builds and decide whether they are mandatory',
  });

  // Keep the platform list in sync with the selected app.
  useEffect(() => {
    const entry = APPS.find((a) => a.id === form.app);
    if (entry && !entry.platforms.includes(form.platform)) {
      setForm((f) => ({ ...f, platform: entry.platforms[0] }));
    }
  }, [form.app, form.platform]);

  const publish = async () => {
    if (saving) return;
    if (!form.version.trim()) {
      toastError('Version is required.');
      return;
    }
    if (!/^https:\/\//i.test(form.downloadUrl.trim())) {
      toastError('Download URL must start with https://');
      return;
    }
    setSaving(true);
    try {
      await wardenApi.publishAppRelease({
        app: form.app,
        platform: form.platform,
        version: form.version.trim(),
        versionCode: form.versionCode.trim() ? Number(form.versionCode.trim()) : null,
        mandatory: form.mandatory,
        downloadUrl: form.downloadUrl.trim(),
        sha256: form.sha256.trim() || null,
        notes: form.notes.trim() || null,
      });
      toastSuccess(`Published ${form.version}. Clients will be told about it.`);
      setForm(emptyForm);
      await refresh();
    } catch {
      toastError('Failed to publish the release.');
    } finally {
      setSaving(false);
    }
  };

  const toggleMandatory = async (release: AppRelease) => {
    try {
      await wardenApi.updateAppRelease(release.releaseId, { mandatory: !release.mandatory });
      toastSuccess(
        release.mandatory
          ? `${release.version} is now optional.`
          : `${release.version} is now MANDATORY — clients will be blocked until they update.`
      );
      await refresh();
    } catch {
      toastError('Failed to update the release.');
    }
  };

  const selectedApp = APPS.find((a) => a.id === form.app) ?? APPS[0];

  return (
    <div className="space-y-6">
      <ToastContainer toasts={toasts} onDismiss={removeToast} />

      <Card>
        <div className="space-y-5">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-neutral-900 text-white flex items-center justify-center">
              <span className="material-icons text-xl">system_update</span>
            </div>
            <div>
              <h3 className="font-bold text-neutral-900">Publish a new build</h3>
              <p className="text-sm text-neutral-500">
                Usually published automatically by the build — this form is for a manual publish
              </p>
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-semibold text-neutral-700 uppercase tracking-wide mb-1">
                Application
              </label>
              <select
                value={form.app}
                onChange={(e) => setForm({ ...form, app: e.target.value as AppRelease['app'] })}
                className="w-full px-3 py-2.5 border-2 border-neutral-300 rounded-lg text-sm text-neutral-900 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500 bg-white"
              >
                {APPS.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.label}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label className="block text-xs font-semibold text-neutral-700 uppercase tracking-wide mb-1">
                Platform
              </label>
              <select
                value={form.platform}
                onChange={(e) => setForm({ ...form, platform: e.target.value as AppRelease['platform'] })}
                className="w-full px-3 py-2.5 border-2 border-neutral-300 rounded-lg text-sm text-neutral-900 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500 bg-white"
              >
                {selectedApp.platforms.map((p) => (
                  <option key={p} value={p}>
                    {p}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label className="block text-xs font-semibold text-neutral-700 uppercase tracking-wide mb-1">
                Version <span className="text-error">*</span>
              </label>
              <input
                value={form.version}
                onChange={(e) => setForm({ ...form, version: e.target.value })}
                placeholder="1.1.0"
                className="w-full px-3 py-2.5 border-2 border-neutral-300 rounded-lg text-sm text-neutral-900 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500 bg-white"
              />
            </div>

            <div>
              <label className="block text-xs font-semibold text-neutral-700 uppercase tracking-wide mb-1">
                Version code (kiosk only)
              </label>
              <input
                value={form.versionCode}
                onChange={(e) => setForm({ ...form, versionCode: e.target.value })}
                placeholder="2"
                className="w-full px-3 py-2.5 border-2 border-neutral-300 rounded-lg text-sm text-neutral-900 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500 bg-white"
              />
            </div>

            <div className="md:col-span-2">
              <label className="block text-xs font-semibold text-neutral-700 uppercase tracking-wide mb-1">
                Download URL <span className="text-error">*</span>
              </label>
              <input
                value={form.downloadUrl}
                onChange={(e) => setForm({ ...form, downloadUrl: e.target.value })}
                placeholder="https://github.com/.../releases/download/.../kiosk.apk"
                className="w-full px-3 py-2.5 border-2 border-neutral-300 rounded-lg text-sm text-neutral-900 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500 bg-white"
              />
            </div>

            <div className="md:col-span-2">
              <label className="block text-xs font-semibold text-neutral-700 uppercase tracking-wide mb-1">
                SHA-256 (optional, recommended)
              </label>
              <input
                value={form.sha256}
                onChange={(e) => setForm({ ...form, sha256: e.target.value })}
                placeholder="64 hex characters — the client verifies the download against it"
                className="w-full px-3 py-2.5 border-2 border-neutral-300 rounded-lg text-sm text-neutral-900 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500 bg-white"
              />
            </div>

            <div className="md:col-span-2">
              <label className="block text-xs font-semibold text-neutral-700 uppercase tracking-wide mb-1">
                Release notes
              </label>
              <textarea
                rows={2}
                value={form.notes}
                onChange={(e) => setForm({ ...form, notes: e.target.value })}
                placeholder="What changed in this build?"
                className="w-full px-3 py-2.5 border-2 border-neutral-300 rounded-lg text-sm text-neutral-900 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500 bg-white resize-none"
              />
            </div>
          </div>

          <div className="flex items-center justify-between gap-3">
            <label className="flex items-center gap-2 text-sm text-neutral-700">
              <input
                type="checkbox"
                checked={form.mandatory}
                onChange={(e) => setForm({ ...form, mandatory: e.target.checked })}
                className="h-4 w-4 rounded border-neutral-300 text-primary-600 focus:ring-primary-500"
              />
              Mandatory — clients are blocked until they install this build
            </label>
            <button
              onClick={publish}
              disabled={saving}
              className="rounded-lg bg-primary-600 px-5 py-2.5 text-sm font-bold text-white hover:bg-primary-700 disabled:opacity-50"
            >
              {saving ? 'Publishing…' : 'Publish release'}
            </button>
          </div>

          <div className="text-xs text-neutral-600 bg-white border border-neutral-200 rounded-lg p-4">
            <p className="font-semibold text-neutral-900 mb-1">How clients learn about it</p>
            A build publishes itself: the GitHub Actions workflow creates the release and writes this
            registry entry, so the version only has to be written once (in the build). Use this form
            only for a manual publish — and to flip an existing release to mandatory.
            <br />
            <br />
            The kiosk reads the manifest from the heartbeat it already sends, so an update check costs
            no extra request. The desktop console checks the registry on start and every 15 minutes.
            Publishing a new build supersedes the previous one for that app + platform.
          </div>
        </div>
      </Card>

      <Card>
        <div className="space-y-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-neutral-900 text-white flex items-center justify-center">
              <span className="material-icons text-xl">history</span>
            </div>
            <div>
              <h3 className="font-bold text-neutral-900">Published releases</h3>
              <p className="text-sm text-neutral-500">Newest first</p>
            </div>
          </div>

          {error && !releases ? (
            <div className="text-center py-6">
              <p className="text-sm text-neutral-600">{error}</p>
              <button onClick={refresh} className="mt-3 px-4 py-2 bg-primary-600 text-white rounded-lg text-sm">
                Retry
              </button>
            </div>
          ) : !releases ? (
            <div className="space-y-3">
              <Skeleton className="h-12 w-full rounded-lg" />
              <Skeleton className="h-12 w-full rounded-lg" />
            </div>
          ) : releases.length === 0 ? (
            <p className="py-6 text-center text-sm text-neutral-500">
              Nothing published yet — build a release and publish it above.
            </p>
          ) : (
            <div className="divide-y divide-neutral-100">
              {releases.map((r) => (
                <div key={r.releaseId} className="flex flex-wrap items-center justify-between gap-3 py-3">
                  <div>
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-bold text-neutral-900">{r.version}</span>
                      <span className="rounded-full bg-neutral-100 px-2 py-0.5 text-[11px] font-semibold text-neutral-600">
                        {r.app} / {r.platform}
                      </span>
                      {r.mandatory && (
                        <span className="rounded-full bg-red-100 px-2 py-0.5 text-[11px] font-bold text-red-700">
                          MANDATORY
                        </span>
                      )}
                    </div>
                    <p className="mt-0.5 max-w-xl truncate text-xs text-neutral-500">{r.downloadUrl}</p>
                    {r.notes && <p className="mt-0.5 text-xs text-neutral-600">{r.notes}</p>}
                  </div>
                  <button
                    onClick={() => toggleMandatory(r)}
                    className={`rounded-lg px-3 py-1.5 text-xs font-bold ${
                      r.mandatory
                        ? 'border border-neutral-300 text-neutral-600 hover:border-neutral-400'
                        : 'bg-red-600 text-white hover:bg-red-700'
                    }`}
                  >
                    {r.mandatory ? 'Make optional' : 'Make mandatory'}
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
      </Card>
    </div>
  );
}
