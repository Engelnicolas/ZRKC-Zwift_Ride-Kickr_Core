package dev.kickzwift.app

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import dev.kickzwift.app.data.Activity
import dev.kickzwift.app.data.ActivityStore
import dev.kickzwift.app.data.MemoryStore
import dev.kickzwift.app.data.SettingsStore
import dev.kickzwift.app.data.TrustedDevices
import dev.kickzwift.app.log.FileLog
import dev.kickzwift.app.phone.AndroidPhoneActions
import dev.kickzwift.app.phone.MediaMonitor
import dev.kickzwift.app.phone.PhoneActions
import dev.kickzwift.app.route.RouteLibrary
import dev.kickzwift.app.session.SessionController
import dev.kickzwift.app.session.SessionDevices
import dev.kickzwift.app.session.SessionEnvironment
import dev.kickzwift.ble.device.ControllerConfig
import dev.kickzwift.ble.device.DeviceHub
import dev.kickzwift.ble.link.LinkSupervisor
import dev.kickzwift.ble.link.ReconnectPolicy
import dev.kickzwift.ble.scan.DeviceScanner
import dev.kickzwift.protocol.device.DeviceKind
import dev.kickzwift.protocol.device.KnownDevice
import dev.kickzwift.protocol.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Injection manuelle (spécification, « Architecture ») : un objet de chaque, pour toute l'app. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppContainer(
    context: Context,
) {
    private val context = context.applicationContext

    /** Portée de l'app entière : réglages, état de la sortie. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val log = FileLog(File(this.context.filesDir, "logs"))

    val settingsStore = SettingsStore(this.context)
    val settings: StateFlow<AppSettings> = settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val memory = MemoryStore(this.context)
    val trustedDevices = TrustedDevices(this.context)
    val trusted: StateFlow<List<KnownDevice>> = trustedDevices.devices.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val scanner = DeviceScanner()
    val hub = DeviceHub(this.context, ftmsFallback = { settings.value.ftmsFallback }, log = log::log)

    /** Liaisons aux appareils de confiance ; c'est RideService qui les fait tourner. */
    val links = LinkSupervisor(ReconnectPolicy(), hub::newLink, log::log)

    val controllerConfig: StateFlow<ControllerConfig> =
        settings
            .map { ControllerConfig(it.timing, it.paddlePress, it.paddleRelease, it.mapping) }
            .stateIn(scope, SharingStarted.Eagerly, ControllerConfig())

    val routes = RouteLibrary(this.context, memory)

    /** Historique des sorties terminées. */
    val activities = ActivityStore(this.context)

    /** Commentaire d'une activité, écrit dans la portée de l'app : il aboutit même si l'écran se ferme. */
    fun saveComment(
        id: Long,
        text: String,
    ) = writeActivity("commentaire non enregistré") { activities.comment(id, text) }

    fun deleteActivity(id: Long) = writeActivity("activité non supprimée") { activities.delete(id) }

    private fun writeActivity(
        failure: String,
        block: suspend () -> Unit,
    ) {
        scope.launch {
            try {
                block()
            } catch (e: SQLiteException) {
                log.log("$failure : $e")
            }
        }
    }

    /** Fichiers à partager (FIT, journal), servis par le FileProvider de l'app. */
    val exportsDir = File(this.context.cacheDir, "exports")

    private val phone: PhoneActions = AndroidPhoneActions(this.context)

    /** Lecture en cours sur le téléphone, pour le lecteur de l'écran Sortie. */
    val media = MediaMonitor(this.context)

    val session =
        SessionController(
            env = Environment(),
            scope = scope,
            dispatcher = Dispatchers.Default.limitedParallelism(1),
        )

    /** Manettes et trainer des appareils de confiance, mis à jour quand la liste change. */
    fun sessionDevices(): Flow<SessionDevices> =
        trusted.map { list ->
            SessionDevices(
                controllers = list.filter { it.kind == DeviceKind.RIDE }.map { hub.controller(it, links, controllerConfig) },
                trainer = list.firstOrNull { it.kind == DeviceKind.KICKR }?.let { hub.trainer(it, links) },
                heartRate = list.firstOrNull { it.kind == DeviceKind.HEART_RATE }?.let { hub.heartRateEndpoint(it.id).bpm },
            )
        }

    private inner class Environment : SessionEnvironment {
        override val settings: StateFlow<AppSettings> get() = this@AppContainer.settings
        override val phone: PhoneActions get() = this@AppContainer.phone

        override fun now(): Long = SystemClock.elapsedRealtime()

        override fun wallClock(): Long = System.currentTimeMillis()

        override suspend fun lastGear(): Int? = memory.memory.first().lastGear

        override suspend fun saveGear(gear: Int) = memory.update { it.copy(lastGear = gear) }

        override suspend fun saveFit(bytes: ByteArray): File =
            withContext(Dispatchers.IO) {
                exportsDir.mkdirs()
                File(exportsDir, "sortie-${LocalDateTime.now().format(FIT_NAME)}.fit").apply { writeBytes(bytes) }
            }

        override suspend fun saveActivity(activity: Activity): Long? =
            try {
                activities.add(activity)
            } catch (e: SQLiteException) {
                log.log("activité non enregistrée : $e")
                null
            }

        override fun log(message: String) = log.log(message)
    }

    private companion object {
        val FIT_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    }
}

class KickZwiftApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Plantage : le trainer reçoit Neutral avant que le processus ne s'arrête
        // (spécification, « Sécurité »).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                container.log.log("plantage : $error")
                runBlocking { withTimeoutOrNull(EMERGENCY_TIMEOUT_MS) { container.session.emergencyNeutral() } }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private companion object {
        const val EMERGENCY_TIMEOUT_MS = 800L
    }
}

val Context.container: AppContainer get() = (applicationContext as KickZwiftApp).container
