package com.dssmeet.kiosk

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.dssmeet.kiosk.BuildConfig
import com.dssmeet.kiosk.core.KioskPermissions
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

    private var inactivityJob: Job? = null
    private val AUTO_LOGOUT_TIMEOUT_MS = BuildConfig.AUTO_LOGOUT_TIMEOUT_MS

    /** Set by KioskNavHost when lobby/call screens are active — timer skips logout. */
    @Volatile var isInCall: Boolean = false

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
            // Only start timer if user is actually logged in
            val hasSession = sessionManager.hasValidSession()
            if (!hasSession) return@launch

            delay(AUTO_LOGOUT_TIMEOUT_MS)
            // Skip logout during active call/lobby
            if (isInCall) return@launch
            // Re-check session before logout (user may have logged in during delay)
            val stillLoggedIn = sessionManager.hasValidSession()
            if (stillLoggedIn) {
                sessionManager.clearAuthOnly()
                recreate()
            }
        }
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
