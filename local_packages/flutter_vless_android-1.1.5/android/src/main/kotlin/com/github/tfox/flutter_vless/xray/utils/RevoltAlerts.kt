package com.github.tfox.flutter_vless.xray.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

// local_packages/.../xray/utils/RevoltAlerts.kt
//
// The two user-facing session alerts, built in one place because two processes
// post them: XrayVPNService (:RunSoLibXrayDaemon) owns the native deadline and
// can still warn when the UI process is gone, while MainActivity posts the
// "not protected" alert for session ends only Dart can see (cap exhausted,
// server ended the session, VPN error). Both use the same ids, so whichever
// posts second just replaces the first notification instead of stacking.
//
// These are deliberately NOT the foreground-service notification (id 1,
// channel REVOLT_VPN_SERVICE, IMPORTANCE_LOW): an alert the user must notice
// needs its own high-importance channel, and must be dismissible.
object RevoltAlerts {

    const val CHANNEL_ID = "REVOLT_VPN_ALERTS"
    const val WARNING_NOTIFICATION_ID = 2
    const val DISCONNECTED_NOTIFICATION_ID = 3

    // How long before the session deadline the warning fires.
    const val WARNING_LEAD_MS = 5 * 60 * 1000L

    private const val TAG = "RevoltAlerts"
    private const val LAUNCH_REQUEST_CODE = 2001

    fun postExpiryWarning(context: Context) {
        post(
            context,
            WARNING_NOTIFICATION_ID,
            "Revolt VPN disconnects in 5 minutes",
            "Watch an ad to extend your session.",
        )
    }

    fun postDisconnected(context: Context) {
        post(
            context,
            DISCONNECTED_NOTIFICATION_ID,
            "Revolt VPN disconnected",
            "Your traffic is no longer protected. Tap to reconnect.",
        )
    }

    fun cancelExpiryWarning(context: Context) = cancel(context, WARNING_NOTIFICATION_ID)

    fun cancelDisconnected(context: Context) = cancel(context, DISCONNECTED_NOTIFICATION_ID)

    fun cancelAll(context: Context) {
        cancelExpiryWarning(context)
        cancelDisconnected(context)
    }

    private fun cancel(context: Context, id: Int) {
        try {
            manager(context)?.cancel(id)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel alert $id", e)
        }
    }

    private fun post(context: Context, id: Int, title: String, text: String) {
        if (!canPost(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; dropping alert $id")
            return
        }

        try {
            val manager = manager(context) ?: return
            ensureChannel(manager)

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)
            }

            val icon = context.resources
                .getIdentifier("notification_icon", "drawable", context.packageName)
                .takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

            builder
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(icon)
                .setAutoCancel(true)
                .setOngoing(false)

            launchIntent(context)?.let { builder.setContentIntent(it) }

            manager.notify(id, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post alert $id", e)
        }
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun manager(context: Context): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    // Importance is fixed when the channel is first created, so this asks for
    // HIGH up front — a session alert the user never sees is the whole bug
    // these notifications exist to fix.
    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Session alerts",
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.description = "Warns before a session ends and when protection stops."
        manager.createNotificationChannel(channel)
    }

    private fun launchIntent(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_NEW_TASK
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(context, LAUNCH_REQUEST_CODE, intent, flags)
    }
}
