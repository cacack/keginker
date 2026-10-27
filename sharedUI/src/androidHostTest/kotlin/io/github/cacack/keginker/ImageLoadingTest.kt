package io.github.cacack.keginker

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.cacack.keginker.image.WHITE
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Loads each fixture photo in `fixtures/photos` through [loadImage] and
 * checks its view against the golden beside it, pixel for pixel. The iOS
 * loader test (`iosAppTests/ImageLoadingTests.swift`) checks the same goldens.
 *
 * A missing golden is written from this loader and the test fails, so a new
 * fixture's golden is reviewed by eye before it's committed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImageLoadingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fixtures = File(System.getProperty("keginker.photoFixtures") ?: error("keginker.photoFixtures isn't set"))
    private val display = LabelDisplay.WAVESHARE_2IN7

    @Test
    fun exifRotatedPhotoComesInUpright() = checkGolden("exif-rotated.jpg")

    @Test
    fun transparencyIsWhite() = checkGolden("transparent.png")

    @Test
    fun coloursBecomeRec601Greys() = checkGolden("saturated.png")

    @Test
    fun wideImageIsCentreCropped() = checkGolden("wide.png")

    @Test
    fun tallImageIsCentreCropped() = checkGolden("tall.png")

    @Test
    fun tinyImageIsScaledUp() = checkGolden("tiny.png")

    @Test
    fun unreadableImageFails() {
        val notAnImage = File.createTempFile("not-an-image", ".png").apply {
            deleteOnExit()
            writeText("not an image")
        }
        // WriteViewModel shows "Couldn't open that image." for any failure;
        // the loader's own failures are IOExceptions.
        assertFailsWith<IOException> { runBlocking { loadImage(context, Uri.fromFile(notAnImage)) } }
    }

    @Test
    fun photo48MpStillLoads() {
        runBlocking { loadImage(context, Uri.fromFile(File(fixtures, "48mp.png"))) }
    }

    @Test
    fun imageOverPixelLimitFails() {
        val photo = File(fixtures, "over-pixel-limit.png")
        assertFailsWith<IOException> { runBlocking { loadImage(context, Uri.fromFile(photo)) } }
    }

    @Test
    fun fileOverSizeLimitFails() {
        // A readable image, padded past the limit; decoders ignore what follows it.
        val padded = File.createTempFile("over-size-limit", ".png").apply {
            deleteOnExit()
            writeBytes(File(fixtures, "tiny.png").readBytes())
            appendBytes(ByteArray(MAX_FILE_BYTES))
        }
        assertFailsWith<IOException> { runBlocking { loadImage(context, Uri.fromFile(padded)) } }
    }

    private fun checkGolden(name: String) {
        val view = renderView(display, runBlocking { loadImage(context, Uri.fromFile(File(fixtures, name))) }).map { it == WHITE }.toBooleanArray()
        val golden = File(fixtures, name.substringBeforeLast('.') + ".pbm")
        if (!golden.exists()) {
            golden.writeText(toPbm(view, display.viewWidth))
            fail("Wrote missing golden $golden; check it by eye and commit it")
        }
        val expected = fromPbm(golden.readText())
        assertEquals(expected.size, view.size, "$name: view size")
        val differing = view.indices.count { view[it] != expected[it] }
        assertEquals(0, differing, "$name: pixels that differ from $golden")
    }
}

/** A plain (`P1`) PBM, one image row per line; PBM's 1 is black. */
private fun toPbm(white: BooleanArray, width: Int): String = buildString {
    append("P1\n$width ${white.size / width}\n")
    white.toList().chunked(width).forEach { row -> row.joinTo(this, "") { if (it) "0" else "1" }.append('\n') }
}

/** Reads [toPbm]'s output back into a view (`true` is white). */
private fun fromPbm(text: String): BooleanArray {
    val lines = text.lines()
    require(lines[0] == "P1") { "Not a plain PBM" }
    return lines.drop(2).joinToString("").map { it == '0' }.toBooleanArray()
}
