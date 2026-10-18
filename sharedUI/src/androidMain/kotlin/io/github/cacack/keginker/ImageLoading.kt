package io.github.cacack.keginker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import io.github.cacack.keginker.image.GrayImage
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Longest side photos are decoded to; far more than any panel needs. */
private const val MAX_SIDE = 1024

/**
 * Decodes a picked image, downsampled, into greyscale. Upright on Android 9+;
 * older versions ignore EXIF rotation, so some camera photos come in sideways.
 *
 * The file is read whole and decoded from memory, as on iOS. That also lets
 * the host test run it: Robolectric can't decode from a file descriptor.
 */
suspend fun loadGrayImage(context: Context, uri: Uri): GrayImage = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IOException("Couldn't open $uri")
    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder applies EXIF rotation; BitmapFactory doesn't.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
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
    val options = BitmapFactory.Options().apply {
        inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / MAX_SIDE)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: throw IOException("Not a readable image")
}

/** A black/white view image (`true` is white) as a bitmap for previewing. */
fun BooleanArray.toPreviewBitmap(width: Int): Bitmap {
    val colors = IntArray(size) { if (this[it]) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
    return Bitmap.createBitmap(colors, width, size / width, Bitmap.Config.ARGB_8888)
}
