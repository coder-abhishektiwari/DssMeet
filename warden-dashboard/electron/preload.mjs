/**
 * Minimal, validated bridge between the sandboxed renderer and the Electron
 * main process.
 *
 * Capabilities:
 *   getAppInfo()     → { version, platform } so the UI can compare itself with
 *                      the published release registry.
 *   openExternal()   → hand an https URL to the OS browser. The main process
 *                      re-validates the URL against its allowlist.
 *   checkForUpdate() → ask electron-updater whether a newer build exists.
 *   downloadUpdate() → download it (progress arrives via onUpdateEvent).
 *   quitAndInstall() → install the downloaded build and restart the app.
 *   onUpdateEvent()  → subscribe to the updater's progress/state events.
 *
 * No node APIs, no filesystem, no arbitrary IPC are exposed.
 */
import { contextBridge, ipcRenderer } from 'electron';

contextBridge.exposeInMainWorld('dss', {
  getAppInfo: () => ipcRenderer.invoke('dss:app-info'),
  openExternal: (url) => ipcRenderer.invoke('dss:open-external', url),
  checkForUpdate: () => ipcRenderer.invoke('dss:update-check'),
  downloadUpdate: () => ipcRenderer.invoke('dss:update-download'),
  quitAndInstall: () => ipcRenderer.invoke('dss:update-quit-install'),
  onUpdateEvent: (callback) => {
    const listener = (_event, payload) => callback(payload);
    ipcRenderer.on('dss:update-event', listener);
    return () => ipcRenderer.removeListener('dss:update-event', listener);
  },
});
