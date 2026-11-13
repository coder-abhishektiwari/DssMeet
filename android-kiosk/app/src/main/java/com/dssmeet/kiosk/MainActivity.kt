package com.dssmeet.kiosk

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.dssmeet.kiosk.BuildConfig
import com.dssmeet.kiosk.core.KioskPermissions
import com.dssmeet.kiosk.core.Logger
import com.dssmeet.kiosk.core.SessionManager
import com.dssmeet.kiosk.navigation.KioskNavHost
import com.dssmeet.kiosk.ui.auth.NoInternetScreen
import com.dssmeet.kiosk.ui.setup.SetupPermissionsScreen
import com.dssmeet.kiosk.ui.theme.PrisonKioskTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single-activity entry point hosting the Compose navigation graph.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var networkMonitor: com.dssmeet.kiosk.core.NetworkMonitor
    @Inject lateinit var authRepository: com.dssmeet.kiosk.repository.AuthRepository
    @Inject lateinit var updateManager: com.dssmeet.kiosk.core.UpdateManager
    @Inject lateinit var updateInstaller: com.dssmeet.kiosk.core.UpdateInstaller

    private var inactivityJob: Job? = null

    // Update install progress (shown on the mandatory update gate).
    @Volatile private var updateWorkInProgress = false
    @Volatile private var updateProgressText: String? = null
    @Volatile private var updateErrorText: String? = null

    /** Roles that may install a kiosk update (an admin action). */
    private val ADMIN_ROLES = setOf("admin", "kiosk_admin", "super-admin", "super_admin", "vendor")

    /**
     * Install the update the user asked for.
     *
     * Installing a build is an ADMIN action: when an admin is signed in it
     * starts right away; otherwise the kiosk is logged out and sent to the
     * login screen, and the install resumes automatically once an admin
     * signs in.
     */
    fun requestUpdateInstall(info: com.dssmeet.kiosk.models.appupdate.AppUpdateInfo) {
        val role = currentRole
        if (role != null && role.lowercase() in ADMIN_ROLES) {
            startUpdateInstall(info)
            return
        }
        // Not an admin (inmate session or logged out) → remember the request,
        // drop any session and ask for an admin login.
        updateManager.requestInstall(info)
        Logger.w("MainActivity: update install needs an admin session (role=$role) — logging out")
        lifecycleScope.launch {
            try {
                authRepository.logout().collect { }
            } catch (e: Exception) {
                Logger.w("MainActivity: logout before update failed: ${e.message}")
            }
            com.dssmeet.kiosk.core.SessionEvents.notifyExpired(
                "Log in as admin to install the kiosk update"
            )
        }
    }

    /**
     * Download the published APK, verify it and hand it to the installer.
     * On a device-owner kiosk this is a silent install — the new version is
     * live after the system restarts the app.
     */
    fun startUpdateInstall(info: com.dssmeet.kiosk.models.appupdate.AppUpdateInfo) {
        if (updateWorkInProgress) return
        updateWorkInProgress = true
        updateProgressText = null
        updateErrorText = null
        updateManager.requestInstall(info)
        updateManager.beginInstall()
        lifecycleScope.launch {
            val downloaded = updateInstaller.download(info) { percent ->
                updateManager.setInstallPhase("Downloading…", percent)
            }
            downloaded.onFailure { err ->
                updateWorkInProgress = false
                updateProgressText = null
                updateErrorText = err.message ?: "Download failed"
                updateManager.setInstallError(err.message ?: "Download failed")
                Logger.w("MainActivity: update download failed: ${err.message}")
                return@launch
            }
            val file = downloaded.getOrThrow().file
            updateProgressText = "Installing update…"
            updateManager.setInstallPhase("Installing…", 100)
            val installed = updateInstaller.install(file)
            installed.onSuccess {
                updateProgressText = "Update installed — restarting…"
                Logger.i("MainActivity: update install handed off")
            }.onFailure { err ->
                updateWorkInProgress = false
                updateProgressText = null
                updateErrorText = err.message ?: "Install failed"
                updateManager.setInstallError(err.message ?: "Install failed")
                Logger.w("MainActivity: update install failed: ${err.message}")
            }
        }
    }

    /**
     * The kiosk's inactivity logout is a LOCAL, FIXED policy — 2 minutes by
     * default (AUTO_LOGOUT_TIMEOUT_MS in local.properties). The server is not
     * involved: no request, no config fetch, nothing a client could change.
     */
    private val AUTO_LOGOUT_TIMEOUT_MS = BuildConfig.AUTO_LOGOUT_TIMEOUT_MS

    /**
     * Set by KioskNavHost when a call screen is active (live call AND the
     * family-waiting screen) — the inactivity timer must never cut a call.
     */
    @Volatile var isInCall: Boolean = false

    /**
     * Set while the call lobby dialog is open. The lobby is a Compose Dialog —
     * its own window — so its touches never reach dispatchTouchEvent and the
     * activity cannot see the user is busy. Without this the kiosk could log
     * the inmate out in the middle of starting a call.
     */
    @Volatile var isLobbyOpen: Boolean = false

    /** Role of the current session (null = nobody signed in). */
    @Volatile private var currentRole: String? = null

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable Kiosk Mode: Full screen immersive experience
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            PrisonKioskTheme {
                val windowSizeClass = calculateWindowSizeClass(this)
                val isOnline by networkMonitor.isOnline.collectAsState()
                val updateState by updateManager.state.collectAsState()
                val installState by updateManager.installState.collectAsState()
                val pendingInstall by updateManager.pendingInstall.collectAsState()
                val sessionRole by sessionManager.sessionRole.collectAsState(initial = null)
                val atLogin by updateManager.atLogin.collectAsState()

                // Keep the activity's copy of the role in sync (the install
                // gate needs it outside the composition too).
                LaunchedEffect(sessionRole) { currentRole = sessionRole }

                // A pending install resumes by itself as soon as an ADMIN
                // session exists (the user was asked to log in as admin).
                LaunchedEffect(sessionRole, pendingInstall) {
                    val pending = pendingInstall ?: return@LaunchedEffect
                    val role = sessionRole?.lowercase()
                    if (role != null && role in ADMIN_ROLES && !updateWorkInProgress) {
                        Logger.i("MainActivity: admin session present — resuming the pending update install")
                        startUpdateInstall(pending)
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // One-time setup gate: nothing runs until every runtime
                        // permission the kiosk needs has been collected.
                        var setupComplete by remember {
                            mutableStateOf(KioskPermissions.missing(this@MainActivity).isEmpty())
                        }
                        if (setupComplete) {
                            val navController = rememberNavController()
                            KioskNavHost(
                                navController = navController,
                                windowSizeClass = windowSizeClass
                            )
                        } else {
                            SetupPermissionsScreen(onGranted = { setupComplete = true })
                        }

                        // Global no-internet gate: paints over WHATEVER screen is
                        // showing the instant connectivity drops, and retires the
                        // instant it returns (driven by NetworkMonitor's callback).
                        if (!isOnline) {
                            NoInternetScreen(onRetry = { networkMonitor.refresh() })
                        }

                        // Optional update notice — floats above EVERY screen
                        // (login, inmate dashboard, admin screens) so the
                        // admin who actually maintains the kiosk always sees
                        // it, not just the inmate dashboard.
                        val optionalUpdate =
                            updateState as? com.dssmeet.kiosk.core.UpdateManager.State.Optional
                        if (optionalUpdate != null) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp)
                            ) {
                                com.dssmeet.kiosk.ui.update.OptionalUpdateBanner(
                                    info = optionalUpdate.info,
                                    onInstall = { requestUpdateInstall(optionalUpdate.info) },
                                    onDismiss = { dismissOptionalUpdate() }
                                )
                            }
                        }

                        // The install itself (download → verify → install).
                        // Only reachable with an admin session.
                        val installing = pendingInstall
                        if (installing != null && installState.active) {
                            com.dssmeet.kiosk.ui.update.UpdateInstallScreen(
                                info = installing,
                                phase = installState.phase,
                                percent = installState.percent,
                                error = installState.error,
                                onRetry = { requestUpdateInstall(installing) },
                                onCancel = { updateManager.clearPendingInstall() }
                            )
                        }

                        // Mandatory update gate: a published build that says
                        // "mandatory" blocks the kiosk completely — the screen
                        // stays unusable until the new APK is installed.
                        // EXCEPTION: on the login screen the gate steps aside,
                        // otherwise the admin could never sign in to install it.
                        val mandatory = updateState as? com.dssmeet.kiosk.core.UpdateManager.State.Mandatory
                        if (mandatory != null && !atLogin) {
                            com.dssmeet.kiosk.ui.update.MandatoryUpdateScreen(
                                info = mandatory.info,
                                isWorking = updateWorkInProgress,
                                progressText = updateProgressText,
                                errorText = updateErrorText,
                                onInstall = { requestUpdateInstall(mandatory.info) }
                            )
                        }
                    }
                }
            }
        }

        resetInactivityTimer()

        maybeRunRecorderSpike()
    }

    /**
     * Debug hook: `adb shell am start -n com.dssmeet.kiosk/.MainActivity --ez run_spike true`
     * runs the on-device encoder probe (com.dssmeet.kiosk.debug.RecorderSpike).
     */
    private fun maybeRunRecorderSpike() {
        if (!BuildConfig.DEBUG) return
        if (intent?.getBooleanExtra(EXTRA_RUN_SPIKE, false) != true) return
        intent?.removeExtra(EXTRA_RUN_SPIKE)
        val app = applicationContext
        Thread { com.dssmeet.kiosk.debug.RecorderSpike.run(app) }.start()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        resetInactivityTimer()
        return super.dispatchTouchEvent(ev)
    }

    private fun resetInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = lifecycleScope.launch {
            // The kiosk's inactivity timeout is a fixed local value (2 min).
            val timeoutMs = AUTO_LOGOUT_TIMEOUT_MS
            if (timeoutMs <= 0) return@launch

            // An inactivity window only expires while the kiosk is IDLE. A live
            // call, the family-waiting screen or the open lobby never gets cut:
            // the window simply restarts once that flow is over.
            while (true) {
                delay(timeoutMs)
                if (!isInCall && !isLobbyOpen) break
            }

            // Re-check: the user may have logged in again during the wait.
            if (!sessionManager.hasValidSession()) return@launch

            Logger.w("MainActivity: inactivity timeout — logging out")
            performLogout("Session expired due to inactivity. Please log in again.")
        }
    }

    /**
     * End the user session: revoke it server-side (best effort), wipe the
     * local credentials and bounce back to the login screen with a reason.
     */
    private fun performLogout(reason: String) {
        lifecycleScope.launch {
            try {
                authRepository.logout().collect { }
            } catch (e: Exception) {
                Logger.w("MainActivity: logout call failed: ${e.message}")
            }
            com.dssmeet.kiosk.core.SessionEvents.notifyExpired(reason)
            recreate()
        }
    }

    /** The user dismissed an optional update notice. */
    fun dismissOptionalUpdate() {
        updateManager.dismissOptional()
    }

    override fun onPause() {
        super.onPause()
        inactivityJob?.cancel()
    }

    override fun onResume() {
        super.onResume()
        resetInactivityTimer()
    }

    companion object {
        private const val EXTRA_RUN_SPIKE = "run_spike"
    }
}
