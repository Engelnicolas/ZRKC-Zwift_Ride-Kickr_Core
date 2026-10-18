package dev.kickzwift.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kickzwift.app.AppContainer
import dev.kickzwift.protocol.Action
import dev.kickzwift.protocol.Button
import dev.kickzwift.protocol.Gesture
import dev.kickzwift.protocol.coros.CorosUpload
import dev.kickzwift.protocol.device.DeviceKind
import dev.kickzwift.protocol.gear.GearTable
import dev.kickzwift.protocol.mapping.ButtonMapping
import dev.kickzwift.protocol.settings.AppSettings
import dev.kickzwift.protocol.settings.GearMode
import dev.kickzwift.protocol.settings.OrientationChoice
import dev.kickzwift.protocol.settings.ThemeChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** Firmware lu sur une manette, pour les options avancées. */
data class FirmwareLine(
    val name: String,
    val version: String?,
)

data class SettingsUi(
    val settings: AppSettings = AppSettings(),
    val testing: Boolean = false,
    /** Commande pressée en mode « Tester », surlignée un instant. */
    val pressed: Button? = null,
    val firmware: List<FirmwareLine> = emptyList(),
)

/** Écran Réglages (lot 10) : tout s'applique sans redémarrer la sortie. */
class SettingsViewModel(
    private val c: AppContainer,
) : ViewModel() {
    private val pressed = MutableStateFlow<Button?>(null)

    init {
        viewModelScope.launch {
            c.session.events.collectLatest {
                pressed.value = it.button
                delay(HIGHLIGHT_MS)
                pressed.value = null
            }
        }
    }

    val state: StateFlow<SettingsUi> =
        combine(c.settings, c.session.testing, pressed, c.trusted) { s, testing, p, trusted ->
            SettingsUi(
                settings = s,
                testing = testing,
                pressed = p,
                firmware =
                    trusted
                        .filter { it.kind == DeviceKind.RIDE }
                        .map {
                            FirmwareLine(
                                it.name,
                                c.hub
                                    .rideEndpoint(it.id)
                                    .firmware.value,
                            )
                        },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUi())

    private fun update(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            c.settingsStore.update { current -> runCatching { change(current) }.getOrDefault(current) }
        }
    }

    fun bind(
        button: Button,
        gesture: Gesture,
        action: Action,
    ) = update { it.copy(mapping = it.mapping.with(button, gesture, action)) }

    fun resetMapping() = update { it.copy(mapping = ButtonMapping.DEFAULT) }

    fun setTesting(on: Boolean) {
        c.session.testing.value = on
    }

    /** Table saisie : nombres séparés par des virgules ou des espaces ; faux si invalide. */
    fun setGears(text: String): Boolean {
        val gears = parseGears(text) ?: return false
        update { it.copy(gears = gears, startGear = it.startGear.coerceAtMost(gears.size)) }
        return true
    }

    fun zwiftPreset() = update { it.copy(gears = GearTable.ZWIFT, startGear = it.startGear.coerceAtMost(GearTable.ZWIFT.size)) }

    fun stepStartGear(up: Boolean) = update { it.copy(startGear = (it.startGear + if (up) 1 else -1).coerceIn(1, it.gears.size)) }

    fun stepWriteInterval(up: Boolean) =
        update {
            val next = it.writeIntervalMs + if (up) INTERVAL_STEP_MS else -INTERVAL_STEP_MS
            it.copy(writeIntervalMs = next.coerceIn(AppSettings.WRITE_INTERVAL_RANGE.first, AppSettings.WRITE_INTERVAL_RANGE.last))
        }

    fun stepRider(up: Boolean) = update { it.copy(riderKg = mass(it.riderKg, up)) }

    fun stepBike(up: Boolean) = update { it.copy(bikeKg = mass(it.bikeKg, up)) }

    fun setTheme(t: ThemeChoice) = update { it.copy(theme = t) }

    fun setOrientation(o: OrientationChoice) = update { it.copy(orientation = o) }

    fun setHaptics(on: Boolean) = update { it.copy(haptics = on) }

    fun setImperial(on: Boolean) = update { it.copy(imperial = on) }

    fun setFtms(on: Boolean) = update { it.copy(ftmsFallback = on) }

    /** Essai du renvoi (Advanced › Resend test). */
    val resendTest = c.session.resendTest

    fun startResendTest() = c.session.startResendTest()

    fun stopResendTest() = c.session.stopResendTest()

    /** Compte COROS enregistré, ou null. */
    val corosAccount = c.corosAccounts.account
    val coros = c.coros.status

    fun saveCoros(
        email: String,
        password: String,
        region: CorosUpload.Region,
    ) {
        viewModelScope.launch { c.corosAccounts.save(email, password, region) }
    }

    fun forgetCoros() {
        viewModelScope.launch { c.corosAccounts.forget() }
    }

    fun checkCoros() = c.coros.check()

    fun setGearMode(mode: GearMode) = update { it.copy(gearMode = mode) }

    /** Journal complet, prêt à partager. */
    suspend fun exportLog(): File = withContext(Dispatchers.IO) { c.log.exportTo(File(c.exportsDir, "journal.txt")) }

    private fun mass(
        kg: Double,
        up: Boolean,
    ): Double = (((kg + if (up) MASS_STEP else -MASS_STEP) * TEN).roundToInt() / TEN).coerceIn(AppSettings.MASS_RANGE)

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val HIGHLIGHT_MS = 1_500L
        private const val INTERVAL_STEP_MS = 50L
        private const val MASS_STEP = 0.5
        private const val TEN = 10.0

        fun parseGears(text: String): List<Double>? {
            val values = text.split(',', ';', ' ', '\n').filter { it.isNotBlank() }.map { it.trim().toDoubleOrNull() ?: return null }
            val valid =
                values.size in 1..AppSettings.MAX_GEARS && values.first() > 0 && values.zipWithNext().all { (a, b) -> a < b }
            return values.takeIf { valid }
        }

        fun formatGears(gears: List<Double>): String = gears.joinToString(", ") { "%.2f".format(Locale.ROOT, it) }

        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { SettingsViewModel(c) } }
    }
}
