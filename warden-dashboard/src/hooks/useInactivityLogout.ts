import { useEffect, useRef } from 'react';
import { useAuth } from '@/context/AuthContext';
import { wardenApi } from '@/services/api/wardenApi';

/**
 * Dashboard auto logout — a LOCAL inactivity timer.
 *
 * The VALUE (enabled + timeout in minutes) comes from the server, where the
 * warden sets it under Configurations → Auto Logout. The timer itself runs
 * entirely in the browser: no polling, no server round trip, no heartbeat.
 * The server is never asked "is this session idle?".
 *
 * The kiosk app has its own, unrelated, fixed 2-minute inactivity logout —
 * this hook has nothing to do with it.
 */
const ACTIVITY_EVENTS = ['mousemove', 'mousedown', 'keydown', 'scroll', 'touchstart', 'wheel'] as const;

export function useInactivityLogout() {
  const { isAuthenticated, logout } = useAuth();
  const timerRef = useRef<number | null>(null);

  useEffect(() => {
    if (!isAuthenticated) return;

    let timeoutMinutes = 0;
    let cancelled = false;

    const arm = () => {
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current);
        timerRef.current = null;
      }
      // 0 = auto logout switched off — the dashboard stays signed in.
      if (timeoutMinutes <= 0) return;
      timerRef.current = window.setTimeout(() => {
        void logout();
      }, timeoutMinutes * 60_000);
    };

    // One cached read of the policy (the api layer caches it for 60s).
    wardenApi
      .getSettings()
      .then((settings) => {
        if (cancelled) return;
        const policy = settings?.securitySettings?.autoLogout;
        timeoutMinutes = policy?.enabled ? policy.timeoutMinutes : 0;
        arm();
      })
      .catch(() => {
        // Settings unreachable → stay signed in (never log the warden out
        // because of a failed config read).
      });

    const onActivity = () => arm();
    ACTIVITY_EVENTS.forEach((event) =>
      window.addEventListener(event, onActivity, { passive: true })
    );

    return () => {
      cancelled = true;
      ACTIVITY_EVENTS.forEach((event) => window.removeEventListener(event, onActivity));
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current);
        timerRef.current = null;
      }
    };
  }, [isAuthenticated, logout]);
}
