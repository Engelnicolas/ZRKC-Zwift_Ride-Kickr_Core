package dev.kickzwift.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.kickzwift.app.MainActivity
import dev.kickzwift.app.R

/**
 * Notification permanente du service, canal « Sortie » à importance basse. Lot 4 : état des
 * liaisons seulement ; vitesse, puissance et actions Pause et Arrêter viendront au lot 9.
 */
object RideNotification {
    const val ID = 1
    private const val CHANNEL = "ride"

    fun build(
        context: Context,
        ready: Int,
        total: Int,
    ): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_ride), NotificationManager.IMPORTANCE_LOW),
        )
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val text =
            if (total == 0) {
                context.getString(R.string.notification_no_device)
            } else {
                context.getString(R.string.notification_links, ready, total)
            }
        return NotificationCompat
            .Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
