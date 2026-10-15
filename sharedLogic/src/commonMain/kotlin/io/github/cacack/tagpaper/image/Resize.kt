package io.github.cacack.tagpaper.image

/**
 * Scales to exactly [width] × [height], centre-cropping to keep the aspect
 * ratio (never letterboxing). Each output pixel is the average of the source
 * pixels it covers.
 */
fun GrayImage.fill(width: Int, height: Int): GrayImage {
    require(width > 0 && height > 0) { "Target must be at least 1×1, was ${width}×$height" }
    val sourceIsWider = this.width.toLong() * height > this.height.toLong() * width
    val cropWidth = if (sourceIsWider) (this.height.toLong() * width / height).toInt() else this.width
    val cropHeight = if (sourceIsWider) this.height else (this.width.toLong() * height / width).toInt()
    val left = (this.width - cropWidth) / 2
    val top = (this.height - cropHeight) / 2

    /** Start of output cell [i] of [cells] across a crop of [span] starting at [origin]. */
    fun edge(origin: Int, i: Int, span: Int, cells: Int) = origin + (i.toLong() * span / cells).toInt()

    val out = ByteArray(width * height)
    for (ty in 0 until height) {
        val y0 = edge(top, ty, cropHeight, height)
        val y1 = maxOf(edge(top, ty + 1, cropHeight, height), y0 + 1)
        for (tx in 0 until width) {
            val x0 = edge(left, tx, cropWidth, width)
            val x1 = maxOf(edge(left, tx + 1, cropWidth, width), x0 + 1)
            var sum = 0L
            for (y in y0 until y1) for (x in x0 until x1) sum += gray(y * this.width + x)
            out[ty * width + tx] = (sum / ((y1 - y0) * (x1 - x0))).toInt().toByte()
        }
    }
    return GrayImage(width, height, out)
}
