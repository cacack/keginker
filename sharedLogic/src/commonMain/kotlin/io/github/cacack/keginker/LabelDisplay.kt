package io.github.cacack.keginker

import io.github.cacack.keginker.image.Dither
import io.github.cacack.keginker.image.GrayImage
import io.github.cacack.keginker.image.fill
import io.github.cacack.keginker.image.toBlackWhite
import io.github.cacack.keginker.waveshare.WavesharePanel

/**
 * How an EZ Sign panel's memory holds the view: memory is the view turned
 * this way. Native sizes follow, so a 90° turn swaps width and height.
 */
internal enum class ViewTurn {
    /** Memory is the view: view (x, y) is native (x, y). */
    NONE,

    /** View (x, y) is native (width − 1 − x, height − 1 − y). */
    TURNED_180,

    /** The view's left edge is memory's top: view (x, y) is native (height − 1 − y, x). */
    TURNED_90_CLOCKWISE,

    /** The view's top edge is memory's left: view (x, y) is native (y, width − 1 − x). */
    TURNED_90_COUNTER_CLOCKWISE,
    ;

    /** Memory width, for a view [viewWidth] × [viewHeight]. */
    fun nativeWidth(viewWidth: Int, viewHeight: Int): Int = if (swapsSides) viewHeight else viewWidth

    /** Memory height, for a view [viewWidth] × [viewHeight]. */
    fun nativeHeight(viewWidth: Int, viewHeight: Int): Int = if (swapsSides) viewWidth else viewHeight

    /** The row-major memory index of view pixel ([x], [y]) in a view [viewWidth] × [viewHeight]. */
    fun nativeIndex(x: Int, y: Int, viewWidth: Int, viewHeight: Int): Int {
        val nativeWidth = nativeWidth(viewWidth, viewHeight)
        return when (this) {
            NONE -> y * nativeWidth + x
            TURNED_180 -> (viewHeight - 1 - y) * nativeWidth + (viewWidth - 1 - x)
            TURNED_90_CLOCKWISE -> x * nativeWidth + (viewHeight - 1 - y)
            TURNED_90_COUNTER_CLOCKWISE -> (viewWidth - 1 - x) * nativeWidth + y
        }
    }

    private val swapsSides: Boolean get() = this == TURNED_90_CLOCKWISE || this == TURNED_90_COUNTER_CLOCKWISE
}

/**
 * A display the user writes labels to. Labels are composed portrait on
 * every display, at [viewWidth] × [viewHeight].
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
) {
    WAVESHARE_2IN7(
        title = "Waveshare 2.7\"",
        viewWidth = WavesharePanel.EPD_2IN7.viewWidth,
        viewHeight = WavesharePanel.EPD_2IN7.viewHeight,
        wavesharePanel = WavesharePanel.EPD_2IN7,
        ezSignTurn = null,
    ),

    /**
     * Native 128 × 296 per its A0 TLV. Held portrait, a square drawn at
     * native (0, 0) showed bottom right and native row 0 ran along the
     * bottom edge, so memory is the view turned 180° (#130, iPhone 15 Pro).
     */
    EZ_SIGN_2IN9(title = "EZ Sign 2.9\"", viewWidth = 128, viewHeight = 296, wavesharePanel = null, ezSignTurn = ViewTurn.TURNED_180),

    /**
     * Native 400 × 300 per its A0 TLV, which held landscape shows native
     * (0, 0) top left with native row 0 along the top (#131, iPhone 15 Pro).
     * The label is portrait with the landscape's left edge at its top, so
     * memory is the view turned 90° counter-clockwise.
     */
    EZ_SIGN_4IN2(title = "EZ Sign 4.2\"", viewWidth = 300, viewHeight = 400, wavesharePanel = null, ezSignTurn = ViewTurn.TURNED_90_COUNTER_CLOCKWISE),
    ;

    init {
        require((wavesharePanel == null) != (ezSignTurn == null)) { "$name must be exactly one of Waveshare or EZ Sign" }
    }

    val isEzSign: Boolean get() = ezSignTurn != null

    /** Width of the panel memory, in pixels: on EZ Sign displays, as its A0 TLV gives it. */
    val nativeWidth: Int get() = ezSignTurn?.nativeWidth(viewWidth, viewHeight) ?: wavesharePanel!!.width

    /** Height of the panel memory, in pixels: on EZ Sign displays, as its A0 TLV gives it. */
    val nativeHeight: Int get() = ezSignTurn?.nativeHeight(viewWidth, viewHeight) ?: wavesharePanel!!.height
}

/**
 * The black/white image exactly as [display] will show it: row-major,
 * [LabelDisplay.viewWidth] wide, `true` is white. The image is centre-cropped
 * to the view shape and scaled, as for a Waveshare panel.
 */
fun renderView(display: LabelDisplay, image: GrayImage, dither: Dither = Dither.FloydSteinberg): BooleanArray =
    image.fill(display.viewWidth, display.viewHeight).toBlackWhite(dither)
