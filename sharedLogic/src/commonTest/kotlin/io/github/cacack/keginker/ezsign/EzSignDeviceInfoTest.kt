package io.github.cacack.keginker.ezsign

import io.github.cacack.keginker.image.forEachSeed
import io.github.cacack.keginker.probe.EZ_SIGN_29_DEVICE_INFO
import io.github.cacack.keginker.probe.EZ_SIGN_42_DEVICE_INFO
import io.github.cacack.keginker.probe.MADE_UP_ID
import io.github.cacack.keginker.probe.MADE_UP_SERIAL
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private val OK = bytes(0x90, 0x00)

private fun decoded(reply: ByteArray) = assertIs<EzSignDeviceInfoParse.Decoded>(parseEzSignDeviceInfo(reply)).info

private fun reason(reply: ByteArray) = assertIs<EzSignDeviceInfoParse.NotDecoded>(parseEzSignDeviceInfo(reply)).reason

/**
 * A random device-information reply: TLVs that may include an A0 (with a
 * known colour mode or not), a C0 and a C1, maybe cut short, maybe with a
 * status other than `90 00`, or sometimes just random bytes.
 */
internal fun Random.nextDeviceInfoReply(serial: ByteArray, id: ByteArray): ByteArray {
    if (nextInt(8) == 0) return nextBytes(nextInt(0, 40))
    val tlvs = mutableListOf<ByteArray>()
    if (nextBoolean()) {
        val mode = listOf(0x01, 0x07, 0x47, nextInt(256)).random(this)
        tlvs += bytes(0xA0, 7, nextInt(256), mode) + nextBytes(5)
    }
    if (nextBoolean()) tlvs += bytes(0xC0, serial.size) + serial
    if (nextBoolean()) tlvs += bytes(0xC1, id.size) + id
    repeat(nextInt(0, 4)) {
        val tag = (0..0xFF).filter { it != 0xC0 && it != 0xC1 }.random(this)
        val value = nextBytes(nextInt(0, 9))
        tlvs += bytes(tag, value.size) + value
    }
    tlvs.shuffle(this)
    var data = tlvs.fold(ByteArray(0)) { all, tlv -> all + tlv }
    if (data.isNotEmpty() && nextInt(4) == 0) data = data.copyOfRange(0, nextInt(data.size))
    return data + if (nextInt(6) == 0) bytes(0x6A, 0x82) else OK
}

class EzSignDeviceInfoTest {
    @Test
    fun aPortraitA0IsShownLandscape() {
        val info = decoded(EZ_SIGN_29_DEVICE_INFO + OK)

        assertEquals(0x07, info.colourMode)
        assertEquals(2, info.bitsPerPixel)
        assertEquals(listOf("black", "white", "yellow", "red"), info.colourNames)
        assertEquals(0x20, info.rowsPerBlock)
        assertEquals(128 to 296, info.nativeWidth to info.nativeHeight)
        assertEquals(296 to 128, info.viewWidth to info.viewHeight)
    }

    @Test
    fun aLandscapeA0KeepsItsLayout() {
        val info = decoded(EZ_SIGN_42_DEVICE_INFO + OK)

        assertEquals(400 to 300, info.nativeWidth to info.nativeHeight)
        assertEquals(400 to 300, info.viewWidth to info.viewHeight)
        assertEquals(4, info.colourNames.size)
    }

    @Test
    fun theOtherTlvsAreKeptRawButNeverTheSerialOrId() {
        val info = decoded(EZ_SIGN_42_DEVICE_INFO + OK)

        assertEquals(listOf(0xA1, 0xB1, 0xB2, 0xB3, 0xD1), info.otherTlvs.keys.toList())
        assertContentEquals(bytes(0x01, 0x12, 0x00, 0x30, 0xFF, 0xFF, 0xFF), info.otherTlvs[0xA1])
        assertContentEquals(bytes(0x08), info.otherTlvs[0xB1])
        info.otherTlvs.values.forEach {
            assertFalse(it.contentEquals(MADE_UP_SERIAL) || it.contentEquals(MADE_UP_ID))
        }
    }

    @Test
    fun aOneBitModeKeepsTheRawHeight() {
        // Colour mode 47: 1 bpp, so the raw height is the height.
        val info = decoded(bytes(0xA0, 0x07, 0x00, 0x47, 0x20, 0x01, 0x2C, 0x01, 0x90) + OK)

        assertEquals(1, info.bitsPerPixel)
        assertEquals(listOf("black", "white"), info.colourNames)
        assertEquals(400 to 300, info.viewWidth to info.viewHeight)
    }

    @Test
    fun anUnknownColourModeIsNotDecoded() {
        assertEquals(
            "colour mode 99 isn't one alt-core/nfc-eink knows",
            reason(bytes(0xA0, 0x07, 0xF0, 0x99, 0x20, 0x02, 0x58, 0x01, 0x90) + OK),
        )
    }

    @Test
    fun aTruncatedTlvIsNotDecoded() {
        // The C0 claims 10 bytes; 4 follow.
        val reply = bytes(0xA0, 0x07, 0xF0, 0x07, 0x20, 0x02, 0x58, 0x01, 0x90, 0xC0, 0x0A) + MADE_UP_SERIAL.copyOfRange(0, 4) + OK

        assertEquals("its TLVs are malformed", reason(reply))
    }

    @Test
    fun aMissingOrShortA0IsNotDecoded() {
        assertEquals("it has no A0 TLV", reason(bytes(0xA1, 0x01, 0x00) + OK))
        assertEquals("its A0 TLV is 2 bytes, not 7", reason(bytes(0xA0, 0x02, 0xF0, 0x07) + OK))
    }

    @Test
    fun aRefusedQueryIsNotDecoded() {
        assertEquals("the tag refused the query (status 6D 00)", reason(bytes(0x6D, 0x00)))
        assertEquals("the reply doesn't end in 90 00", reason(EZ_SIGN_42_DEVICE_INFO))
        assertEquals("the reply is too short to hold a status", reason(bytes(0x90)))
    }

    @Test
    fun randomRepliesNeverThrowAndNeverKeepTheSerialOrId() = forEachSeed { random ->
        val serial = random.nextBytes(10)
        val id = random.nextBytes(4)
        val reply = random.nextDeviceInfoReply(serial, id)

        when (val parsed = parseEzSignDeviceInfo(reply)) {
            is EzSignDeviceInfoParse.Decoded -> parsed.info.otherTlvs.forEach { (tag, value) ->
                assertTrue(tag != 0xC0 && tag != 0xC1 && tag != 0xA0, "tag $tag kept")
                assertFalse(value.contentEquals(serial) || value.contentEquals(id))
            }
            is EzSignDeviceInfoParse.NotDecoded -> assertTrue(parsed.reason.isNotEmpty())
        }
    }
}
