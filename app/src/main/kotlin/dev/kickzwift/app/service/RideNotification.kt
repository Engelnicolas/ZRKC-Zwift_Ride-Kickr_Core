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
import dev.kickzwift.protocol.session.RidePhase
import dev.kickzwift.protocol.session.SessionSnapshot

/**
 * Notification permanente du service, canal « Sortie » à importance basse : vitesse engagée,
 * puissance et état des connexions, actions Pause et Arrêter (spécification, « Interface »).
 */
object RideNotification {
    const val ID = 1
    private const val CHANNEL = "ride"
    private const val REQUEST_OPEN = 0
    private const val REQUEST_PAUSE = 1
    private const val REQUEST_STOP = 2

    /** Ce qu'affiche la notification ; comparée pour ne la redessiner qu'en cas de changement. */
    data class Content(
        val phase: RidePhase,
        val gear: Int,
        val gearCount: Int,
        val powerW: Int?,
        val ready: Int,
        val total: Int,
    ) {
        companion object {
            fun of(
                s: SessionSnapshot,
                ready: Int,
                total: Int,
            ) = Content(s.phase, s.gear, s.gearCount, s.power3sW, ready, total)
        }
    }

    fun build(
        context: Context,
        content: Content,
    ): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_ride), NotificationManager.IMPORTANCE_LOW),
        )
        val open =
            PendingIntent.getActivity(
                context,
                REQUEST_OPEN,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val links = context.getString(R.string.notification_links, content.ready, content.total)
        val riding = content.phase == RidePhase.RIDING || content.phase == RidePhase.PAUSED
        val title =
            if (riding) {
                context.getString(R.string.notification_ride, content.gear, content.gearCount, content.powerW?.toString() ?: "—")
            } else {
                context.getString(R.string.notification_title)
            }
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(if (content.phase == RidePhase.PAUSED) context.getString(R.string.notification_paused, links) else links)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (riding) {
            val label = if (content.phase == RidePhase.PAUSED) R.string.action_resume else R.string.action_pause
            builder.addAction(0, context.getString(label), service(context, RideService.ACTION_PAUSE, REQUEST_PAUSE))
        }
        builder.addAction(0, context.getString(R.string.action_stop), service(context, RideService.ACTION_STOP, REQUEST_STOP))
        return builder.build()
    }

    private fun service(
        context: Context,
        action: String,
        request: Int,
    ): PendingIntent =
        PendingIntent.getService(
            context,
            request,
            Intent(context, RideService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )
}
