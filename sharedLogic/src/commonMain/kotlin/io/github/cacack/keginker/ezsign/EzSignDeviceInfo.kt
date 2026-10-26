/*
 * The A0 decoding follows alt-core/nfc-eink's src/nfc_eink/device.py
 * (parse_device_info; https://github.com/alt-core/nfc-eink, commit 682519f),
 * licensed under the MIT License.
 *
 * Modified: ported from Python to Kotlin; a malformed TLV fails the decode
 * instead of ending the walk; failures are returned as a reason rather than
 * raised; C0 and C1 are never kept; the native layout is kept beside the view.
 */
package io.github.cacack.keginker.ezsign

import io.github.cacack.keginker.waveshare.toHex

/** Where the decoding of an EZ Sign device-information reply comes from, for reports. */
const val EZ_SIGN_DECODER_SOURCE = "alt-core/nfc-eink https://github.com/alt-core/nfc-eink/blob/" +
    "682519f87aa6e422930d0f7a178eaa2d0d9640da/src/nfc_eink/device.py"

/** The device-information TLVs that identify one device, never kept and never reported: C0 its serial, C1 its ID. */
internal val DEVICE_ID_TLVS = setOf(0xC0, 0xC1)

/** The A0 TLV: `[flags, colour mode, rows per block, height × bpp (2 bytes), width (2 bytes)]`. */
private const val PANEL_TLV = 0xA0
private const val PANEL_TLV_BYTES = 7

/**
 * Most pixels a decoded panel may have. Real EZ Sign panels top out well
 * under 1 million pixels, so a reply that claims more is garbled; its test
 * patterns would cost more memory than a phone should spend on them.
 */
internal const val MAX_PANEL_PIXELS = 2_000_000L

/** Bits per pixel for each A0 colour mode alt-core knows. */
private val BITS_PER_PIXEL = mapOf(0x01 to 1, 0x07 to 2, 0x47 to 1)

/** Colour names by pixel value, per alt-core: 1 bpp is black and white, 2 bpp adds yellow and red. */
private val COLOUR_NAMES = listOf("black", "white", "yellow", "red")

/**
 * What an EZ Sign tag's device-information reply says about its panel.
 * Native sizes are as the A0 TLV gives them; the view is the panel held
 * landscape, so it swaps them when the native width is the shorter side.
 */
class EzSignDeviceInfo internal constructor(
    /** The A0 colour-mode byte. */
    val colourMode: Int,
    val bitsPerPixel: Int,
    /** By pixel value: index 0 is black, 1 white, and on 2 bpp panels 2 yellow, 3 red. */
    val colourNames: List<String>,
    val rowsPerBlock: Int,
    val nativeWidth: Int,
    val nativeHeight: Int,
    val viewWidth: Int,
    val viewHeight: Int,
    /** The reply's other TLVs by tag, in reply order, as raw values; never A0, C0 or C1. */
    val otherTlvs: Map<Int, ByteArray>,
)

/** The outcome of [parseEzSignDeviceInfo]. */
sealed interface EzSignDeviceInfoParse {
    class Decoded(val info: EzSignDeviceInfo) : EzSignDeviceInfoParse

    /** [reason] is plain English and quotes no device identifier. */
    class NotDecoded(val reason: String) : EzSignDeviceInfoParse
}

/**
 * Decodes the reply to the EZ Sign "Get Device Information" query: TLVs,
 * then `90 00`. Never throws; a reply it can't decode gets the reason.
 */
fun parseEzSignDeviceInfo(reply: ByteArray): EzSignDeviceInfoParse {
    fun fail(reason: String) = EzSignDeviceInfoParse.NotDecoded(reason)
    if (reply.size < 2) return fail("the reply is too short to hold a status")
    val ok = reply[reply.size - 2] == 0x90.toByte() && reply[reply.size - 1] == 0x00.toByte()
    if (!ok) {
        // A longer reply's last two bytes may be part of a serial, so only a bare status word is quoted.
        return fail(if (reply.size == 2) "the tag refused the query (status ${reply.toHex()})" else "the reply doesn't end in 90 00")
    }
    val end = reply.size - 2
    val tlvs = ezSignTlvs(reply, end) ?: return fail("its TLVs are malformed")
    val values = linkedMapOf<Int, ByteArray>()
    // A repeated tag keeps its last value, as alt-core does.
    for (tlv in tlvs) values[tlv.tag] = reply.copyOfRange(tlv.valueStart, tlv.valueEnd)
    val a0 = values[PANEL_TLV] ?: return fail("it has no A0 TLV")
    if (a0.size < PANEL_TLV_BYTES) return fail("its A0 TLV is ${a0.size} bytes, not $PANEL_TLV_BYTES")
    fun u8(index: Int) = a0[index].toInt() and 0xFF
    val colourMode = u8(1)
    val bitsPerPixel = BITS_PER_PIXEL[colourMode]
        ?: return fail("colour mode ${a0.copyOfRange(1, 2).toHex()} isn't one alt-core/nfc-eink knows")
    val nativeWidth = u8(5) shl 8 or u8(6)
    // A0 holds the height times the bits per pixel.
    val nativeHeight = (u8(3) shl 8 or u8(4)) / bitsPerPixel
    val size = "$nativeWidth × $nativeHeight"
    if (nativeWidth == 0 || nativeHeight == 0) return fail("its panel is $size, which has no pixels")
    if (nativeWidth.toLong() * nativeHeight > MAX_PANEL_PIXELS) {
        return fail("its panel is $size, more than the $MAX_PANEL_PIXELS pixels KegInker accepts")
    }
    val portrait = nativeWidth < nativeHeight
    return EzSignDeviceInfoParse.Decoded(
        EzSignDeviceInfo(
            colourMode = colourMode,
            bitsPerPixel = bitsPerPixel,
            colourNames = COLOUR_NAMES.take(1 shl bitsPerPixel),
            rowsPerBlock = u8(2),
            nativeWidth = nativeWidth,
            nativeHeight = nativeHeight,
            viewWidth = if (portrait) nativeHeight else nativeWidth,
            viewHeight = if (portrait) nativeWidth else nativeHeight,
            otherTlvs = values.filterKeys { it != PANEL_TLV && it !in DEVICE_ID_TLVS },
        ),
    )
}

/** One TLV in a reply: its tag, and where its value lies. */
internal class EzSignTlv(val tag: Int, val valueStart: Int, val valueEnd: Int)

/**
 * The TLVs (one-byte tag, one-byte length, value) in [reply] before [end];
 * null when they don't fill it exactly, as when the last one runs past it.
 */
internal fun ezSignTlvs(reply: ByteArray, end: Int): List<EzSignTlv>? {
    val tlvs = mutableListOf<EzSignTlv>()
    var at = 0
    while (at < end) {
        if (at + 2 > end) return null
        val valueStart = at + 2
        val valueEnd = valueStart + (reply[at + 1].toInt() and 0xFF)
        if (valueEnd > end) return null
        tlvs += EzSignTlv(reply[at].toInt() and 0xFF, valueStart, valueEnd)
        at = valueEnd
    }
    return tlvs
}
