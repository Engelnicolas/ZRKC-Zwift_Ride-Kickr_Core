package dev.kickzwift.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

enum class ActivityMode { FREE, ROUTE, ERG }

/** Une sortie terminée, telle qu'enregistrée dans la base de l'app. */
data class Activity(
    val id: Long = 0,
    /** Heure Unix du départ, en millisecondes. */
    val startedAtMs: Long,
    val mode: ActivityMode,
    val routeName: String? = null,
    val durationMs: Long,
    val distanceM: Double,
    val avgPowerW: Int,
    val maxPowerW: Int,
    val avgCadenceRpm: Int,
    val elevationGainM: Double,
    val shifts: Int,
    val comment: String = "",
) {
    companion object {
        const val MAX_COMMENT = 500
    }
}

/**
 * Historique des sorties (activities.db), la plus récente en tête. Base SQLite de l'app,
 * exclue des sauvegardes comme le reste (data_extraction_rules.xml).
 */
class ActivityStore(
    context: Context,
) {
    private val db = Helper(context.applicationContext)

    /** Incrémenté à chaque écriture : les lectures en cours se relancent. */
    private val changes = MutableStateFlow(0)

    val activities: Flow<List<Activity>> = changes.map { query(null) }.flowOn(Dispatchers.IO)

    fun activity(id: Long): Flow<Activity?> = changes.map { query(id).firstOrNull() }.flowOn(Dispatchers.IO)

    /** Enregistre la sortie ; rend son identifiant. */
    suspend fun add(activity: Activity): Long =
        write {
            insertOrThrow(TABLE, null, activity.toValues())
        }

    suspend fun comment(
        id: Long,
        text: String,
    ) {
        write {
            val values = ContentValues().apply { put(COMMENT, text.trim().take(Activity.MAX_COMMENT)) }
            update(TABLE, values, "$ID = ?", arrayOf(id.toString()))
        }
    }

    suspend fun delete(id: Long) {
        write { delete(TABLE, "$ID = ?", arrayOf(id.toString())) }
    }

    private suspend fun <T> write(block: SQLiteDatabase.() -> T): T =
        withContext(Dispatchers.IO) {
            db.writableDatabase.block().also { changes.update { it + 1 } }
        }

    private fun query(id: Long?): List<Activity> =
        db.readableDatabase
            .query(
                TABLE,
                null,
                id?.let { "$ID = ?" },
                id?.let { arrayOf(it.toString()) },
                null,
                null,
                "$STARTED_AT DESC, $ID DESC",
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toActivity()) } }

    private class Helper(
        context: Context,
    ) : SQLiteOpenHelper(context, "activities.db", null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $STARTED_AT INTEGER NOT NULL,
                    $MODE TEXT NOT NULL,
                    $ROUTE_NAME TEXT,
                    $DURATION INTEGER NOT NULL,
                    $DISTANCE REAL NOT NULL,
                    $AVG_POWER INTEGER NOT NULL,
                    $MAX_POWER INTEGER NOT NULL,
                    $CADENCE INTEGER NOT NULL,
                    $ELEVATION REAL NOT NULL,
                    $SHIFTS INTEGER NOT NULL,
                    $COMMENT TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX ${TABLE}_$STARTED_AT ON $TABLE ($STARTED_AT)")
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) {
            // Première version du schéma : rien à migrer.
        }
    }

    private companion object {
        const val VERSION = 1
        const val TABLE = "activities"
        const val ID = "_id"
        const val STARTED_AT = "started_at"
        const val MODE = "mode"
        const val ROUTE_NAME = "route_name"
        const val DURATION = "duration_ms"
        const val DISTANCE = "distance_m"
        const val AVG_POWER = "avg_power_w"
        const val MAX_POWER = "max_power_w"
        const val CADENCE = "avg_cadence_rpm"
        const val ELEVATION = "elevation_gain_m"
        const val SHIFTS = "shifts"
        const val COMMENT = "comment"

        fun Activity.toValues() =
            ContentValues().apply {
                put(STARTED_AT, startedAtMs)
                put(MODE, mode.name)
                put(ROUTE_NAME, routeName)
                put(DURATION, durationMs)
                put(DISTANCE, distanceM)
                put(AVG_POWER, avgPowerW)
                put(MAX_POWER, maxPowerW)
                put(CADENCE, avgCadenceRpm)
                put(ELEVATION, elevationGainM)
                put(SHIFTS, shifts)
                put(COMMENT, comment.trim().take(Activity.MAX_COMMENT))
            }

        fun Cursor.toActivity() =
            Activity(
                id = getLong(getColumnIndexOrThrow(ID)),
                startedAtMs = getLong(getColumnIndexOrThrow(STARTED_AT)),
                mode =
                    getString(getColumnIndexOrThrow(MODE)).let { m ->
                        ActivityMode.entries.firstOrNull { it.name == m }
                            ?: ActivityMode.FREE
                    },
                routeName = getColumnIndexOrThrow(ROUTE_NAME).let { if (isNull(it)) null else getString(it) },
                durationMs = getLong(getColumnIndexOrThrow(DURATION)),
                distanceM = getDouble(getColumnIndexOrThrow(DISTANCE)),
                avgPowerW = getInt(getColumnIndexOrThrow(AVG_POWER)),
                maxPowerW = getInt(getColumnIndexOrThrow(MAX_POWER)),
                avgCadenceRpm = getInt(getColumnIndexOrThrow(CADENCE)),
                elevationGainM = getDouble(getColumnIndexOrThrow(ELEVATION)),
                shifts = getInt(getColumnIndexOrThrow(SHIFTS)),
                comment = getString(getColumnIndexOrThrow(COMMENT)),
            )
    }
}
