package dev.kickzwift.protocol.settings

import dev.kickzwift.protocol.Action
import dev.kickzwift.protocol.Button
import dev.kickzwift.protocol.Gesture
import dev.kickzwift.protocol.gesture.GestureTiming
import dev.kickzwift.protocol.grade.GradeLimits
import dev.kickzwift.protocol.mapping.ButtonMapping
import dev.kickzwift.protocol.wire.MalformedFrameException
import dev.kickzwift.protocol.wire.ProtoReader
import dev.kickzwift.protocol.wire.ProtoWriter
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Réglages au format protobuf (settings.pb), sans runtime protobuf :
 *
 *     message Settings {
 *       repeated Binding binding = 1;   // {1 commande, 2 geste, 3 action, 4 paramètre sint32}
 *       uint32 mapping_set = 2;         // 1 : la correspondance est écrite, même vide
 *       repeated uint32 gear_x10000 = 3;
 *       uint32 start_gear = 4; uint32 rider_kg_x100 = 5; uint32 bike_kg_x100 = 6;
 *       uint32 write_interval_ms = 7;
 *       uint32 debounce_ms = 8; uint32 long_press_ms = 9; uint32 double_window_ms = 10;
 *       uint32 paddle_press = 11; uint32 paddle_release = 12;
 *       sint32 grade_min_x100 = 13; sint32 grade_max_x100 = 14; uint32 erg_max_w = 15;
 *       string theme = 16; string orientation = 17;
 *       uint32 haptics = 18; uint32 imperial = 19; uint32 ftms_fallback = 20; uint32 fit_export = 21;
 *       string gear_mode = 22;
 *     }
 *
 * Les énumérations sont écrites par leur nom : l'ordre du code peut changer sans casser le
 * fichier. À la lecture, une valeur absente ou invalide reprend sa valeur par défaut, champ
 * par champ ; seule une trame illisible lève [MalformedFrameException].
 */
object SettingsCodec {
    private const val BINDING = 1
    private const val MAPPING_SET = 2
    private const val GEAR = 3
    private const val START_GEAR = 4
    private const val RIDER = 5
    private const val BIKE = 6
    private const val WRITE_INTERVAL = 7
    private const val DEBOUNCE = 8
    private const val LONG_PRESS = 9
    private const val DOUBLE_WINDOW = 10
    private const val PADDLE_PRESS = 11
    private const val PADDLE_RELEASE = 12
    private const val GRADE_MIN = 13
    private const val GRADE_MAX = 14
    private const val ERG_MAX = 15
    private const val THEME = 16
    private const val ORIENTATION = 17
    private const val HAPTICS = 18
    private const val IMPERIAL = 19
    private const val FTMS = 20
    private const val GEAR_MODE = 22
    private const val FIT = 21

    private const val B_BUTTON = 1
    private const val B_GESTURE = 2
    private const val B_ACTION = 3
    private const val B_PARAM = 4

    private const val RATIO_SCALE = 10_000.0
    private const val HUNDRED = 100.0
    private const val GRADE_PARAM_SCALE = 10.0

    fun encode(s: AppSettings): ByteArray {
        val w = ProtoWriter()
        for ((button, gesture, action) in s.mapping.entries()) {
            val (kind, param) = ActionCodes.encode(action)
            w.message(
                BINDING,
                ProtoWriter()
                    .message(B_BUTTON, button.name.encodeToByteArray())
                    .message(B_GESTURE, gesture.name.encodeToByteArray())
                    .message(B_ACTION, kind.encodeToByteArray())
                    .sint(B_PARAM, param)
                    .toByteArray(),
            )
        }
        w.varint(MAPPING_SET, 1)
        s.gears.forEach { w.varint(GEAR, (it * RATIO_SCALE).roundToLong()) }
        w
            .varint(START_GEAR, s.startGear.toLong())
            .varint(RIDER, (s.riderKg * HUNDRED).roundToLong())
            .varint(BIKE, (s.bikeKg * HUNDRED).roundToLong())
            .varint(WRITE_INTERVAL, s.writeIntervalMs)
            .varint(DEBOUNCE, s.timing.debounceMs)
            .varint(LONG_PRESS, s.timing.longPressMs)
            .varint(DOUBLE_WINDOW, s.timing.doubleWindowMs)
            .varint(PADDLE_PRESS, s.paddlePress.toLong())
            .varint(PADDLE_RELEASE, s.paddleRelease.toLong())
            .sint(GRADE_MIN, (s.gradeLimits.minPct * HUNDRED).roundToInt())
            .sint(GRADE_MAX, (s.gradeLimits.maxPct * HUNDRED).roundToInt())
            .varint(ERG_MAX, s.ergMaxW.toLong())
            .message(THEME, s.theme.name.encodeToByteArray())
            .message(ORIENTATION, s.orientation.name.encodeToByteArray())
            .varint(HAPTICS, flag(s.haptics))
            .varint(IMPERIAL, flag(s.imperial))
            .varint(FTMS, flag(s.ftmsFallback))
            .varint(FIT, flag(s.fitExport))
            .message(GEAR_MODE, s.gearMode.name.encodeToByteArray())
        return w.toByteArray()
    }

    fun decode(bytes: ByteArray): AppSettings {
        val f = Fields.read(ProtoReader(bytes))
        val gears = gearsOf(f)
        val (press, release) = paddlesOf(f)
        return AppSettings(
            mapping = if (f.has(MAPPING_SET)) mapping(f.messages(BINDING)) else D.mapping,
            gears = gears,
            startGear = f.int(START_GEAR)?.takeIf { it in 1..gears.size } ?: D.startGear.coerceAtMost(gears.size),
            riderKg = massOf(f, RIDER, D.riderKg),
            bikeKg = massOf(f, BIKE, D.bikeKg),
            writeIntervalMs = f.long(WRITE_INTERVAL)?.takeIf { it in AppSettings.WRITE_INTERVAL_RANGE } ?: D.writeIntervalMs,
            timing = timing(f),
            paddlePress = press,
            paddleRelease = release,
            gradeLimits = gradeLimits(f),
            ergMaxW = f.int(ERG_MAX)?.takeIf { it in 1..AppSettings.ERG_CEILING_W } ?: D.ergMaxW,
            theme = enumOf(f, THEME, D.theme),
            orientation = enumOf(f, ORIENTATION, D.orientation),
            haptics = flagOf(f, HAPTICS, D.haptics),
            imperial = flagOf(f, IMPERIAL, D.imperial),
            ftmsFallback = flagOf(f, FTMS, D.ftmsFallback),
            fitExport = flagOf(f, FIT, D.fitExport),
            gearMode = enumOf(f, GEAR_MODE, D.gearMode),
        )
    }

    private val D = AppSettings()

    private fun gearsOf(f: Fields): List<Double> = f.repeated(GEAR).map { it / RATIO_SCALE }.takeIf { validGears(it) } ?: D.gears

    private fun paddlesOf(f: Fields): Pair<Int, Int> {
        val press = f.int(PADDLE_PRESS)?.takeIf { it in 1..AppSettings.AXIS_MAX } ?: D.paddlePress
        return press to (f.int(PADDLE_RELEASE)?.takeIf { it in 0..press } ?: minOf(D.paddleRelease, press))
    }

    private fun massOf(
        f: Fields,
        field: Int,
        default: Double,
    ): Double = f.long(field)?.let { it / HUNDRED }?.takeIf { it in AppSettings.MASS_RANGE } ?: default

    private inline fun <reified E : Enum<E>> enumOf(
        f: Fields,
        field: Int,
        default: E,
    ): E = f.string(field)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private fun flagOf(
        f: Fields,
        field: Int,
        default: Boolean,
    ): Boolean = f.long(field)?.let { it != 0L } ?: default

    private fun flag(b: Boolean) = if (b) 1L else 0L

    private fun validGears(gears: List<Double>) =
        gears.isNotEmpty() && gears.size <= AppSettings.MAX_GEARS && gears.first() > 0 && gears.zipWithNext().all { (a, b) -> a < b }

    private fun timing(f: Fields): GestureTiming =
        try {
            GestureTiming(
                debounceMs = f.long(DEBOUNCE) ?: D.timing.debounceMs,
                longPressMs = f.long(LONG_PRESS) ?: D.timing.longPressMs,
                doubleWindowMs = f.long(DOUBLE_WINDOW) ?: D.timing.doubleWindowMs,
            )
        } catch (_: IllegalArgumentException) {
            D.timing
        }

    private fun gradeLimits(f: Fields): GradeLimits {
        val min = f.sint(GRADE_MIN)?.div(HUNDRED)
        val max = f.sint(GRADE_MAX)?.div(HUNDRED)
        return when {
            min == null || max == null -> D.gradeLimits
            min < max && min in GRADE_RANGE && max in GRADE_RANGE -> GradeLimits(min, max)
            else -> D.gradeLimits
        }
    }

    private fun mapping(bindings: List<ByteArray>): ButtonMapping =
        bindings.fold(ButtonMapping.EMPTY) { m, bytes ->
            val b = Fields.read(ProtoReader(bytes))
            val button = b.string(B_BUTTON)?.let { n -> Button.entries.firstOrNull { it.name == n } }
            val gesture = b.string(B_GESTURE)?.let { n -> Gesture.entries.firstOrNull { it.name == n } }
            val action = b.string(B_ACTION)?.let { ActionCodes.decode(it, b.sint(B_PARAM) ?: 0) }
            val known = action?.takeIf { it in ButtonMapping.CHOICES }
            if (button == null || gesture == null || known == null) m else m.with(button, gesture, known)
        }

    private val GRADE_RANGE = -40.0..40.0

    /** Actions par leur nom, paramètre à part : pente en dixièmes de %. */
    internal object ActionCodes {
        fun encode(a: Action): Pair<String, Int> =
            when (a) {
                is Action.Shift -> "shift" to a.delta
                is Action.Grade -> "grade" to (a.deltaPct * GRADE_PARAM_SCALE).roundToInt()
                Action.PlayPause -> "play_pause" to 0
                Action.NextTrack -> "next_track" to 0
                Action.PreviousTrack -> "previous_track" to 0
                Action.VolumeUp -> "volume_up" to 0
                Action.VolumeDown -> "volume_down" to 0
                Action.PauseResume -> "pause_resume" to 0
                Action.OpenSpotify -> "open_spotify" to 0
                Action.None -> "none" to 0
            }

        fun decode(
            kind: String,
            param: Int,
        ): Action? =
            when (kind) {
                "shift" -> Action.Shift(param)
                "grade" -> Action.Grade(param / GRADE_PARAM_SCALE)
                "play_pause" -> Action.PlayPause
                "next_track" -> Action.NextTrack
                "previous_track" -> Action.PreviousTrack
                "volume_up" -> Action.VolumeUp
                "volume_down" -> Action.VolumeDown
                "pause_resume" -> Action.PauseResume
                "open_spotify" -> Action.OpenSpotify
                "none" -> Action.None
                else -> null
            }
    }
}

/** Mémoire entre deux sorties (ride_state.pb) : `uint32 last_gear = 1; repeated Route route = 2 {1 fichier, 2 nom}`. */
object RideMemoryCodec {
    private const val LAST_GEAR = 1
    private const val ROUTE = 2
    private const val R_FILE = 1
    private const val R_NAME = 2

    fun encode(m: RideMemory): ByteArray {
        val w = ProtoWriter()
        m.lastGear?.let { w.varint(LAST_GEAR, it.toLong()) }
        for (r in m.recentRoutes) {
            w.message(
                ROUTE,
                ProtoWriter().message(R_FILE, r.file.encodeToByteArray()).message(R_NAME, r.name.encodeToByteArray()).toByteArray(),
            )
        }
        return w.toByteArray()
    }

    fun decode(bytes: ByteArray): RideMemory {
        val f = Fields.read(ProtoReader(bytes))
        val routes =
            f.messages(ROUTE).mapNotNull { bytes ->
                val r = Fields.read(ProtoReader(bytes))
                val file = r.string(R_FILE)?.takeIf { RouteFiles.isSafeName(it) } ?: return@mapNotNull null
                RecentRoute(file, r.string(R_NAME) ?: file)
            }
        return RideMemory(
            lastGear = f.int(LAST_GEAR)?.takeIf { it >= 1 },
            recentRoutes = routes.distinctBy { it.file }.take(RideMemory.MAX_RECENT),
        )
    }
}

/** Noms des fichiers GPX copiés dans le stockage interne : jamais de chemin. */
object RouteFiles {
    private val SAFE = Regex("[A-Za-z0-9._-]{1,80}")

    fun isSafeName(name: String): Boolean = SAFE.matches(name) && name != "." && name != ".."
}

/** Champs d'un message : varints et champs délimités, tous répétables. */
internal class Fields private constructor(
    private val varints: Map<Int, List<Long>>,
    private val bytes: Map<Int, List<ByteArray>>,
) {
    fun has(field: Int) = field in varints || field in bytes

    fun long(field: Int): Long? = varints[field]?.last()

    fun int(field: Int): Int? = long(field)?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()

    fun sint(field: Int): Int? = long(field)?.let { ProtoReader.zigzag(it) }

    fun repeated(field: Int): List<Long> = varints[field].orEmpty()

    fun messages(field: Int): List<ByteArray> = bytes[field].orEmpty()

    fun string(field: Int): String? = bytes[field]?.last()?.decodeToString()

    companion object {
        fun read(r: ProtoReader): Fields {
            val v = mutableMapOf<Int, MutableList<Long>>()
            val b = mutableMapOf<Int, MutableList<ByteArray>>()
            while (r.hasMore) {
                val (field, wire) = r.readTag()
                when (wire) {
                    ProtoReader.WIRE_VARINT -> v.getOrPut(field) { mutableListOf() } += r.readVarint()
                    ProtoReader.WIRE_LEN -> b.getOrPut(field) { mutableListOf() } += r.readDelimited().remainingBytes()
                    else -> r.skip(wire)
                }
            }
            if (v.values.sumOf { it.size } + b.values.sumOf { it.size } > MAX_FIELDS) throw MalformedFrameException("trop de champs")
            return Fields(v, b)
        }

        private const val MAX_FIELDS = 10_000
    }
}
