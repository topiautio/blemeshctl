package io.github.topiautio.blemeshctl.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ADDRESS = "A4:C1:38:93:1B:72"

private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

// The vectors below are the same ones used by tests/test_protocol.py.
class AdvertisementTests {
    @Test
    fun parsesRealTelinkLayout() {
        val info = TelinkProtocol.parseAdvertisement(
            ADDRESS.lowercase(),
            "BleMesh",
            hex("1102721b93380400016700000102030405060708090a0b0c0d0e0f"),
            -46,
        )

        assertEquals(0x0211, info.meshUuid)
        assertEquals(0x38931b72L, info.commandMacPrefix)
        assertEquals(0x0004, info.productUuid)
        assertEquals(0x01, info.status)
        assertEquals(0x0067, info.meshAddress)
        assertEquals(ADDRESS, info.address)
        assertEquals(TelinkProtocol.commandMacPrefix(ADDRESS), info.commandMacPrefix)
    }

    @Test
    fun rejectsShortAndForeignPayloads() {
        assertThrows(TelinkProtocolException::class.java) {
            TelinkProtocol.parseAdvertisement(ADDRESS, null, hex("1102721b9338"), null)
        }
        assertThrows(TelinkProtocolException::class.java) {
            TelinkProtocol.parseAdvertisement(ADDRESS, null, hex("ffff721b93380400016700"), null)
        }
    }

    @Test
    fun scanRecordPrefersThePayloadForThisAddress() {
        val other = hex("110201020304040001670000")
        val matching = hex("1102721b9338040001680000")
        // Two manufacturer fields (length, type 0xFF, company 0x0211, payload) and a flags field.
        val record = hex("020106") +
            byteArrayOf((other.size + 3).toByte(), 0xFF.toByte(), 0x11, 0x02) + other +
            byteArrayOf((matching.size + 3).toByte(), 0xFF.toByte(), 0x11, 0x02) + matching

        assertArrayEquals(matching, TelinkProtocol.findAdvertisementManufacturerData(ADDRESS, record))
        // An unrelated address falls back to the last Telink payload.
        assertArrayEquals(matching, TelinkProtocol.findAdvertisementManufacturerData("00:11:22:33:44:55", record))
        assertNull(TelinkProtocol.findAdvertisementManufacturerData(ADDRESS, hex("020106")))
    }

    @Test
    fun scanRecordStopsAtTruncatedField() {
        val payload = hex("1102721b9338040001680000")
        val record = byteArrayOf(40, 0xFF.toByte(), 0x11, 0x02) + payload

        assertNull(TelinkProtocol.findAdvertisementManufacturerData(ADDRESS, record))
    }
}

class LoginTests {
    @Test
    fun pairRequestAndSessionKeyMatchReferenceVector() {
        val localRandom = hex("0102030405060708")
        val remoteRandom = hex("1020304050607080")
        val credential = TelinkProtocol.credentials("BleMesh", "mesh123")

        val request = TelinkProtocol.buildPairRequest("BleMesh", "mesh123", localRandom)

        assertArrayEquals(localRandom, request.localRandom)
        assertEquals(0x0C, request.bytes[0].toInt())
        assertArrayEquals(localRandom, request.bytes.copyOfRange(1, 9))
        assertArrayEquals(
            TelinkProtocol.javaAesEncrypt(localRandom.copyOf(16), credential).copyOfRange(8, 16).reversedArray(),
            request.bytes.copyOfRange(9, 17),
        )

        val response = byteArrayOf(0x0D) + remoteRandom +
            TelinkProtocol.javaAesEncrypt(remoteRandom.copyOf(16), credential).copyOfRange(8, 16).reversedArray()
        assertEquals(
            "749c11ec6ea3d3a0cd7824290eb3bff0",
            TelinkProtocol.deriveSessionKey("BleMesh", "mesh123", localRandom, response).toHex(),
        )
    }

    @Test
    fun wrongCredentialsAreRejected() {
        val localRandom = hex("0102030405060708")
        val remoteRandom = hex("1020304050607080")
        val credential = TelinkProtocol.credentials("BleMesh", "mesh123")
        val response = byteArrayOf(0x0D) + remoteRandom +
            TelinkProtocol.javaAesEncrypt(remoteRandom.copyOf(16), credential).copyOfRange(8, 16).reversedArray()

        val error = assertThrows(TelinkProtocolException::class.java) {
            TelinkProtocol.deriveSessionKey("BleMesh", "wrong", localRandom, response)
        }
        assertEquals("Telink pairing proof did not match the supplied credentials", error.message)
        assertThrows(TelinkProtocolException::class.java) {
            TelinkProtocol.deriveSessionKey("BleMesh", "mesh123", localRandom, byteArrayOf(0x0E) + ByteArray(16))
        }
    }

    @Test
    fun credentialsLongerThanSixteenBytesAreRejected() {
        assertThrows(TelinkProtocolException::class.java) {
            TelinkProtocol.credentials("BleMesh", "x".repeat(17))
        }
    }
}

class CommandTests {
    @Test
    fun frameUsesAddressThenOpcodeLayout() {
        val frame = TelinkProtocol.buildCommandFrame(
            0x010203,
            0x0067,
            TelinkProtocol.COMMAND_ON_OFF,
            TelinkProtocol.onParameters(),
        )

        assertEquals("030201", frame.copyOfRange(0, 3).toHex())
        assertEquals("0000", frame.copyOfRange(3, 5).toHex())
        assertEquals("6700f01102", frame.copyOfRange(5, 10).toHex())
        assertEquals("010000", frame.copyOfRange(10, 13).toHex())
    }

    @Test
    fun encryptionMatchesNativeReferenceVector() {
        val sessionKey = hex("749c11ec6ea3d3a0cd7824290eb3bff0")
        val frame = hex("03020100006700f0110201000000000000000000")
        val expected = "030201f064d3c71b2c56b5591742f6f3fe30dc57"

        assertEquals(expected, TelinkProtocol.encryptCommandFrame(sessionKey, ADDRESS, frame).toHex())
        assertEquals(
            expected,
            TelinkProtocol.encryptCommandFrame(sessionKey, TelinkProtocol.commandMacPrefix(ADDRESS), frame).toHex(),
        )
        // The input frame must not be modified in place.
        assertEquals("03020100006700f0110201000000000000000000", frame.toHex())
    }

    @Test
    fun rgbParameters() {
        assertArrayEquals(
            byteArrayOf(100, 0, 255.toByte(), 0, 0, 0, 0, 0),
            TelinkProtocol.rgbParameters(0, 255, 0, 100),
        )
        assertThrows(TelinkProtocolException::class.java) { TelinkProtocol.rgbParameters(0, 0, 0, 0) }
        assertThrows(TelinkProtocolException::class.java) { TelinkProtocol.rgbParameters(256, 0, 0, 50) }
    }

    @Test
    fun parsesConventionalRgb() {
        assertEquals(Rgb(0, 255, 0), TelinkProtocol.parseRgb("#00ff00"))
        assertEquals(Rgb(0x12, 0x34, 0x56), TelinkProtocol.parseRgb("123456"))
        assertThrows(TelinkProtocolException::class.java) { TelinkProtocol.parseRgb("gg0000") }
        assertThrows(TelinkProtocolException::class.java) { TelinkProtocol.parseRgb("fff") }
    }

    @Test
    fun rejectsMalformedAddresses() {
        assertThrows(TelinkProtocolException::class.java) { TelinkProtocol.commandMacPrefix("A4:C1:38:93:1B") }
        assertThrows(TelinkProtocolException::class.java) { TelinkProtocol.commandMacPrefix("A4:C1:38:93:1B:ZZ") }
    }

    @Test
    fun randomSequenceIsNonZeroAndFitsInTwentyFourBits() {
        repeat(1_000) {
            val sequence = TelinkProtocol.randomSequence()
            assertTrue("sequence $sequence is out of range", sequence in 1..0xFFFFFE)
        }
    }
}
