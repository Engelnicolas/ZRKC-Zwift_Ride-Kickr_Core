package dev.kickzwift.app

import android.app.Application
import android.content.Context
import android.util.Log
import dev.kickzwift.app.data.TrustedDevices
import dev.kickzwift.ble.link.LinkSupervisor
import dev.kickzwift.ble.link.ReconnectPolicy
import dev.kickzwift.ble.nordic.GattLinkFactory
import dev.kickzwift.ble.scan.DeviceScanner

/** Injection manuelle (spécification, « Environnement ») : un objet de chaque, pour toute l'app. */
class AppContainer(
    context: Context,
) {
    val trustedDevices = TrustedDevices(context)
    val scanner = DeviceScanner()

    /** Liaisons aux appareils de confiance ; c'est RideService qui les fait tourner. */
    val links =
        LinkSupervisor(
            // Sans limite de reprises : la règle pendant une sortie.
            policy = ReconnectPolicy(),
            newLink = GattLinkFactory(context)::create,
            log = { Log.i(TAG, it) },
        )

    companion object {
        const val TAG = "KickZwift"
    }
}

class KickZwiftApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as KickZwiftApp).container
