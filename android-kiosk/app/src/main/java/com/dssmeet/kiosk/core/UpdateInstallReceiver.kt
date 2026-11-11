package com.dssmeet.kiosk.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

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
        } else {
            Logger.w("UpdateInstallReceiver: install failed (status=$status) $message")
        }
    }

    companion object {
        const val ACTION = "com.dssmeet.kiosk.action.UPDATE_INSTALL_STATUS"
    }
}
