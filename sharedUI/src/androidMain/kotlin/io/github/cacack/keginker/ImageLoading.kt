package io.github.cacack.keginker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import io.github.cacack.keginker.image.GrayImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Longest side photos are decoded to; far more than any panel needs. */
private const val MAX_SIDE = 1024

/** Largest picked file read, in bytes: well above a 48 MP JPEG or HEIC. */
internal const val MAX_FILE_BYTES = 64 * 1024 * 1024

/**
 * Most pixels an image may have, checked from its header before decoding:
 * above the 108 MP of the largest common phone cameras. iOS has the same limit.
 */
internal const val MAX_PIXELS = 120_000_000L

/**
 * Decodes a picked image, downsampled, into greyscale. Upright on Android 9+;
 * older versions ignore EXIF rotation, so some camera photos come in sideways.
 * Files over [MAX_FILE_BYTES] and images over [MAX_PIXELS] fail.
 *
 * The file is read whole and decoded from memory, as on iOS. That also lets
 * the host test run it: Robolectric can't decode from a file descriptor.
 */
suspend fun loadGrayImage(context: Context, uri: Uri): GrayImage = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readAtMost(MAX_FILE_BYTES) }
        ?: throw IOException("Couldn't open $uri")
    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder applies EXIF rotation; BitmapFactory doesn't.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            checkPixels(info.size.width, info.size.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val largest = maxOf(info.size.width, info.size.height)
            if (largest > MAX_SIDE) decoder.setTargetSampleSize((largest + MAX_SIDE - 1) / MAX_SIDE)
        }
    } else {
        decodeWithBitmapFactory(bytes)
    }
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    GrayImage.fromArgb(bitmap.width, bitmap.height, pixels)
}

private fun decodeWithBitmapFactory(bytes: ByteArray): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    checkPixels(bounds.outWidth, bounds.outHeight)
    val options = BitmapFactory.Options().apply {
        inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / MAX_SIDE)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: throw IOException("Not a readable image")
}

/** Reads the whole stream, failing before it holds more than [limit] bytes. */
private fun InputStream.readAtMost(limit: Int): ByteArray {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read < 0) return bytes.toByteArray()
        if (bytes.size() + read > limit) throw IOException("File is over $limit bytes")
        bytes.write(buffer, 0, read)
    }
}

private fun checkPixels(width: Int, height: Int) {
    if (width.toLong() * height > MAX_PIXELS) throw IOException("Image is over $MAX_PIXELS pixels")
}

/** A black/white view image (`true` is white) as a bitmap for previewing. */
fun BooleanArray.toPreviewBitmap(width: Int): Bitmap {
    val colors = IntArray(size) { if (this[it]) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
    return Bitmap.createBitmap(colors, width, size / width, Bitmap.Config.ARGB_8888)
}
