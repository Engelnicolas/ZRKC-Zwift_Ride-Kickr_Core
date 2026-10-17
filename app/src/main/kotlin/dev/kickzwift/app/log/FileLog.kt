package dev.kickzwift.app.log

import android.util.Log
import dev.kickzwift.protocol.device.Redaction
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Journal horodaté dans le stockage interne (spécification, « Persistance ») : rotation sur
 * [files] fichiers de [maxBytes] octets. Adresses Bluetooth tronquées, aucune donnée
 * personnelle. Chaque ligne part aussi dans le journal Android.
 */
class FileLog(
    private val dir: File,
    private val files: Int = 5,
    private val maxBytes: Long = 1_000_000,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    private val lock = Any()

    fun log(message: String) {
        val clean = Redaction.addresses(message)
        Log.i(TAG, clean)
        val line = "${clock().format(STAMP)} $clean\n"
        synchronized(lock) {
            runCatching {
                dir.mkdirs()
                val current = file(0)
                if (current.length() + line.length > maxBytes) rotate()
                current.appendText(line)
            }
        }
    }

    /** Tout le journal, du plus ancien au plus récent, dans [target] ; à partager. */
    fun exportTo(target: File): File {
        synchronized(lock) {
            target.parentFile?.mkdirs()
            target.writeText("")
            for (i in files - 1 downTo 0) {
                val f = file(i)
                if (f.exists()) target.appendText(f.readText())
            }
        }
        return target
    }

    private fun rotate() {
        file(files - 1).delete()
        for (i in files - 2 downTo 0) {
            val f = file(i)
            if (f.exists()) f.renameTo(file(i + 1))
        }
    }

    private fun file(index: Int) = File(dir, "journal-$index.log")

    private companion object {
        const val TAG = "KickZwift"
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}
