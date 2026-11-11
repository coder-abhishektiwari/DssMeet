package com.dssmeet.kiosk.core

import com.dssmeet.kiosk.BuildConfig
import com.dssmeet.kiosk.models.appupdate.AppUpdateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kiosk update state.
 *
 * The manifest arrives with the heartbeat the app already sends every 30
 * seconds — an update check therefore costs NO extra request. A release with a
 * HIGHER versionCode than the running build is either mandatory (the screen
 * is blocked until the new APK is installed) or optional (a dismissible
 * notice).
 */
@Singleton
class UpdateManager @Inject constructor() {

    sealed interface State {
        /** Nothing known yet (first heartbeat not received). */
        data object Unknown : State
        /** Running the newest published build. */
        data object UpToDate : State
        /** A newer build exists — the user may ignore it. */
        data class Optional(val info: AppUpdateInfo) : State
        /** A newer build exists and the kiosk must not keep running on this one. */
        data class Mandatory(val info: AppUpdateInfo) : State
    }

    private val _state = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Feed the manifest from the heartbeat response. */
    fun apply(info: AppUpdateInfo?) {
        _state.value = evaluate(info)
    }

    fun evaluate(info: AppUpdateInfo?): State {
        if (info == null) return State.UpToDate
        val latest = info.versionCode ?: return State.UpToDate
        // The manifest is for a different app/platform — ignore it.
        if (info.app != null && info.app != "kiosk") return State.UpToDate
        if (info.platform != null && info.platform != "android") return State.UpToDate
        if (latest <= BuildConfig.VERSION_CODE) return State.UpToDate
        return if (info.mandatory) State.Mandatory(info) else State.Optional(info)
    }

    /** The user dismissed an optional update. */
    fun dismissOptional() {
        if (_state.value is State.Optional) _state.value = State.UpToDate
    }
}
