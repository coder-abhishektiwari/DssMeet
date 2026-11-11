import { useCallback, useEffect, useRef, useState } from 'react';
import { env } from '@/config/env';
import { wardenApi, AppRelease } from '@/services/api/wardenApi';

/**
 * Desktop update handling.
 *
 * Two paths, same UI:
 *
 *  1. INSTALLED DESKTOP APP → electron-updater. It talks to the GitHub
 *     Releases feed, downloads the new installer and installs it on restart.
 *     (The release is published from the "Warden Desktop Builds" workflow.)
 *
 *  2. BROWSER TAB / UNPACKAGED BUILD → the backend release registry. The UI
 *     then just opens the download URL.
 *
 * In both cases the VALUE ("is there a newer build, is it mandatory") is
 * decided by the server; the download and the countdown run locally.
 *
 * NOTE: this is the WARDEN DESKTOP's own update. The inmate kiosk has a
 * separate, fixed 2-minute inactivity logout and its own APK updater — the
 * two never touch each other.
 */
export type UpdateState =
  | { kind: 'unknown' }
  | { kind: 'up-to-date' }
  | { kind: 'optional'; release: AppRelease; notes?: string | null }
  | { kind: 'mandatory'; release: AppRelease; notes?: string | null };

export type DownloadState = {
  active: boolean;
  percent: number;
  error?: string | null;
  /** The new build is downloaded and ready to install. */
  ready: boolean;
};

const CHECK_INTERVAL_MS = 15 * 60 * 1000;

/** Compare dotted versions: '1.2.10' > '1.2.9'. Returns >0 when a is newer. */
export function compareVersions(a: string, b: string): number {
  const pa = a.split('.').map((n) => parseInt(n, 10) || 0);
  const pb = b.split('.').map((n) => parseInt(n, 10) || 0);
  const len = Math.max(pa.length, pb.length);
  for (let i = 0; i < len; i += 1) {
    const diff = (pa[i] || 0) - (pb[i] || 0);
    if (diff !== 0) return diff;
  }
  return 0;
}

function currentPlatform(): string {
  if (env.appPlatform) return env.appPlatform;
  const ua = typeof navigator !== 'undefined' ? navigator.userAgent : '';
  if (/Windows/i.test(ua)) return 'windows';
  if (/Mac/i.test(ua)) return 'mac';
  return 'linux';
}

export function useAppUpdate() {
  const [state, setState] = useState<UpdateState>({ kind: 'unknown' });
  const [download, setDownload] = useState<DownloadState>({
    active: false,
    percent: 0,
    error: null,
    ready: false,
  });
  const [dismissed, setDismissed] = useState(false);
  const unsubscribeRef = useRef<(() => void) | null>(null);

  // Subscribe to the updater's progress events once.
  useEffect(() => {
    const bridge = window.dss;
    if (!bridge?.onUpdateEvent) return;
    const off = bridge.onUpdateEvent((event) => {
      switch (event.type) {
        case 'checking':
          setDownload((d) => ({ ...d, active: true, error: null }));
          break;
        case 'available':
          setDownload((d) => ({ ...d, active: true, percent: 0 }));
          break;
        case 'progress':
          setDownload((d) => ({
            ...d,
            active: true,
            percent: event.percent ?? d.percent,
          }));
          break;
        case 'downloaded':
          setDownload((d) => ({ ...d, active: false, percent: 100, ready: true }));
          break;
        case 'error':
          setDownload((d) => ({ ...d, active: false, error: event.message ?? 'Update failed' }));
          break;
        default:
          break;
      }
    });
    unsubscribeRef.current = off;
    return () => {
      off();
      unsubscribeRef.current = null;
    };
  }, []);

  /** electron-updater first (installed app), registry as the fallback. */
  const check = useCallback(async () => {
    const bridge = window.dss;
    if (bridge?.checkForUpdate) {
      try {
        const result = await bridge.checkForUpdate();
        if (!result?.available || !result.version) {
          setState({ kind: 'up-to-date' });
          return;
        }
        // The updater knows the version; the registry knows whether it is
        // mandatory — ask the registry for that.
        let mandatory = false;
        let notes: string | null = null;
        try {
          const release = await wardenApi.getLatestAppRelease('warden', currentPlatform());
          if (release && compareVersions(release.version, result.version) === 0) {
            mandatory = release.mandatory;
            notes = release.notes;
          }
        } catch {
          // Registry unreachable — treat it as optional rather than blocking.
        }
        const pseudo: AppRelease = {
          releaseId: 'electron-updater',
          app: 'warden',
          platform: currentPlatform() as AppRelease['platform'],
          version: result.version,
          versionCode: null,
          mandatory,
          downloadUrl: '',
          sha256: null,
          sizeBytes: null,
          notes,
          releasedAt: result.releaseDate ?? null,
        };
        setState(mandatory ? { kind: 'mandatory', release: pseudo, notes } : { kind: 'optional', release: pseudo, notes });
        return;
      } catch {
        setState({ kind: 'up-to-date' });
        return;
      }
    }

    // Browser / unpackaged build → the registry.
    try {
      const release = await wardenApi.getLatestAppRelease('warden', currentPlatform());
      if (!release) {
        setState({ kind: 'up-to-date' });
        return;
      }
      const current = env.appVersion || '0.0.0';
      if (compareVersions(release.version, current) <= 0) {
        setState({ kind: 'up-to-date' });
        return;
      }
      setState(
        release.mandatory
          ? { kind: 'mandatory', release, notes: release.notes }
          : { kind: 'optional', release, notes: release.notes }
      );
    } catch {
      // Registry unreachable — never block the warden because of a failed check.
      setState({ kind: 'up-to-date' });
    }
  }, []);

  useEffect(() => {
    void check();
    const timer = window.setInterval(() => {
      if (!document.hidden) void check();
    }, CHECK_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [check]);

  const dismiss = useCallback(() => setDismissed(true), []);

  /** Download the update (desktop) or open the download URL (browser). */
  const install = useCallback(
    async (release: AppRelease) => {
      const bridge = window.dss;
      if (bridge?.downloadUpdate) {
        setDownload({ active: true, percent: 0, error: null, ready: false });
        const result = await bridge.downloadUpdate();
        if (!result?.ok) {
          setDownload((d) => ({ ...d, active: false, error: result?.error ?? 'Download failed' }));
        }
        return;
      }
      if (bridge?.openExternal) {
        await bridge.openExternal(release.downloadUrl);
        return;
      }
      window.open(release.downloadUrl, '_blank', 'noopener');
    },
    []
  );

  /** Restart into the downloaded build. */
  const restart = useCallback(async () => {
    await window.dss?.quitAndInstall?.();
  }, []);

  const effective: UpdateState =
    dismissed && state.kind === 'optional' ? { kind: 'up-to-date' } : state;

  return {
    state: effective,
    download,
    check,
    dismiss,
    install,
    restart,
    openDownload: async (release: AppRelease) => {
      if (window.dss?.openExternal) {
        await window.dss.openExternal(release.downloadUrl);
        return;
      }
      window.open(release.downloadUrl, '_blank', 'noopener');
    },
  };
}
