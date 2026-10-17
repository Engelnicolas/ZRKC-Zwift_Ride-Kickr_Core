package dev.kickzwift.app.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.kickzwift.app.AppContainer
import dev.kickzwift.app.container
import dev.kickzwift.protocol.LinkState
import dev.kickzwift.protocol.session.RidePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Service de premier plan de type connectedDevice : il porte les liaisons BLE et la sortie,
 * qui tiennent ainsi écran éteint ou app en arrière-plan. Notification permanente avec
 * Pause et Arrêter ; Arrêter remet le trainer à plat avant de tout fermer.
 */
class RideService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val c = container
        val stopping = intent?.action == ACTION_STOP
        if (intent?.action == ACTION_PAUSE) togglePause(c)
        val started = !stopping && startInForeground(c)
        when {
            stopping -> shutdown(c)
            !started -> stopSelf()
            running == null -> running = scope.launch { run(c) }
        }
        // Relancé par Android s'il est tué : les liaisons repartent des appareils mémorisés.
        return if (started) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        // Annule chaque liaison, qui ferme alors sa connexion GATT.
        scope.cancel()
        super.onDestroy()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun run(c: AppContainer) {
        kotlinx.coroutines.coroutineScope {
            // Le repli FTMS change de protocole : toutes les liaisons repartent.
            launch {
                c.settings
                    .map { it.ftmsFallback }
                    .distinctUntilChanged()
                    .collectLatest { c.links.run(c.trusted) }
            }
            launch { c.session.run(c.sessionDevices()) }
            launch { followInNotification(c) }
        }
    }

    private fun togglePause(c: AppContainer) {
        when (c.session.state.value.snapshot.phase) {
            RidePhase.RIDING -> c.session.pause()
            RidePhase.PAUSED -> c.session.resume()
            else -> Unit
        }
    }

    private fun shutdown(c: AppContainer) {
        scope.launch {
            val phase = c.session.state.value.snapshot.phase
            if (phase == RidePhase.RIDING || phase == RidePhase.PAUSED) c.session.finish()
            c.session.emergencyNeutral()
            c.log.log("service arrêté depuis la notification")
            stopSelf()
        }
    }

    private fun startInForeground(c: AppContainer): Boolean =
        try {
            ServiceCompat.startForeground(
                this,
                RideNotification.ID,
                RideNotification.build(this, RideNotification.Content.of(c.session.state.value.snapshot, 0, 0)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            c.log.log("service refusé au premier plan : $e")
            false
        } catch (e: SecurityException) {
            // Type connectedDevice sans BLUETOOTH_CONNECT accordée.
            c.log.log("service refusé, permission Bluetooth manquante : $e")
            false
        }

    private suspend fun followInNotification(c: AppContainer) {
        val manager = getSystemService(NotificationManager::class.java)
        combine(c.session.state, c.links.states) { ui, states ->
            RideNotification.Content.of(ui.snapshot, states.count { it.value == LinkState.READY }, states.size)
        }.distinctUntilChanged()
            .collect { content ->
                if (manager.areNotificationsEnabled()) manager.notify(RideNotification.ID, RideNotification.build(this, content))
            }
    }

    companion object {
        const val ACTION_PAUSE = "dev.kickzwift.app.PAUSE"
        const val ACTION_STOP = "dev.kickzwift.app.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RideService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_STOP))
        }
    }
}
