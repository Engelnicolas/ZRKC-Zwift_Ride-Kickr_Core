package dev.kickzwift.ble.nordic

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Handler
import dev.kickzwift.ble.device.TrainerConnection
import dev.kickzwift.ble.device.TrainerEndpoint
import dev.kickzwift.ble.link.LinkError
import dev.kickzwift.ble.link.LinkException
import dev.kickzwift.protocol.TrainerTarget
import dev.kickzwift.protocol.trainer.FtmsCodec
import dev.kickzwift.protocol.trainer.FtmsFrame
import dev.kickzwift.protocol.trainer.FtmsGears
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.ktx.suspend
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Repli FTMS (service 0x1826), activable dans les options avancées : « Indoor Bike Data »
 * en notification, point de contrôle en écriture avec indication. Les vitesses virtuelles
 * y sont simulées par un décalage de pente ([FtmsGears]). Pas de requête 520 : le rapport
 * n'existe pas côté trainer.
 */
class FtmsManager(
    context: Context,
    handler: Handler,
    private val endpoint: TrainerEndpoint,
    private val gears: FtmsGears,
    private val now: () -> Long,
    private val log: (String) -> Unit,
) : LinkManager(context, handler),
    TrainerConnection {
    private var bikeData: BluetoothGattCharacteristic? = null
    private var controlPoint: BluetoothGattCharacteristic? = null
    private val responses =
        MutableSharedFlow<FtmsFrame.ControlResponse>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val s = gatt.getService(FTMS_SERVICE) ?: return false
        bikeData = s.getCharacteristic(INDOOR_BIKE_DATA)
        controlPoint = s.getCharacteristic(CONTROL_POINT)
        return bikeData != null && controlPoint != null
    }

    override fun initialize() {
        setNotificationCallback(bikeData).with { _, data ->
            when (val f = data.value?.let { FtmsCodec.decodeIndoorBikeData(it) }) {
                is FtmsFrame.IndoorBike -> endpoint.data.value = f.data.toRidingData(now())
                is FtmsFrame.Malformed -> log("FTMS : trame rejetée, ${f.reason}")
                else -> Unit
            }
        }
        enableNotifications(bikeData).enqueue()
        setIndicationCallback(controlPoint).with { _, data ->
            (data.value?.let { FtmsCodec.decodeControlResponse(it) } as? FtmsFrame.ControlResponse)?.let { responses.tryEmit(it) }
        }
        enableIndications(controlPoint).enqueue()
    }

    override fun onServicesInvalidated() {
        bikeData = null
        controlPoint = null
    }

    override suspend fun handshake() {
        if (!command(FtmsCodec.requestControl(), FtmsCodec.OP_REQUEST_CONTROL)) {
            throw LinkException(LinkError(LinkError.Reason.HANDSHAKE))
        }
        command(FtmsCodec.startOrResume(), FtmsCodec.OP_START_OR_RESUME)
        endpoint.attach(this)
    }

    override fun released() {
        endpoint.detach(this)
    }

    override suspend fun apply(target: TrainerTarget) {
        val c = controlPoint ?: throw LinkException(LinkError(LinkError.Reason.LINK_LOSS))
        writeCharacteristic(c, gears.encode(target), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).suspend()
    }

    override suspend fun readGearRatio(): Double? = null

    /** Écrit une commande et attend sa réponse ; vrai si elle a réussi. */
    private suspend fun command(
        frame: ByteArray,
        opcode: Int,
    ): Boolean {
        val c = controlPoint ?: return false
        val reply =
            coroutineScope {
                val r = async(start = CoroutineStart.UNDISPATCHED) { responses.first { it.requestOpcode == opcode } }
                writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).suspend()
                withTimeoutOrNull(REPLY_TIMEOUT) { r.await() }.also { r.cancel() }
            }
        return reply?.succeeded == true
    }

    companion object {
        val FTMS_SERVICE: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
        val INDOOR_BIKE_DATA: UUID = UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb")
        val CONTROL_POINT: UUID = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb")
        private val REPLY_TIMEOUT = 3.seconds
    }
}
