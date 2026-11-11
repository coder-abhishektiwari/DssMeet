import { Outlet } from 'react-router-dom';
import { Sidebar } from '@/components/ui/Sidebar';
import { Header } from '@/components/ui/Header';
import { PageHeaderProvider } from '@/context/PageHeaderContext';
import { useInactivityLogout } from '@/hooks/useInactivityLogout';
import { useAppUpdate } from '@/hooks/useAppUpdate';
import { MandatoryUpdateGate, UpdateBanner } from '@/components/update/UpdateGate';

export function DashboardLayout() {
  // Local inactivity timer — the value comes from the server (warden
  // configurable), the countdown runs in the browser.
  useInactivityLogout();

  // Update check — on the installed desktop app electron-updater handles the
  // download; a mandatory release blocks the whole console.
  const { state: updateState, download, dismiss, install, restart } = useAppUpdate();

  return (
    <PageHeaderProvider>
      <div className="min-h-screen bg-neutral-50">
        <Sidebar />
        <div className="ml-16 flex flex-col min-h-screen">
          <Header />
          <main className="flex-1 p-6">
            {updateState.kind === 'optional' && (
              <UpdateBanner
                release={updateState.release}
                downloading={download.active}
                downloaded={download.ready}
                percent={download.percent}
                error={download.error}
                onDownload={install}
                onRestart={restart}
                onDismiss={dismiss}
              />
            )}
            <Outlet />
          </main>
        </div>
        {updateState.kind === 'mandatory' && (
          <MandatoryUpdateGate
            release={updateState.release}
            notes={updateState.notes}
            percent={download.active ? download.percent : null}
            downloading={download.active}
            downloaded={download.ready}
            error={download.error}
            onInstall={install}
            onRestart={restart}
          />
        )}
      </div>
    </PageHeaderProvider>
  );
}
