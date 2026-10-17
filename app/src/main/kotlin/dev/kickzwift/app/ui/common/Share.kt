package dev.kickzwift.app.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.kickzwift.app.R
import java.io.File

/**
 * Partage un fichier par le partage Android (spécification : pas d'accès au stockage).
 * Le FileProvider de l'app n'expose que le dossier des exports.
 */
fun Context.shareFile(
    file: File,
    mime: String,
) {
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val send =
        Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, getString(R.string.share_title)))
}
