package io.github.topiautio.blemeshctl.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

class GattException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val DEFAULT_TIMEOUT_MILLIS = 20_000L

/**
 * Wait for a GATT callback, reporting a timeout as a [GattException].
 *
 * A plain `withTimeout` would throw a cancellation exception instead, which
 * callers would mistake for the user stopping the operation.
 */
internal suspend fun <T : Any> awaitGattOperation(operation: Deferred<T>, timeoutMillis: Long, description: String): T =
    withTimeoutOrNull(timeoutMillis) { operation.await() }
        ?: throw GattException("$description timed out after $timeoutMillis ms")

/** A coroutine wrapper for one Android GATT client connection. */
@SuppressLint("MissingPermission")
class GattConnection private constructor() {
    private val connected = CompletableDeferred<Unit>()
    private var gatt: BluetoothGatt? = null

    @Volatile
    var isConnected = false
        private set

    @Volatile
    private var servicesDiscovered: CompletableDeferred<Unit>? = null

    @Volatile
    private var pendingWrite: CompletableDeferred<Unit>? = null

    @Volatile
    private var pendingRead: CompletableDeferred<ByteArray>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                connected.complete(Unit)
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                isConnected = false
                val error = GattException("GATT connection closed (status $status, state $newState)")
                connected.completeExceptionally(error)
                servicesDiscovered?.completeExceptionally(error)
                pendingWrite?.completeExceptionally(error)
                pendingRead?.completeExceptionally(error)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            completeStatus(servicesDiscovered, status, "service discovery")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            completeStatus(pendingWrite, status, "characteristic ${characteristic.uuid} write")
        }

        @Deprecated("Deprecated by Android")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            // Android 13 and newer deliver the value through the overload below.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                completeRead(characteristic.value ?: ByteArray(0), status, characteristic.uuid)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            completeRead(value, status, characteristic.uuid)
        }
    }

    suspend fun discoverServices(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {
        val currentGatt = requireGatt()
        val operation = CompletableDeferred<Unit>()
        servicesDiscovered = operation
        if (!currentGatt.discoverServices()) {
            servicesDiscovered = null
            throw GattException("Android rejected GATT service discovery")
        }
        try {
            awaitGattOperation(operation, timeoutMillis, "GATT service discovery")
        } finally {
            if (servicesDiscovered === operation) servicesDiscovered = null
        }
    }

    fun characteristic(serviceUuid: UUID, characteristicUuid: UUID): BluetoothGattCharacteristic {
        val service = requireGatt().getService(serviceUuid)
            ?: throw GattException("Telink GATT service $serviceUuid was not found")
        return service.getCharacteristic(characteristicUuid)
            ?: throw GattException("Telink GATT characteristic $characteristicUuid was not found")
    }

    /** Write with response and wait until the peripheral acknowledges it. */
    suspend fun write(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ) {
        val currentGatt = requireGatt()
        val operation = CompletableDeferred<Unit>()
        check(pendingWrite == null) { "a GATT write is already pending" }
        pendingWrite = operation
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(characteristic)
        }
        if (!accepted) {
            pendingWrite = null
            throw GattException("Android rejected the GATT write request")
        }
        try {
            awaitGattOperation(operation, timeoutMillis, "characteristic ${characteristic.uuid} write")
        } finally {
            if (pendingWrite === operation) pendingWrite = null
        }
    }

    suspend fun read(characteristic: BluetoothGattCharacteristic, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): ByteArray {
        val currentGatt = requireGatt()
        val operation = CompletableDeferred<ByteArray>()
        check(pendingRead == null) { "a GATT read is already pending" }
        pendingRead = operation
        if (!currentGatt.readCharacteristic(characteristic)) {
            pendingRead = null
            throw GattException("Android rejected the GATT read request")
        }
        try {
            return awaitGattOperation(operation, timeoutMillis, "characteristic ${characteristic.uuid} read")
        } finally {
            if (pendingRead === operation) pendingRead = null
        }
    }

    /** Disconnect, release the Android GATT client, and fail pending operations. */
    fun close() {
        isConnected = false
        val currentGatt = gatt
        gatt = null
        if (currentGatt != null) {
            runCatching { currentGatt.disconnect() }
            runCatching { currentGatt.close() }
        }
        val error = GattException("GATT connection was closed")
        servicesDiscovered?.completeExceptionally(error)
        pendingWrite?.completeExceptionally(error)
        pendingRead?.completeExceptionally(error)
    }

    private fun requireGatt(): BluetoothGatt = gatt ?: throw GattException("GATT connection is closed")

    private fun completeStatus(operation: CompletableDeferred<Unit>?, status: Int, description: String) {
        if (operation == null) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            operation.complete(Unit)
        } else {
            operation.completeExceptionally(GattException("$description failed with status $status"))
        }
    }

    private fun completeRead(value: ByteArray, status: Int, uuid: UUID) {
        val operation = pendingRead ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            operation.complete(value.copyOf())
        } else {
            operation.completeExceptionally(GattException("characteristic $uuid read failed with status $status"))
        }
    }

    companion object {
        /** Open a direct LE connection to [device] and wait until it is connected. */
        suspend fun connect(
            context: Context,
            device: BluetoothDevice,
            timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        ): GattConnection {
            val connection = GattConnection()
            try {
                // The settings-based replacement exists only on Android 17 and newer.
                @Suppress("DEPRECATION")
                connection.gatt = device.connectGatt(context, false, connection.callback, BluetoothDevice.TRANSPORT_LE)
                    ?: throw GattException("Android did not create a GATT client")
                awaitGattOperation(connection.connected, timeoutMillis, "GATT connection")
                return connection
            } catch (exception: CancellationException) {
                connection.close()
                throw exception
            } catch (exception: Exception) {
                connection.close()
                throw GattException("Bluetooth connection attempt failed: ${exception.message}", exception)
            }
        }
    }
}
