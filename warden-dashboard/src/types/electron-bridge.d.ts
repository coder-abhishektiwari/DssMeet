/**
 * The Electron preload bridge (electron/preload.mjs). Present only in the
 * desktop build — in a browser tab the app simply has no bridge and falls
 * back to the browser's user-agent.
 */
export type UpdateEvent = {
  type: 'checking' | 'available' | 'not-available' | 'progress' | 'downloaded' | 'error';
  version?: string | null;
  percent?: number;
  transferred?: number;
  total?: number;
  bytesPerSecond?: number;
  message?: string;
};

export interface DssBridge {
  getAppInfo(): Promise<{ version: string; platform: string }>;
  openExternal(url: string): Promise<boolean>;
  checkForUpdate(): Promise<{
    available: boolean;
    version?: string | null;
    releaseNotes?: string | null;
    releaseDate?: string | null;
    reason?: string;
  }>;
  downloadUpdate(): Promise<{ ok: boolean; error?: string }>;
  quitAndInstall(): Promise<{ ok: boolean }>;
  onUpdateEvent(callback: (event: UpdateEvent) => void): () => void;
}

declare global {
  interface Window {
    dss?: DssBridge;
  }
}

export {};
