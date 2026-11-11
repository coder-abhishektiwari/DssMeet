package com.dssmeet.kiosk.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-wide signal that the USER session is gone.
 *
 * Before this existed the kiosk silently dropped its tokens when a refresh
 * was rejected and left the inmate staring at a screen full of
 * "Unauthorized access" errors — no logout, no way back to the login screen.
 * Every definitive auth rejection (expired access token with a dead refresh
 * token, revoked session, inactivity timeout) now announces itself here and
 * the navigation graph bounces back to the login screen with a reason.
 */
object SessionEvents {

    private val _expired = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val expired: SharedFlow<String> = _expired.asSharedFlow()

    /**
     * Last reason, kept so a screen that is composed AFTER the event (the
     * login screen is rebuilt from scratch) can still show it.
     */
    @Volatile
    var lastReason: String? = null
        private set

    fun notifyExpired(reason: String) {
        lastReason = reason
        _expired.tryEmit(reason)
    }

    /** Read-and-clear, so the same reason is not shown twice. */
    fun consumeReason(): String? {
        val reason = lastReason
        lastReason = null
        return reason
    }
}
