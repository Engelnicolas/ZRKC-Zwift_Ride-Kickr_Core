package dev.kickzwift.protocol.fuzz

import dev.kickzwift.protocol.device.KnownDevicesCodec
import dev.kickzwift.protocol.ride.RideDecoder
import dev.kickzwift.protocol.settings.RideMemoryCodec
import dev.kickzwift.protocol.settings.SettingsCodec
import dev.kickzwift.protocol.trainer.FtmsCodec
import dev.kickzwift.protocol.trainer.TrainerCodec
import dev.kickzwift.protocol.wire.MalformedFrameException

// Cibles Jazzer (CI nocturne, spécification « Stratégie de tests ») : une trame reçue ou un
// fichier lu, quels qu'ils soient, ne lèvent jamais d'exception non gérée. Jazzer appelle la
// méthode statique fuzzerTestOneInput ; aucune dépendance à Jazzer n'est nécessaire.

/** Trames des manettes du Ride : le décodeur ne lève jamais. */
object RideDecoderFuzz {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        RideDecoder.decode(data)
    }
}

/** Trames du KICKR en protocole Zwift : le décodeur ne lève jamais. */
object TrainerDecoderFuzz {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        TrainerCodec.decode(data)
    }
}

/** Trames FTMS : les décodeurs ne lèvent jamais. */
object FtmsDecoderFuzz {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        FtmsCodec.decodeIndoorBikeData(data)
        FtmsCodec.decodeControlResponse(data)
    }
}

/** Fichiers de l'app : seule MalformedFrameException est permise, le DataStore la traite. */
object StoredFilesFuzz {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        allowMalformed { KnownDevicesCodec.decode(data) }
        allowMalformed { SettingsCodec.decode(data) }
        allowMalformed { RideMemoryCodec.decode(data) }
    }

    private inline fun allowMalformed(block: () -> Unit) {
        try {
            block()
        } catch (_: MalformedFrameException) {
            // attendu : fichier illisible
        }
    }
}
