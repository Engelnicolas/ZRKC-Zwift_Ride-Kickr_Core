package dev.kickzwift.ble.nordic

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Handler
import dev.kickzwift.ble.device.RideEndpoint
import dev.kickzwift.ble.device.TrainerConnection
import dev.kickzwift.ble.device.TrainerEndpoint
import dev.kickzwift.ble.link.LinkError
import dev.kickzwift.ble.link.LinkException
import dev.kickzwift.protocol.TrainerTarget
import dev.kickzwift.protocol.ride.RideDecoder
import dev.kickzwift.protocol.ride.RideFrame
import dev.kickzwift.protocol.trainer.TrainerCodec
import dev.kickzwift.protocol.trainer.TrainerFrame
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.ktx.suspend
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Protocole Zwift commun au Ride et au KICKR (spécification, « Spécification des protocoles ») :
 * dans [service], `…0002` en notification, `…0003` en écriture sans réponse, `…0004` en
 * indication. Poignée de main : s'abonner aux deux, écrire « RideOn », attendre l'écho sur `…0004`.
 */
abstract class ZwiftManager(
    context: Context,
    handler: Handler,
    private val service: UUID,
) : LinkManager(context, handler) {
    private var notify: BluetoothGattCharacteristic? = null
    private var control: BluetoothGattCharacteristic? = null
    private var response: BluetoothGattCharacteristic? = null

    private val indications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    protected abstract fun onNotification(frame: ByteArray)

    protected open fun onIndication(frame: ByteArray) = Unit

    /** Lectures facultatives après la poignée de main (batterie, firmware). */
    protected open suspend fun afterHandshake() = Unit

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val s = gatt.getService(service) ?: return false
        notify = s.getCharacteristic(NOTIFY)
        control = s.getCharacteristic(CONTROL)
        response = s.getCharacteristic(RESPONSE)
        findOptional(gatt)
        return notify != null && control != null && response != null
    }

    /** Caractéristiques facultatives d'autres services. */
    protected open fun findOptional(gatt: BluetoothGatt) = Unit

    override fun initialize() {
        setNotificationCallback(notify).with { _, data -> data.value?.let { onNotification(it) } }
        enableNotifications(notify).enqueue()
        setIndicationCallback(response).with { _, data ->
            data.value?.let {
                indications.tryEmit(it)
                onIndication(it)
            }
        }
        enableIndications(response).enqueue()
    }

    override fun onServicesInvalidated() {
        notify = null
        control = null
        response = null
    }

    override suspend fun handshake() {
        val acked =
            coroutineScope {
                val echo = async(start = CoroutineStart.UNDISPATCHED) { indications.first { startsWithRideOn(it) } }
                write(TrainerCodec.RIDE_ON)
                withTimeoutOrNull(HANDSHAKE_TIMEOUT) { echo.await() }.also { echo.cancel() }
            }
        if (acked == null) throw LinkException(LinkError(LinkError.Reason.HANDSHAKE))
        afterHandshake()
    }

    /** Écriture sans réponse sur `…0003`, seule forme acceptée par le KICKR. */
    protected suspend fun write(frame: ByteArray) {
        val c = control ?: throw LinkException(LinkError(LinkError.Reason.LINK_LOSS))
        writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).suspend()
    }

    /** Attend la prochaine indication qui satisfait [match], déclenchée par [send]. */
    protected suspend fun <T : Any> request(
        send: ByteArray,
        timeout: Duration,
        match: (ByteArray) -> T?,
    ): T? =
        coroutineScope {
            val reply = async(start = CoroutineStart.UNDISPATCHED) { indications.first { match(it) != null } }
            write(send)
            withTimeoutOrNull(timeout) { reply.await() }.also { reply.cancel() }?.let(match)
        }

    private fun startsWithRideOn(frame: ByteArray) =
        frame.size >= TrainerCodec.RIDE_ON.size && frame.copyOf(TrainerCodec.RIDE_ON.size).contentEquals(TrainerCodec.RIDE_ON)

    companion object {
        val NOTIFY: UUID = UUID.fromString("00000002-19ca-4651-86e5-fa29dcdd09d1")
        val CONTROL: UUID = UUID.fromString("00000003-19ca-4651-86e5-fa29dcdd09d1")
        val RESPONSE: UUID = UUID.fromString("00000004-19ca-4651-86e5-fa29dcdd09d1")
        val HANDSHAKE_TIMEOUT = 5.seconds
    }
}

/** Une manette du Ride : trames vers [endpoint], batterie et firmware lus à la connexion. */
class RideManager(
    context: Context,
    handler: Handler,
    private val endpoint: RideEndpoint,
) : ZwiftManager(context, handler, RIDE_SERVICE) {
    private var battery: BluetoothGattCharacteristic? = null
    private var firmware: BluetoothGattCharacteristic? = null

    // Battement 0x15 toutes les 0,99 s : au-delà de 3 s sans trame, la manette est perdue.
    override val silenceTimeout: Duration = 3.seconds

    override fun findOptional(gatt: BluetoothGatt) {
        battery = gatt.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)
        firmware = gatt.getService(DEVICE_INFORMATION)?.getCharacteristic(FIRMWARE_REVISION)
    }

    override fun onNotification(frame: ByteArray) {
        markAlive()
        val decoded = RideDecoder.decode(frame)
        if (decoded is RideFrame.Battery) endpoint.battery.value = decoded.percent
        endpoint.frames.tryEmit(decoded)
    }

    override suspend fun afterHandshake() {
        // Lectures facultatives : un échec n'empêche pas la liaison.
        firmware
            ?.let { c -> runCatching { readCharacteristic(c).suspend().getStringValue(0) }.getOrNull() }
            ?.let { endpoint.firmware.value = it.trim() }
        battery
            ?.let { c -> runCatching { readCharacteristic(c).suspend().getIntValue(Data.FORMAT_UINT8, 0) }.getOrNull() }
            ?.let { endpoint.battery.value = it }
    }

    companion object {
        val RIDE_SERVICE: UUID = UUID.fromString("0000fc82-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val DEVICE_INFORMATION: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    }
}

/** Le KICKR CORE en protocole Zwift : télémétrie 0x03, consignes 0x04, requête 520. */
class KickrManager(
    context: Context,
    handler: Handler,
    private val endpoint: TrainerEndpoint,
    private val now: () -> Long,
    private val log: (String) -> Unit,
) : ZwiftManager(context, handler, KICKR_SERVICE),
    TrainerConnection {
    override fun onNotification(frame: ByteArray) {
        markAlive()
        when (val f = TrainerCodec.decode(frame)) {
            is TrainerFrame.Riding -> endpoint.data.value = f.fields.toRidingData(now())
            is TrainerFrame.DebugLog -> log("KICKR : ${f.text}")
            is TrainerFrame.Malformed -> log("KICKR : trame rejetée, ${f.reason}")
            else -> Unit
        }
    }

    override suspend fun afterHandshake() {
        endpoint.attach(this)
    }

    override fun released() {
        endpoint.detach(this)
    }

    override suspend fun apply(target: TrainerTarget) {
        TrainerCodec.encode(target).forEach { write(it) }
    }

    override suspend fun readGearRatio(): Double? =
        request(TrainerCodec.infoRequest(), REPLY_TIMEOUT) { frame ->
            (TrainerCodec.decode(frame) as? TrainerFrame.GearRatio)?.gearRatioX10000
        }?.let { it / RATIO_SCALE }

    companion object {
        val KICKR_SERVICE: UUID = UUID.fromString("00000001-19ca-4651-86e5-fa29dcdd09d1")
        val REPLY_TIMEOUT = 1.seconds
        private const val RATIO_SCALE = 10_000.0
    }
}
