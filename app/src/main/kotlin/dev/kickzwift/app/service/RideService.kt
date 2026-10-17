package dev.kickzwift.app.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.kickzwift.app.AppContainer
import dev.kickzwift.app.container
import dev.kickzwift.protocol.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Service de premier plan de type connectedDevice : il porte les liaisons BLE, qui tiennent
 * ainsi écran éteint ou app en arrière-plan. Lot 4 : liaisons aux appareils de confiance et
 * notification minimale ; la sortie s'y ajoutera au lot 7.
 */
class RideService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var links: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (!startInForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (links == null) {
            val container = container
            links = scope.launch { container.links.run(container.trustedDevices.devices) }
            scope.launch { followInNotification(container) }
        }
        // Relancé par Android s'il est tué : les liaisons repartent des appareils mémorisés.
        return START_STICKY
    }

    override fun onDestroy() {
        // Annule chaque liaison, qui ferme alors sa connexion GATT.
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(): Boolean =
        try {
            ServiceCompat.startForeground(
                this,
                RideNotification.ID,
                RideNotification.build(this, ready = 0, total = 0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(AppContainer.TAG, "service refusé au premier plan", e)
            false
        } catch (e: SecurityException) {
            // Type connectedDevice sans BLUETOOTH_CONNECT accordée.
            Log.w(AppContainer.TAG, "service refusé, permission Bluetooth manquante", e)
            false
        }

    private suspend fun followInNotification(container: AppContainer) {
        val manager = getSystemService(NotificationManager::class.java)
        container.links.states
            .map { states -> states.count { it.value == LinkState.READY } to states.size }
            .distinctUntilChanged()
            .collect { (ready, total) ->
                if (manager.areNotificationsEnabled()) {
                    manager.notify(RideNotification.ID, RideNotification.build(this, ready, total))
                }
            }
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RideService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RideService::class.java))
        }
    }
}
