package com.brainrot.pet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.brainrot.pet.R
import com.brainrot.pet.data.Permissions
import com.brainrot.pet.ui.MainActivity

object Notifications {
    const val CHANNEL_STATUS = "brain_status"
    const val CHANNEL_NUDGES = "brain_nudges"
    const val ID_STATUS = 1
    private const val ID_NUDGE = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_status_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_NUDGES, context.getString(R.string.channel_nudges), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_nudges_desc)
            },
        )
    }

    fun status(context: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun updateStatus(context: Context, title: String, text: String) {
        if (!Permissions.hasNotificationPermission(context)) return
        context.getSystemService(NotificationManager::class.java)?.notify(ID_STATUS, status(context, title, text))
    }

    /** Fallback for limit nudges and blocks when the overlay cannot be drawn. */
    fun nudge(context: Context, title: String, text: String) {
        if (!Permissions.hasNotificationPermission(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_NUDGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(ID_NUDGE, n)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
