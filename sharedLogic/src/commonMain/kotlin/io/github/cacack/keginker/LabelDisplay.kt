package io.github.cacack.keginker

import io.github.cacack.keginker.image.BLACK
import io.github.cacack.keginker.image.ColorImage
import io.github.cacack.keginker.image.Dither
import io.github.cacack.keginker.image.Palette
import io.github.cacack.keginker.image.WHITE
import io.github.cacack.keginker.image.fill
import io.github.cacack.keginker.image.toBlackWhite
import io.github.cacack.keginker.image.toPalette
import io.github.cacack.keginker.waveshare.WavesharePanel

/** How an EZ Sign panel's memory holds the view, which is the memory's size. */
internal enum class ViewTurn {
    /** Memory is the view: view (x, y) is native (x, y). */
    NONE,

    /** View (x, y) is native (width − 1 − x, height − 1 − y). */
    TURNED_180,
    ;

    /** The row-major memory index of view pixel ([x], [y]) in a view [viewWidth] × [viewHeight]. */
    fun nativeIndex(x: Int, y: Int, viewWidth: Int, viewHeight: Int): Int = when (this) {
        NONE -> y * viewWidth + x
        TURNED_180 -> (viewHeight - 1 - y) * viewWidth + (viewWidth - 1 - x)
    }
}

/**
 * A display the user writes labels to. Labels are composed at [viewWidth] ×
 * [viewHeight], the way up the display is read: portrait, except the 4.2".
 *
 * The enum name is the stable key the apps persist to remember the user's
 * choice: rename an entry and the apps forget it.
 */
enum class LabelDisplay(
    /** What the user sees in the display picker. */
    val title: String,
    val viewWidth: Int,
    val viewHeight: Int,
    /** The Waveshare panel; null on EZ Sign displays. */
    val wavesharePanel: WavesharePanel?,
    /** How an EZ Sign panel's memory holds the view; null on Waveshare displays, whose panel says. */
    internal val ezSignTurn: ViewTurn?,
    /** The colors it shows. */
    val palette: Palette,
) {
    WAVESHARE_2IN7(
        title = "Waveshare 2.7\"",
        viewWidth = WavesharePanel.EPD_2IN7.viewWidth,
        viewHeight = WavesharePanel.EPD_2IN7.viewHeight,
        wavesharePanel = WavesharePanel.EPD_2IN7,
        ezSignTurn = null,
        palette = Palette.BLACK_WHITE,
    ),

    /**
     * Native 128 × 296 per its A0 TLV. Held portrait, a square drawn at
     * native (0, 0) showed bottom right and native row 0 ran along the
     * bottom edge, so memory is the view turned 180° (#130, iPhone 15 Pro).
     */
    EZ_SIGN_2IN9(
        title = "EZ Sign 2.9\"",
        viewWidth = 128,
        viewHeight = 296,
        wavesharePanel = null,
        ezSignTurn = ViewTurn.TURNED_180,
        palette = Palette.BLACK_WHITE_YELLOW_RED,
    ),

    /**
     * Native 400 × 300 per its A0 TLV. It's read landscape, with its wider
     * border along the bottom; held so, native (0, 0) shows top left with
     * native row 0 along the top (#131, iPhone 15 Pro), so memory is the view.
     */
    EZ_SIGN_4IN2(
        title = "EZ Sign 4.2\"",
        viewWidth = 400,
        viewHeight = 300,
        wavesharePanel = null,
        ezSignTurn = ViewTurn.NONE,
        palette = Palette.BLACK_WHITE_YELLOW_RED,
    ),
    ;

    init {
        require((wavesharePanel == null) != (ezSignTurn == null)) { "$name must be exactly one of Waveshare or EZ Sign" }
    }

    val isEzSign: Boolean get() = ezSignTurn != null

    /** Width of the panel memory, in pixels: on EZ Sign displays, as its A0 TLV gives it. */
    val nativeWidth: Int get() = if (isEzSign) viewWidth else wavesharePanel!!.width

    /** Height of the panel memory, in pixels: on EZ Sign displays, as its A0 TLV gives it. */
    val nativeHeight: Int get() = if (isEzSign) viewHeight else wavesharePanel!!.height
}

/**
 * [image] center-cropped and scaled to [display]'s view, ready for
 * [renderView]: in color where the display shows it, otherwise just the gray.
 */
fun fitView(display: LabelDisplay, image: ColorImage): ColorImage =
    if (display.palette.hasColor) {
        image.fill(display.viewWidth, display.viewHeight)
    } else {
        ColorImage.fromGray(image.gray.fill(display.viewWidth, display.viewHeight))
    }

/**
 * The image exactly as [display] will show it: row-major indices into its
 * [LabelDisplay.palette], [LabelDisplay.viewWidth] wide. The image is
 * center-cropped to the view shape and scaled, as for a Waveshare panel.
 * In [color] where the display shows it; otherwise black and white.
 */
fun renderView(
    display: LabelDisplay,
    image: ColorImage,
    dither: Dither = Dither.FloydSteinberg,
    color: Boolean = true,
): ByteArray {
    if (color && display.palette.hasColor) {
        return image.fill(display.viewWidth, display.viewHeight).toPalette(display.palette, dither)
    }
    val white = image.gray.fill(display.viewWidth, display.viewHeight).toBlackWhite(dither)
    return ByteArray(white.size) { if (white[it]) WHITE else BLACK }
}
