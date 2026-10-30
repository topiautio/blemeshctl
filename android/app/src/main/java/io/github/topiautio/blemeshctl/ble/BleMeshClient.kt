package io.github.topiautio.blemeshctl.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import io.github.topiautio.blemeshctl.protocol.AdvertisementInfo
import io.github.topiautio.blemeshctl.protocol.TelinkProtocol
import io.github.topiautio.blemeshctl.protocol.TelinkProtocolException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Raised when discovery or a GATT command cannot be completed. */
open class BleMeshException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A retryable failure before any encrypted light command was sent. */
class InitialConnectionException(message: String, cause: Throwable? = null) : BleMeshException(message, cause)

/** The command may or may not have reached the light, so it must not be retried automatically. */
class CommandOutcomeUnknownException(message: String, cause: Throwable? = null) : BleMeshException(message, cause)

data class MeshCredentials(val meshName: String, val password: String)

data class ConnectionReceipt(
    val info: AdvertisementInfo,
    val reusedConnection: Boolean,
    val connectedDirectlyFromCache: Boolean,
)

data class CommandReceipt(
    val info: AdvertisementInfo,
    val reusedConnection: Boolean,
    val connectedDirectlyFromCache: Boolean,
)

/**
 * Keeps one authenticated Telink connection open between commands.
 *
 * The connection closes after [idleTimeoutMillis] without a command, like the
 * command-line tool's keepalive daemon.
 */
@SuppressLint("MissingPermission")
class BleMeshClient(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val idleTimeoutMillis: Long = 300_000,
) {
    private val applicationContext = context.applicationContext
    private val scanner = BleMeshScanner(adapter)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var idleJob: Job? = null
    private var session: Session? = null

    private class Session(
        val address: String,
        val credentials: MeshCredentials,
        val info: AdvertisementInfo,
        val connectedDirectlyFromCache: Boolean,
        val sessionKey: ByteArray,
        val connection: GattConnection,
        val commandCharacteristic: BluetoothGattCharacteristic,
    )

    val connectionDescription: String
        get() = session
            ?.takeIf { it.connection.isConnected }
            ?.let { "Yhteys ${it.address}, mesh 0x${it.info.meshAddress.toString(16).padStart(4, '0')}" }
            ?: "Ei avointa yhteyttä"

    /** Open and authenticate a connection without sending a light command. */
    suspend fun connect(
        address: String,
        cachedInfo: AdvertisementInfo?,
        credentials: MeshCredentials,
    ): ConnectionReceipt = mutex.withLock {
        val (active, reusedConnection) = acquireSession(address, cachedInfo, credentials)
        scheduleIdleClose()
        ConnectionReceipt(active.info, reusedConnection, active.connectedDirectlyFromCache)
    }

    /** Send one command, reusing the open connection when it still matches. */
    suspend fun send(
        address: String,
        cachedInfo: AdvertisementInfo?,
        credentials: MeshCredentials,
        opcode: Int,
        parameters: ByteArray,
    ): CommandReceipt = mutex.withLock {
        val (active, reusedConnection) = acquireSession(address, cachedInfo, credentials)
        val frame = TelinkProtocol.buildCommandFrame(
            TelinkProtocol.randomSequence(),
            active.info.meshAddress,
            opcode,
            parameters,
        )
        val encrypted = TelinkProtocol.encryptCommandFrame(active.sessionKey, active.info.commandMacPrefix, frame)
        try {
            active.connection.write(active.commandCharacteristic, encrypted)
        } catch (exception: CancellationException) {
            closeSession()
            throw exception
        } catch (exception: Exception) {
            closeSession()
            throw CommandOutcomeUnknownException(
                "Bluetooth connection dropped during the command write; its physical outcome is unknown",
                exception,
            )
        }
        scheduleIdleClose()
        CommandReceipt(active.info, reusedConnection, active.connectedDirectlyFromCache)
    }

    suspend fun disconnect() {
        mutex.withLock {
            idleJob?.cancel()
            idleJob = null
            closeSession()
        }
    }

    fun shutdown() {
        idleJob?.cancel()
        idleJob = null
        closeSession()
        scope.cancel()
    }

    private suspend fun acquireSession(
        address: String,
        cachedInfo: AdvertisementInfo?,
        credentials: MeshCredentials,
    ): Pair<Session, Boolean> {
        idleJob?.cancel()
        idleJob = null
        val normalizedAddress = address.uppercase(Locale.ROOT)
        val existing = session?.takeIf {
            it.address == normalizedAddress && it.credentials == credentials && it.connection.isConnected
        }
        if (existing != null) return existing to true
        closeSession()
        return connectAndAuthenticate(normalizedAddress, cachedInfo, credentials) to false
    }

    /**
     * Connect straight to a remembered light, then fall back to scanning.
     *
     * A direct connection skips the scan when the cached advertisement still
     * describes the light, which makes the first command noticeably faster.
     */
    private suspend fun connectAndAuthenticate(
        address: String,
        cachedInfo: AdvertisementInfo?,
        credentials: MeshCredentials,
    ): Session {
        var directFailure: InitialConnectionException? = null
        if (cachedInfo != null && cachedInfo.address.uppercase(Locale.ROOT) == address) {
            val device = try {
                adapter.getRemoteDevice(address)
            } catch (exception: Exception) {
                throw InitialConnectionException("Stored Bluetooth address $address is invalid", exception)
            }
            try {
                return authenticate(device, cachedInfo.copy(address = address, rssi = null), credentials, true)
            } catch (exception: InitialConnectionException) {
                directFailure = exception
            }
        }

        try {
            val target = scanner.findByAddress(address)
            return authenticate(target.device, target.info, credentials, false)
        } catch (exception: InitialConnectionException) {
            val directMessage = directFailure?.message?.let { " Direct connection failed first: $it." } ?: ""
            throw InitialConnectionException("${exception.message}.$directMessage", exception)
        }
    }

    private suspend fun authenticate(
        device: BluetoothDevice,
        info: AdvertisementInfo,
        credentials: MeshCredentials,
        connectedDirectlyFromCache: Boolean,
    ): Session {
        val pairRequest = TelinkProtocol.buildPairRequest(credentials.meshName, credentials.password)
        var connection: GattConnection? = null
        try {
            connection = GattConnection.connect(applicationContext, device)
            connection.discoverServices()
            val serviceUuid = UUID.fromString(TelinkProtocol.SERVICE_UUID)
            val pairCharacteristic = connection.characteristic(serviceUuid, UUID.fromString(TelinkProtocol.PAIR_UUID))
            val commandCharacteristic =
                connection.characteristic(serviceUuid, UUID.fromString(TelinkProtocol.COMMAND_UUID))
            connection.write(pairCharacteristic, pairRequest.bytes)
            delay(200)
            val response = connection.read(pairCharacteristic)
            val sessionKey = TelinkProtocol.deriveSessionKey(
                credentials.meshName,
                credentials.password,
                pairRequest.localRandom,
                response,
            )
            return Session(
                info.address.uppercase(Locale.ROOT),
                credentials,
                info,
                connectedDirectlyFromCache,
                sessionKey,
                connection,
                commandCharacteristic,
            ).also { session = it }
        } catch (exception: BleMeshException) {
            connection?.close()
            throw exception
        } catch (exception: TelinkProtocolException) {
            connection?.close()
            throw exception
        } catch (exception: SecurityException) {
            connection?.close()
            throw BleMeshException("Bluetooth permission is missing or was revoked", exception)
        } catch (exception: CancellationException) {
            connection?.close()
            throw exception
        } catch (exception: Exception) {
            connection?.close()
            throw InitialConnectionException(
                "Could not open and authenticate the Bluetooth connection: ${exception.message}",
                exception,
            )
        }
    }

    private fun scheduleIdleClose() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleTimeoutMillis)
            mutex.withLock {
                closeSession()
                idleJob = null
            }
        }
    }

    private fun closeSession() {
        val oldSession = session
        session = null
        oldSession?.connection?.close()
    }
}
