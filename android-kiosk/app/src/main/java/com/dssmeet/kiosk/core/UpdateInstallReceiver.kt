package com.dssmeet.kiosk.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Receives the outcome of a PackageInstaller session (the in-app update).
 * On success the app restarts itself, so the freshly installed build is the
 * one that is running.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        if (status == PackageInstaller.STATUS_SUCCESS) {
            Logger.i("UpdateInstallReceiver: update installed — restarting the app")
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (launch != null) context.startActivity(launch)
            return
        }

        // The session is waiting on the user (silent install was not allowed).
        // Hand it to the system installer instead of swallowing the status —
        // otherwise the kiosk just sits on "Installing…" forever.
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Logger.i("UpdateInstallReceiver: install needs user action — opening the system installer")
            @Suppress("DEPRECATION")
            val pending = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (pending != null) {
                pending.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(pending) }
                    .onFailure { Logger.w("UpdateInstallReceiver: could not open the installer: ${it.message}") }
            }
            return
        }

        Logger.w("UpdateInstallReceiver: install failed (status=$status) $message")
        // Surface the failure on the install screen — without this the
        // progress UI never learns the install died.
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            UpdateEntryPoint::class.java
        )
        entryPoint.updateManager().setInstallError(
            message?.takeIf { it.isNotBlank() } ?: "Install failed (status $status)"
        )
    }

    /** Hilt access to the app-scoped UpdateManager without codegen in a receiver. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UpdateEntryPoint {
        fun updateManager(): UpdateManager
    }

    companion object {
        const val ACTION = "com.dssmeet.kiosk.action.UPDATE_INSTALL_STATUS"
    }
}
