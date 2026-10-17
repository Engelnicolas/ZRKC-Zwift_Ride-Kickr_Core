package dev.kickzwift.ble.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Rend la main quand [beats] reste muet pendant [timeout] : une manette du Ride se tait
 * parfois sans que le rappel GATT de déconnexion n'arrive (spécification : 3 s sans 0x15).
 * Le délai court dès l'appel. Rend aussi la main si [beats] se termine.
 */
@OptIn(ExperimentalCoroutinesApi::class) // produce
suspend fun awaitSilence(
    beats: Flow<*>,
    timeout: Duration,
) {
    coroutineScope {
        val ticks = produce { beats.collect { send(Unit) } }
        while (true) {
            val got = withTimeoutOrNull(timeout) { ticks.receiveCatching() }
            if (got == null || got.isClosed) break
        }
        ticks.cancel()
    }
}
