import { AppRelease } from '@/services/api/wardenApi';

interface UpdateGateProps {
  release: AppRelease;
  notes?: string | null;
  /** 0-100 while downloading, null when idle. */
  percent: number | null;
  downloading: boolean;
  downloaded: boolean;
  error?: string | null;
  onInstall: (release: AppRelease) => void;
  onRestart: () => void;
}

/**
 * Mandatory update gate — the dashboard does not keep working on an outdated
 * build. There is no way past it: the only action is installing the update.
 *
 * On the installed desktop app the download is handled by electron-updater
 * (silent, verified by the release checksum); in a browser tab it falls back
 * to opening the download URL.
 */
export function MandatoryUpdateGate({
  release,
  notes,
  percent,
  downloading,
  downloaded,
  error,
  onInstall,
  onRestart,
}: UpdateGateProps) {
  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-neutral-900/95 px-6">
      <div className="w-full max-w-md rounded-2xl bg-white p-8 shadow-2xl text-center">
        <span className="material-icons text-5xl text-amber-500">system_update</span>
        <h2 className="mt-4 text-xl font-bold text-neutral-900">UPDATE REQUIRED</h2>
        <p className="mt-2 text-sm text-neutral-600">
          Version {release.version} must be installed before this console can be used again.
        </p>
        {notes && (
          <div className="mt-4 rounded-lg border border-neutral-200 bg-neutral-50 p-3 text-left text-xs text-neutral-600">
            {notes}
          </div>
        )}

        {downloading && (
          <div className="mt-5">
            <div className="h-2 w-full overflow-hidden rounded-full bg-neutral-200">
              <div
                className="h-full bg-primary-600 transition-all"
                style={{ width: `${Math.max(4, percent ?? 0)}%` }}
              />
            </div>
            <p className="mt-2 text-xs text-neutral-500">Downloading… {percent ?? 0}%</p>
          </div>
        )}

        {error && <p className="mt-4 text-xs font-semibold text-red-600">{error}</p>}

        <button
          onClick={() => (downloaded ? onRestart() : onInstall(release))}
          disabled={downloading}
          className="mt-6 w-full rounded-lg bg-primary-600 px-4 py-3 text-sm font-bold text-white hover:bg-primary-700 disabled:opacity-60"
        >
          {downloaded
            ? 'Restart & install update'
            : downloading
              ? 'Downloading…'
              : `Download & install ${release.version}`}
        </button>
        <p className="mt-3 text-xs text-neutral-500">
          {downloaded
            ? 'The console restarts into the new version.'
            : 'The installer opens in your browser. Close and reopen the console after it finishes.'}
        </p>
      </div>
    </div>
  );
}

interface UpdateBannerProps {
  release: AppRelease;
  downloading: boolean;
  downloaded: boolean;
  percent: number | null;
  error?: string | null;
  onDownload: (release: AppRelease) => void;
  onRestart: () => void;
  onDismiss: () => void;
}

/** Optional update notice — dismissible, the console keeps working. */
export function UpdateBanner({
  release,
  downloading,
  downloaded,
  percent,
  error,
  onDownload,
  onRestart,
  onDismiss,
}: UpdateBannerProps) {
  return (
    <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-300 bg-amber-50 px-4 py-3">
      <div className="flex items-center gap-3">
        <span className="material-icons text-xl text-amber-600">system_update</span>
        <div>
          <p className="text-sm font-bold text-neutral-900">
            New version available ({release.version})
          </p>
          <p className="text-xs text-neutral-600">
            {downloading
              ? `Downloading… ${percent ?? 0}%`
              : downloaded
                ? 'Ready — restart to install.'
                : 'Update to get the latest fixes and features.'}
          </p>
          {error && <p className="text-xs font-semibold text-red-600">{error}</p>}
        </div>
      </div>
      <div className="flex items-center gap-2">
        <button
          onClick={() => (downloaded ? onRestart() : onDownload(release))}
          disabled={downloading}
          className="rounded-lg bg-primary-600 px-3 py-1.5 text-xs font-bold text-white hover:bg-primary-700 disabled:opacity-60"
        >
          {downloaded ? 'Restart' : downloading ? 'Downloading…' : 'Install'}
        </button>
        <button
          onClick={onDismiss}
          className="rounded-lg border border-neutral-300 px-3 py-1.5 text-xs font-semibold text-neutral-600 hover:border-neutral-400"
        >
          Later
        </button>
      </div>
    </div>
  );
}
