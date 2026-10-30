package io.github.topiautio.blemeshctl.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.topiautio.blemeshctl.protocol.AdvertisementInfo
import io.github.topiautio.blemeshctl.protocol.TelinkProtocol
import io.github.topiautio.blemeshctl.protocol.TelinkProtocolException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** A compatible BLE device and its Telink advertisement information. */
data class DiscoveredLight(val device: BluetoothDevice, val info: AdvertisementInfo) {
    override fun toString(): String = info.displayLabel()
}

/** Discover advertising Telink lights without connecting to them. */
@SuppressLint("MissingPermission")
class BleMeshScanner(private val adapter: BluetoothAdapter) {
    suspend fun scan(timeoutMillis: Long = 8_000): List<DiscoveredLight> = scanInternal(timeoutMillis, null)

    /** Scan until [address] advertises a usable Telink payload, or fail after [timeoutMillis]. */
    suspend fun findByAddress(address: String, timeoutMillis: Long = 20_000): DiscoveredLight {
        val normalized = address.uppercase(Locale.ROOT)
        return scanInternal(timeoutMillis, normalized).firstOrNull { it.info.address == normalized }
            ?: throw InitialConnectionException("$normalized was not advertising as a compatible Telink light")
    }

    private suspend fun scanInternal(timeoutMillis: Long, targetAddress: String?): List<DiscoveredLight> =
        suspendCancellableCoroutine { continuation ->
            if (!adapter.isEnabled) {
                continuation.resumeWithException(BleMeshException("Bluetooth is disabled"))
                return@suspendCancellableCoroutine
            }
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                continuation.resumeWithException(BleMeshException("Bluetooth LE scanner is unavailable"))
                return@suspendCancellableCoroutine
            }

            val handler = Handler(Looper.getMainLooper())
            val found = LinkedHashMap<String, DiscoveredLight>()
            val guard = Any()
            var finished = false
            lateinit var callback: ScanCallback

            fun finish(error: Throwable? = null) {
                val shouldFinish = synchronized(guard) {
                    if (finished) {
                        false
                    } else {
                        finished = true
                        true
                    }
                }
                if (!shouldFinish) return
                handler.removeCallbacksAndMessages(callback)
                runCatching { scanner.stopScan(callback) }
                if (continuation.isActive) {
                    if (error != null) {
                        continuation.resumeWithException(error)
                    } else {
                        continuation.resume(found.values.sortedBy { it.info.address })
                    }
                }
            }

            fun accept(result: ScanResult) {
                val light = parseResult(result) ?: return
                found[light.info.address] = light
                if (targetAddress != null && light.info.address == targetAddress) {
                    finish()
                }
            }

            callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    accept(result)
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach(::accept)
                }

                override fun onScanFailed(errorCode: Int) {
                    finish(BleMeshException("Bluetooth LE scan failed with Android error $errorCode"))
                }
            }

            continuation.invokeOnCancellation { finish() }
            handler.postAtTime({ finish() }, callback, SystemClock.uptimeMillis() + timeoutMillis)
            try {
                val filters = listOf(
                    ScanFilter.Builder().setManufacturerData(TelinkProtocol.TELINK_COMPANY_ID, ByteArray(0)).build(),
                )
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                scanner.startScan(filters, settings, callback)
            } catch (exception: Exception) {
                finish(BleMeshException("Could not start Bluetooth LE scan: ${exception.message}", exception))
            }
        }

    private fun parseResult(result: ScanResult): DiscoveredLight? {
        val record = result.scanRecord ?: return null
        val address = result.device.address
        val manufacturerData = TelinkProtocol.findAdvertisementManufacturerData(address, record.bytes)
            ?: record.getManufacturerSpecificData(TelinkProtocol.TELINK_COMPANY_ID)
            ?: return null
        return try {
            val info = TelinkProtocol.parseAdvertisement(address, record.deviceName, manufacturerData, result.rssi)
            DiscoveredLight(result.device, info)
        } catch (exception: TelinkProtocolException) {
            null
        }
    }
}
