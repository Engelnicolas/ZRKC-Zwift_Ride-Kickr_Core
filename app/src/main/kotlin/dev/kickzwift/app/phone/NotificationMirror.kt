package dev.kickzwift.app.phone

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Une notification d'une autre app, telle que la montre l'écran Sortie. Jamais journalisée. */
data class PhoneNotice(
    /** Clé Android : une mise à jour remplace la précédente. */
    val key: String,
    val app: String?,
    val title: String?,
    val text: String?,
)

/**
 * Pont entre [MediaAccessService], lié par le système, et l'écran Sortie : pendant la sortie
 * seulement ([active]), les notifications qui auraient surgi en bandeau y sont recopiées.
 */
object NotificationMirror {
    @Volatile
    var active = false

    private val mutablePosted = MutableSharedFlow<PhoneNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val posted: SharedFlow<PhoneNotice> = mutablePosted.asSharedFlow()

    fun post(notice: PhoneNotice) {
        mutablePosted.tryEmit(notice)
    }
}
