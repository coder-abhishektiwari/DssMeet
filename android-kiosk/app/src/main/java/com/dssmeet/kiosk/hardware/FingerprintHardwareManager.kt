package com.dssmeet.kiosk.hardware

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Base64
import com.dssmeet.kiosk.core.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import SecuGen.FDxSDKPro.JSGFPLib
import SecuGen.FDxSDKPro.SGFDxDeviceName
import SecuGen.FDxSDKPro.SGFDxErrorCode
import SecuGen.FDxSDKPro.SGFDxTemplateFormat
import SecuGen.FDxSDKPro.SGFingerInfo
import javax.inject.Inject
import javax.inject.Singleton

private const val ACTION_USB_PERMISSION = "com.dssmeet.kiosk.USB_PERMISSION"

/** Live state of one fingerprint-capture session. */
sealed class FingerprintCaptureState {
    /** No capture session running. */
    data object Idle : FingerprintCaptureState()

    /** Session active, waiting for a finger on the scanner. */
    data object Searching : FingerprintCaptureState()

    /** A finger was captured — session code must consume it (acknowledgeCapture). */
    data class Captured(val template: String) : FingerprintCaptureState()

    /** The scanner reported a fault — UI offers a retry (or RFID/PIN). */
    data class Failed(val message: String) : FingerprintCaptureState()
}

/**
 * Handles genuine USB discovery and capture for the SecuGen Hamster Pro 20
 * (HU20-AP) fingerprint scanner through the FDx SDK Pro for Android.
 *
 * >>> HARDWARE SWAP: change ONLY this file plus res/xml/device_filter.xml.
 * The VID/PID check lives in [isPotentialScanner], the capture protocol in
 * [runCaptureLoop], and results are pushed with [onTemplateCaptured] /
 * [onCaptureError]. Login (LoginViewModel) and registration
 * (AddPrisonerScreen / BiometricRegistrationScreen) already consume
 * [captureState], and matching against enrolled templates is done separately
 * by SecuGenMatcher.
 */
@Singleton
class FingerprintHardwareManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _connectedScanner = MutableStateFlow<UsbDevice?>(null)
    val connectedScanner = _connectedScanner.asStateFlow()

    private val _hasPermission = MutableStateFlow(false)
    val hasPermission = _hasPermission.asStateFlow()

    private val _captureState = MutableStateFlow<FingerprintCaptureState>(FingerprintCaptureState.Idle)
    val captureState = _captureState.asStateFlow()

    private val _fingerDetected = MutableStateFlow(false)
    val fingerDetected = _fingerDetected.asStateFlow()

    /**
     * Tag of the screen that currently owns the scanner ("Login",
     * "AddPrisoner", "InmateBiometrics"). A screen can only stop the
     * session it owns — a late dispose from a screen that already lost the
     * session can never kill the next screen's live capture.
     */
    @Volatile
    private var sessionOwner: String? = null

    // ---- SecuGen driver session ---------------------------------------
    // The vendor SDK is driven from a single background thread so the port
    // lifecycle stays strictly paired: opened only inside the loop, released
    // only in its finally. `driverGeneration` is bumped on every stop so a
    // superseded loop exits on its own, and the NEXT session joins the old
    // thread before opening — that is what guarantees the port is free.
    @Volatile
    private var driverGeneration = 0
    @Volatile
    private var driverRunning = false
    private var driverThread: Thread? = null
    private val driverLock = Any()

    private companion object {
        /** Quality floor SecuGen recommends for registration-grade captures. */
        const val CAPTURE_QUALITY = 50L
        /** How long one GetImageEx waits for a finger before returning. */
        const val CAPTURE_TIMEOUT_MS = 10_000L
        /** Upper bound on waiting for a superseded session to release the port. */
        const val PORT_RELEASE_WAIT_MS = 15_000L
        /** Backoff after a capture fault so a dead reader cannot busy-spin. */
        const val FAULT_BACKOFF_MS = 150L
        /** SecuGen's USB vendor id — every reader they make uses it. */
        const val SECUGEN_VENDOR_ID = 0x1162
        /** SG400 templates are a fixed 400-byte buffer. */
        const val SG400_TEMPLATE_BYTES = 400
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.apply {
                            Logger.i("USB Permission GRANTED for $deviceName")
                            _hasPermission.value = true
                        }
                        // The live session was waiting on this — open the port now.
                        syncDriver()
                    } else {
                        Logger.w("USB Permission DENIED for device")
                        _hasPermission.value = false
                        syncDriver()
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
                scanForDevices()
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                scanForDevices()
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }

        scanForDevices()
    }

    /**
     * Performs a genuine scan of all physical USB devices and reconciles the
     * vendor driver with whatever is currently attached / permitted.
     */
    fun scanForDevices() {
        val deviceList = usbManager.deviceList
        Logger.d("FingerprintHardwareManager: Scanning ${deviceList.size} USB devices.")

        // SecuGen readers all sit behind vendor id 0x1162 (see device_filter.xml).
        val potentialScanner = deviceList.values.firstOrNull { device ->
            isPotentialScanner(device)
        }

        _connectedScanner.value = potentialScanner
        if (potentialScanner != null) {
            Logger.i("Detected potential scanner: VID=${potentialScanner.vendorId} PID=${potentialScanner.productId}")
            _hasPermission.value = usbManager.hasPermission(potentialScanner)
        } else {
            _hasPermission.value = false
        }
        syncDriver()
    }

    /**
     * Requests system permission to access the USB device.
     */
    fun requestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            _hasPermission.value = true
            return
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * Begin (or resume) a capture session owned by [owner] (a stable screen
     * tag). If another screen still owns the scanner — or a stale session
     * was left running — the old driver is force-stopped and the USB/serial
     * port is closed FIRST, then a clean, fresh session starts for [owner].
     * Safe to call repeatedly from the same screen.
     */
    fun startCapture(owner: String) {
        val previousOwner = sessionOwner
        if (previousOwner != null && previousOwner != owner) {
            Logger.i("FingerprintHardwareManager: taking over from '$previousOwner' for '$owner' — releasing stale session")
            stopCaptureDriver()
            _captureState.value = FingerprintCaptureState.Idle
        } else if (_captureState.value is FingerprintCaptureState.Searching) {
            return
        }
        sessionOwner = owner
        _captureState.value = FingerprintCaptureState.Searching
        _fingerDetected.value = false
        Logger.i("FingerprintHardwareManager: capture session started (owner=$owner)")
        scanForDevices()
        startCaptureDriver()
    }

    /**
     * End the capture session. Only stops if [owner] still owns it — a
     * delayed [stopCapture] from a screen that already yielded the scanner
     * (e.g. its ViewModel's onCleared firing after the next screen mounted)
     * becomes a no-op and cannot wedge the new screen's session.
     */
    fun stopCapture(owner: String? = null) {
        val currentOwner = sessionOwner
        if (owner != null && currentOwner != null && currentOwner != owner) {
            Logger.d("FingerprintHardwareManager: stopCapture('$owner') ignored — session owned by '$currentOwner'")
            return
        }
        stopCaptureDriver()
        sessionOwner = null
        _captureState.value = FingerprintCaptureState.Idle
        _fingerDetected.value = false
    }

    /**
     * Re-arm the session after a capture was consumed. Keeps [captureState]
     * emitting so the next finger produces a fresh Captured event.
     */
    fun acknowledgeCapture() {
        if (_captureState.value is FingerprintCaptureState.Captured) {
            _captureState.value = FingerprintCaptureState.Searching
        }
    }

    /** Call from the driver whenever a finger template/ID is captured. */
    fun onTemplateCaptured(template: String) {
        val cleaned = template.trim()
        if (cleaned.isEmpty()) return
        Logger.i("FingerprintHardwareManager: template captured")
        _fingerDetected.value = false
        _captureState.value = FingerprintCaptureState.Captured(cleaned)
    }

    /** Call from the driver on a capture fault — the UI offers a retry. */
    fun onCaptureError(message: String) {
        Logger.w("FingerprintHardwareManager: capture error: $message")
        _fingerDetected.value = false
        _captureState.value = FingerprintCaptureState.Failed(message)
    }

    /**
     * Block until the capture driver has released the USB port (or [timeoutMs]
     * elapses). Called by SecuGenMatcher before it loads the algorithm module,
     * so two JSGFPLib instances never drive the SDK at the same time — a
     * capture loop can sit inside GetImageEx for up to CAPTURE_TIMEOUT_MS
     * after stopCapture() returns.
     *
     * @return true when the port is free (no driver, or it has finished).
     */
    fun awaitDriverIdle(timeoutMs: Long = PORT_RELEASE_WAIT_MS): Boolean {
        val thread = synchronized(driverLock) { driverThread } ?: return true
        if (thread === Thread.currentThread()) return true
        return try {
            thread.join(timeoutMs)
            !thread.isAlive
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun isPotentialScanner(device: UsbDevice): Boolean {
        // Only SecuGen's own vendor id counts. Matching on "any non-zero VID"
        // used to report the keyboard/RFID wedge as a fingerprint scanner.
        return device.vendorId == SECUGEN_VENDOR_ID
    }

    private fun deviceNameFor(productId: Int): Long? = when (productId) {
        0x1000 -> SGFDxDeviceName.SG_DEV_FDU03
        0x2000 -> SGFDxDeviceName.SG_DEV_FDU04
        0x2200, 0x23BA -> SGFDxDeviceName.SG_DEV_FDU05
        0x2201, 0x2301 -> SGFDxDeviceName.SG_DEV_FDU06
        0x2203 -> SGFDxDeviceName.SG_DEV_FDU07
        0x2240 -> SGFDxDeviceName.SG_DEV_FDU08
        0x2220 -> SGFDxDeviceName.SG_DEV_FDU08P
        0x2360 -> SGFDxDeviceName.SG_DEV_FDU08A
        0x2410 -> SGFDxDeviceName.SG_DEV_FDU09A
        0x2500 -> SGFDxDeviceName.SG_DEV_FDU10A
        else -> null
    }

    private fun initErrorMessage(err: Long, connectedPid: Int?): String = when (err) {
        SGFDxErrorCode.SGFDX_ERROR_DEVICE_NOT_FOUND -> "Fingerprint scanner not detected. Connect the USB reader and try again."
        SGFDxErrorCode.SGFDX_ERROR_UNSUPPORTED_DEV -> if (connectedPid == null) {
            "Fingerprint scanner not detected. Connect the USB reader and try again."
        } else {
            "Connected fingerprint scanner model is not supported (PID 0x${connectedPid.toString(16).uppercase()})."
        }
        SGFDxErrorCode.SGFDX_ERROR_DRVLOAD_FAILED -> "Fingerprint scanner driver could not load. Reconnect the reader and try again."
        SGFDxErrorCode.SGFDX_ERROR_DLLLOAD_FAILED,
        SGFDxErrorCode.SGFDX_ERROR_DLLLOAD_FAILED_DRV,
        SGFDxErrorCode.SGFDX_ERROR_DLLLOAD_FAILED_ALG,
        SGFDxErrorCode.SGFDX_ERROR_DLLLOAD_FAILED_WSQ -> "Fingerprint scanner SDK files are missing. Reinstall the kiosk app."
        else -> "Fingerprint scanner could not be initialised (error $err)."
    }

    // ================================================================
    // SECUGEN DRIVER (FDx SDK Pro for Android) — Hamster Pro 20 / HU20-AP.
    //
    // LIFECYCLE CONTRACT (strict): the USB port is opened ONLY inside
    // runCaptureLoop and released ONLY in its finally block. stopCaptureDriver
    // bumps a generation counter instead of touching the port; the loop
    // notices, unwinds and closes. The NEXT startCaptureDriver joins that
    // thread before opening, so a port left open here can never deadlock a
    // later registration screen.
    // ================================================================

    /**
     * Reconcile the vendor driver with the current device, USB permission and
     * session state. Safe to call from anywhere, any number of times.
     *
     * A missing scanner or a missing USB permission is deliberately NOT turned
     * into [FingerprintCaptureState.Failed] — the UI already branches on
     * `connectedScanner` / `usbPermissionGranted` and shows the right prompt
     * ("reader not detected" / "Allow USB Access"). Failed is reserved for
     * real capture faults.
     */
    private fun syncDriver() {
        val sessionLive = sessionOwner != null &&
            _captureState.value is FingerprintCaptureState.Searching
        val ready = sessionLive && _connectedScanner.value != null && _hasPermission.value
        if (ready) startCaptureDriver() else stopCaptureDriver()
    }

    private fun startCaptureDriver() {
        synchronized(driverLock) {
            if (driverRunning) return
            driverRunning = true
            val generation = driverGeneration
            val previous = driverThread
            val thread = Thread({
                // A superseded session may still be inside GetImageEx holding
                // the port — let it unwind before we open our own.
                try {
                    previous?.join(PORT_RELEASE_WAIT_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                if (generation != driverGeneration) return@Thread
                runCaptureLoop(generation)
            }, "secugen-capture")
            thread.isDaemon = true
            driverThread = thread
            thread.start()
            Logger.i("FingerprintHardwareManager: SecuGen driver session started (generation=$generation)")
        }
    }

    private fun stopCaptureDriver() {
        synchronized(driverLock) {
            driverGeneration++
            driverRunning = false
            // driverThread is kept on purpose: the next startCaptureDriver
            // joins it, which is what proves the port was released.
            Logger.d("FingerprintHardwareManager: SecuGen driver stop requested (generation=$driverGeneration)")
        }
    }

    private fun runCaptureLoop(generation: Int) {
        var sg: JSGFPLib? = null
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            sg = JSGFPLib(context, usbManager)
            Logger.i("FingerprintHardwareManager: jnisgfplib ${sg.Version()}")

            val connectedPid = _connectedScanner.value?.productId
            val deviceName = connectedPid?.let(::deviceNameFor)
            if (connectedPid != null && deviceName == null) {
                onCaptureError("Connected fingerprint scanner model is not supported (PID 0x${connectedPid.toString(16).uppercase()}).")
                return
            }

            var err = sg.Init(deviceName ?: SGFDxDeviceName.SG_DEV_AUTO)
            if (err != SGFDxErrorCode.SGFDX_ERROR_NONE.toLong()) {
                if (generation == driverGeneration) {
                    onCaptureError(initErrorMessage(err, connectedPid))
                }
                return
            }
            sg.SetTemplateFormat(SGFDxTemplateFormat.TEMPLATE_FORMAT_SG400)

            err = sg.OpenDevice(0)
            if (err != SGFDxErrorCode.SGFDX_ERROR_NONE.toLong()) {
                if (generation == driverGeneration) {
                    onCaptureError("Fingerprint scanner could not be opened (error $err)")
                }
                return
            }
            Logger.i("FingerprintHardwareManager: SecuGen reader open")

            // Sized for the largest sensor the SDK supports (the U20 in the
            // HU20-AP is 300x400) so the buffer never depends on reading
            // GetDeviceInfo fields, whose names differ between SDK releases.
            val image = ByteArray(
                JSGFPLib.MAX_IMAGE_WIDTH_ALL_DEVICES.toInt() *
                    JSGFPLib.MAX_IMAGE_HEIGHT_ALL_DEVICES.toInt()
            )
            val maxTemplateSize = IntArray(1)
            sg.GetMaxTemplateSize(maxTemplateSize)
            // SG400 templates are a fixed 400 bytes; only trust the SDK when it
            // actually told us something.
            val template = ByteArray(maxTemplateSize[0].takeIf { it > 0 } ?: SG400_TEMPLATE_BYTES)
            val fingerInfo = SGFingerInfo()

            val fingerPresent = BooleanArray(1)
            while (generation == driverGeneration) {
                if (_connectedScanner.value == null) break // unplugged

                val presenceErr = sg.FingerPresent(fingerPresent)
                _fingerDetected.value = presenceErr == SGFDxErrorCode.SGFDX_ERROR_NONE.toLong() && fingerPresent[0]

                err = sg.GetImageEx(image, CAPTURE_TIMEOUT_MS, CAPTURE_QUALITY)
                if (err != SGFDxErrorCode.SGFDX_ERROR_NONE.toLong()) {
                    // Timeout / no finger / quality too low all mean "keep
                    // waiting"; the backoff stops a dead reader busy-spinning.
                    Thread.sleep(FAULT_BACKOFF_MS)
                    continue
                }

                err = sg.CreateTemplate(fingerInfo, image, template)
                if (err != SGFDxErrorCode.SGFDX_ERROR_NONE.toLong()) {
                    Logger.w("FingerprintHardwareManager: CreateTemplate failed ($err) — try the finger again")
                    Thread.sleep(FAULT_BACKOFF_MS)
                    continue
                }

                onTemplateCaptured(Base64.encodeToString(template, Base64.NO_WRAP))
                // Hold until the session consumes it, so a second finger cannot
                // overwrite a Captured nobody has acknowledged yet.
                while (
                    generation == driverGeneration &&
                    _captureState.value is FingerprintCaptureState.Captured
                ) {
                    Thread.sleep(80)
                }
            }
        } catch (t: Throwable) {
            Logger.e("FingerprintHardwareManager: SecuGen driver died: ${t.message}", t)
            if (generation == driverGeneration) {
                val message = t.message ?: "unknown"
                val friendly = when {
                    t is NoClassDefFoundError || message.contains("Failed resolution of") ->
                        "Fingerprint scanner driver files are missing. Reinstall the kiosk app."
                    message.contains("SgSetSensorResetInit") ->
                        "Fingerprint scanner camera could not be opened. Reconnect the reader and try again."
                    else -> "Fingerprint scanner error: $message"
                }
                onCaptureError(friendly)
            }
        } finally {
            if (sg != null) {
                try {
                    sg.CloseDevice()
                } catch (t: Throwable) {
                    Logger.w("FingerprintHardwareManager: CloseDevice failed: ${t.message}")
                }
                try {
                    sg.Close()
                } catch (t: Throwable) {
                    Logger.w("FingerprintHardwareManager: Close failed: ${t.message}")
                }
                Logger.i("FingerprintHardwareManager: SecuGen port released (generation=$generation)")
            }
            _fingerDetected.value = false
        }
    }
}
