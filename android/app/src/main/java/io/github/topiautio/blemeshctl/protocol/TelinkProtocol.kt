package io.github.topiautio.blemeshctl.protocol

import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Raised when an advertisement, login response, or command is invalid. */
class TelinkProtocolException(message: String) : IllegalArgumentException(message)

data class Rgb(val red: Int, val green: Int, val blue: Int)

/** A Telink pairing request and the local random value needed to verify its response. */
data class PairRequest(val bytes: ByteArray, val localRandom: ByteArray)

/** Fields exposed by a Telink manufacturer advertisement. */
data class AdvertisementInfo(
    val address: String,
    val name: String?,
    val meshUuid: Int,
    val commandMacPrefix: Long,
    val productUuid: Int,
    val status: Int,
    val meshAddress: Int,
    val rssi: Int?,
) {
    fun displayLabel(): String {
        val signal = rssi?.let { "$it dBm" } ?: "RSSI ?"
        return "${name ?: TelinkProtocol.DEFAULT_MESH_NAME}  $address  ($signal)"
    }

    fun details(): String = buildString {
        append("Osoite $address · mesh 0x${meshAddress.hex(4)} · tuote 0x${productUuid.hex(4)}")
        append(" · status 0x${status.hex(2)} · RSSI ${rssi?.let { "$it dBm" } ?: "?"}")
        // Only mention the advertised MAC bytes when they differ from the address.
        val addressPrefix = runCatching { TelinkProtocol.commandMacPrefix(address) }.getOrNull()
        if (addressPrefix != commandMacPrefix) {
            append(" · Telink-MAC ${commandMacPrefix.hex(8)}")
        }
    }

    override fun toString(): String = displayLabel()
}

/** Pure-Kotlin implementation of the legacy Telink light wire protocol. */
object TelinkProtocol {
    const val TELINK_COMPANY_ID = 0x0211
    private const val MANUFACTURER_SPECIFIC_DATA_TYPE = 0xFF
    const val DEFAULT_MESH_NAME = "BleMesh"
    const val DEFAULT_PASSWORD = "mesh123"

    const val PAIR_REQUEST_OPCODE = 0x0C
    const val PAIR_RESPONSE_OPCODE = 0x0D
    const val COMMAND_ON_OFF = 0xF0
    const val COMMAND_RGB = 0xF1

    const val SERVICE_UUID = "00010203-0405-0607-0809-0a0b0c0d1910"
    const val PAIR_UUID = "00010203-0405-0607-0809-0a0b0c0d1914"
    const val COMMAND_UUID = "00010203-0405-0607-0809-0a0b0c0d1912"

    private val secureRandom = SecureRandom()

    /** Parse a conventional `RRGGBB` or `#RRGGBB` colour value. */
    fun parseRgb(value: String): Rgb {
        val normalized = value.removePrefix("#")
        if (normalized.length != 6) {
            throw TelinkProtocolException("RGB colour must contain exactly six hexadecimal digits")
        }
        return try {
            Rgb(
                normalized.substring(0, 2).toInt(16),
                normalized.substring(2, 4).toInt(16),
                normalized.substring(4, 6).toInt(16),
            )
        } catch (exception: NumberFormatException) {
            throw TelinkProtocolException("RGB colour must contain only hexadecimal digits")
        }
    }

    /** Return the 16-byte XOR credential used by Telink's login handshake. */
    fun credentials(meshName: String, password: String): ByteArray {
        val name = padded16(meshName)
        val secret = padded16(password)
        return ByteArray(16) { index -> (name[index].toInt() xor secret[index].toInt()).toByte() }
    }

    /** Match the two-argument AES helper used by the vendor Android app. */
    fun javaAesEncrypt(key: ByteArray, data: ByteArray): ByteArray {
        if (key.size != 16 || data.size != 16) {
            throw TelinkProtocolException("Telink AES inputs must be exactly 16 bytes")
        }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.reversedArray(), "AES"))
        return cipher.doFinal(data.reversedArray())
    }

    /** Match Telink's little-endian native AES block helper. */
    fun telinkAesBlock(key: ByteArray, data: ByteArray): ByteArray = javaAesEncrypt(key, data).reversedArray()

    fun buildPairRequest(
        meshName: String,
        password: String,
        localRandom: ByteArray = ByteArray(8).also(secureRandom::nextBytes),
    ): PairRequest {
        if (localRandom.size != 8) {
            throw TelinkProtocolException("local random value must be 8 bytes")
        }
        val credential = credentials(meshName, password)
        val proof = javaAesEncrypt(localRandom.copyOf(16), credential).copyOfRange(8, 16).reversedArray()
        return PairRequest(byteArrayOf(PAIR_REQUEST_OPCODE.toByte()) + localRandom + proof, localRandom.copyOf())
    }

    /** Verify a pairing response and derive the 16-byte command session key. */
    fun deriveSessionKey(
        meshName: String,
        password: String,
        localRandom: ByteArray,
        response: ByteArray,
    ): ByteArray {
        if (localRandom.size != 8) {
            throw TelinkProtocolException("local random value must be 8 bytes")
        }
        if (response.size != 17 || response[0].unsigned() != PAIR_RESPONSE_OPCODE) {
            throw TelinkProtocolException("unexpected Telink pairing response")
        }
        val credential = credentials(meshName, password)
        val remoteRandom = response.copyOfRange(1, 9)
        val expectedProof = javaAesEncrypt(remoteRandom.copyOf(16), credential).copyOfRange(8, 16).reversedArray()
        if (!response.copyOfRange(1, response.size).contentEquals(remoteRandom + expectedProof)) {
            throw TelinkProtocolException("Telink pairing proof did not match the supplied credentials")
        }
        return javaAesEncrypt(credential, localRandom + remoteRandom).reversedArray()
    }

    /**
     * Parse the manufacturer payload after the two-byte company identifier.
     *
     * The Telink payload layout is mesh UUID, four MAC bytes, product UUID,
     * status, mesh address, then 16 vendor-defined bytes.
     */
    fun parseAdvertisement(
        address: String,
        name: String?,
        manufacturerData: ByteArray,
        rssi: Int?,
    ): AdvertisementInfo {
        if (manufacturerData.size < 11) {
            throw TelinkProtocolException("Telink advertisement is shorter than 11 bytes")
        }
        if (littleEndian16(manufacturerData, 0) != TELINK_COMPANY_ID) {
            throw TelinkProtocolException("manufacturer data is not the Telink mesh payload")
        }
        return AdvertisementInfo(
            address = address.uppercase(Locale.ROOT),
            name = name,
            meshUuid = littleEndian16(manufacturerData, 0),
            commandMacPrefix = littleEndian32(manufacturerData, 2),
            productUuid = littleEndian16(manufacturerData, 6),
            status = manufacturerData[8].unsigned(),
            meshAddress = littleEndian16(manufacturerData, 9),
            rssi = rssi,
        )
    }

    /**
     * Find the Telink payload in a raw scan record.
     *
     * Android's ScanRecord keeps only one manufacturer entry per company, but
     * these lights can advertise several. Prefer the one whose MAC bytes match
     * [address], then fall back to the last Telink mesh payload.
     */
    fun findAdvertisementManufacturerData(address: String, scanRecordData: ByteArray): ByteArray? {
        val expectedCommandPrefix = runCatching { commandMacPrefix(address) }.getOrNull()
        val candidates = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < scanRecordData.size) {
            val fieldLength = scanRecordData[offset].unsigned()
            if (fieldLength == 0) break
            val fieldEnd = offset + 1 + fieldLength
            if (fieldEnd > scanRecordData.size) break
            val fieldType = scanRecordData[offset + 1].unsigned()
            // One type byte, the two-byte company ID, and an 11-byte payload.
            val containsCompanyAndPayload = fieldLength >= 14
            if (
                fieldType == MANUFACTURER_SPECIFIC_DATA_TYPE &&
                containsCompanyAndPayload &&
                littleEndian16(scanRecordData, offset + 2) == TELINK_COMPANY_ID
            ) {
                val payload = scanRecordData.copyOfRange(offset + 4, fieldEnd)
                if (littleEndian16(payload, 0) == TELINK_COMPANY_ID) {
                    candidates += payload
                }
            }
            offset = fieldEnd
        }
        return candidates.lastOrNull { expectedCommandPrefix != null && littleEndian32(it, 2) == expectedCommandPrefix }
            ?: candidates.lastOrNull()
    }

    /** Build the unencrypted 20-byte command frame used by the vendor Android app. */
    fun buildCommandFrame(
        sequence: Int,
        meshAddress: Int,
        opcode: Int,
        parameters: ByteArray = ByteArray(0),
        vendorId: Int = TELINK_COMPANY_ID,
    ): ByteArray {
        if (sequence !in 0..0xFFFFFF) {
            throw TelinkProtocolException("sequence must fit in 24 bits")
        }
        if (meshAddress !in 0..0xFFFF) {
            throw TelinkProtocolException("mesh address must fit in 16 bits")
        }
        if (opcode !in 0..0xFF) {
            throw TelinkProtocolException("opcode must fit in one byte")
        }
        if (parameters.size > 10) {
            throw TelinkProtocolException("Telink command parameters may be at most 10 bytes")
        }

        val frame = ByteArray(20)
        frame[0] = sequence.toByte()
        frame[1] = (sequence ushr 8).toByte()
        frame[2] = (sequence ushr 16).toByte()
        // Telink's packet header stores the destination first, followed by an
        // opcode whose high command bits are set.
        frame[5] = meshAddress.toByte()
        frame[6] = (meshAddress ushr 8).toByte()
        frame[7] = (opcode or 0xC0).toByte()
        frame[8] = vendorId.toByte()
        frame[9] = (vendorId ushr 8).toByte()
        parameters.copyInto(frame, 10)
        return frame
    }

    /** Encrypt a command frame exactly as Telink's native Android routine does. */
    fun encryptCommandFrame(sessionKey: ByteArray, commandMacPrefix: Long, frame: ByteArray): ByteArray {
        if (sessionKey.size != 16 || frame.size != 20) {
            throw TelinkProtocolException("session key must be 16 bytes and frame must be 20 bytes")
        }
        if (commandMacPrefix !in 0..0xFFFFFFFFL) {
            throw TelinkProtocolException("Telink command MAC prefix must fit in 32 bits")
        }

        val sequence = frame[0].unsigned() or (frame[1].unsigned() shl 8) or (frame[2].unsigned() shl 16)
        val iv = commandMacPrefixBytes(commandMacPrefix) +
            byteArrayOf(1) +
            byteArrayOf(sequence.toByte(), (sequence ushr 8).toByte(), (sequence ushr 16).toByte())
        val encrypted = frame.copyOf()

        // Calculate the two-byte message integrity code over bytes 5 through 19.
        var integrity = ByteArray(16)
        iv.copyInto(integrity)
        integrity[8] = 15
        integrity = telinkAesBlock(sessionKey, integrity)
        for (index in 0 until 15) {
            val integrityIndex = index % 16
            integrity[integrityIndex] = (integrity[integrityIndex].toInt() xor encrypted[5 + index].toInt()).toByte()
            if (integrityIndex == 15 || index == 14) {
                integrity = telinkAesBlock(sessionKey, integrity)
            }
        }
        encrypted[3] = integrity[0]
        encrypted[4] = integrity[1]

        // Encrypt bytes 5 through 19 with the same one-byte-counter stream mode.
        val counter = ByteArray(16)
        iv.copyInto(counter, 1)
        var stream = ByteArray(0)
        for (index in 0 until 15) {
            if (index % 16 == 0) {
                stream = telinkAesBlock(sessionKey, counter)
                counter[0] = (counter[0].unsigned() + 1).toByte()
            }
            encrypted[5 + index] = (encrypted[5 + index].toInt() xor stream[index % 16].toInt()).toByte()
        }
        return encrypted
    }

    fun encryptCommandFrame(sessionKey: ByteArray, address: String, frame: ByteArray): ByteArray =
        encryptCommandFrame(sessionKey, commandMacPrefix(address), frame)

    fun onParameters(): ByteArray = byteArrayOf(1, 0, 0)

    fun offParameters(): ByteArray = byteArrayOf(0, 0, 0)

    /** Build the Briloner RGB parameter payload used by opcode `0xF1`. */
    fun rgbParameters(red: Int, green: Int, blue: Int, brightness: Int): ByteArray {
        if (brightness !in 1..100) {
            throw TelinkProtocolException("brightness must be from 1 through 100")
        }
        for ((name, channel) in listOf("red" to red, "green" to green, "blue" to blue)) {
            if (channel !in 0..255) {
                throw TelinkProtocolException("$name must be from 0 through 255")
            }
        }
        return byteArrayOf(brightness.toByte(), red.toByte(), green.toByte(), blue.toByte(), 0, 0, 0, 0)
    }

    /** Return a random non-zero 24-bit command sequence number. */
    fun randomSequence(): Int = secureRandom.nextInt(0xFFFFFE) + 1

    private fun padded16(value: String): ByteArray {
        val encoded = value.toByteArray(Charsets.UTF_8)
        if (encoded.size > 16) {
            throw TelinkProtocolException("mesh name and password must be at most 16 UTF-8 bytes")
        }
        return encoded.copyOf(16)
    }

    /** Return the first four little-endian Telink MAC bytes of a canonical address. */
    fun commandMacPrefix(address: String): Long {
        val parts = address.split(":")
        if (parts.size != 6 || parts.any { it.length != 2 }) {
            throw TelinkProtocolException("Bluetooth address must use AA:BB:CC:DD:EE:FF form")
        }
        val telinkBytes = try {
            parts.map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        } catch (exception: NumberFormatException) {
            throw TelinkProtocolException("Bluetooth address contains non-hexadecimal characters")
        }
        return littleEndian32(telinkBytes, 0)
    }

    fun commandMacPrefixBytes(commandMacPrefix: Long): ByteArray {
        if (commandMacPrefix !in 0..0xFFFFFFFFL) {
            throw TelinkProtocolException("Telink command MAC prefix must fit in 32 bits")
        }
        return byteArrayOf(
            commandMacPrefix.toByte(),
            (commandMacPrefix ushr 8).toByte(),
            (commandMacPrefix ushr 16).toByte(),
            (commandMacPrefix ushr 24).toByte(),
        )
    }

    private fun littleEndian16(data: ByteArray, offset: Int): Int =
        data[offset].unsigned() or (data[offset + 1].unsigned() shl 8)

    private fun littleEndian32(data: ByteArray, offset: Int): Long =
        data[offset].unsigned().toLong() or
            (data[offset + 1].unsigned().toLong() shl 8) or
            (data[offset + 2].unsigned().toLong() shl 16) or
            (data[offset + 3].unsigned().toLong() shl 24)
}

private fun Byte.unsigned(): Int = toInt() and 0xFF

private fun Int.hex(width: Int): String = toString(16).padStart(width, '0')

private fun Long.hex(width: Int): String = toString(16).padStart(width, '0')
