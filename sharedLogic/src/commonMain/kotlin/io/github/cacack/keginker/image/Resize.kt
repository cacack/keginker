package io.github.cacack.keginker.image

/**
 * Scales to exactly [width] × [height], center-cropping to keep the aspect
 * ratio (never letterboxing). Each output pixel is the average of the source
 * pixels it covers, weighted by how much of each it covers, so the crop and
 * the scaling are symmetric: mirroring the input mirrors the output.
 */
fun GrayImage.fill(width: Int, height: Int): GrayImage {
    require(width > 0 && height > 0) { "Target must be at least 1×1, was ${width}×$height" }
    // Measured in units of 1 / (2 × width × height) of a source pixel, the
    // centered crop and every output cell have whole-unit edges. The crop is
    // the largest region of the target's aspect ratio: in source pixels it is
    // crop / height wide and crop / width tall, where crop is the smaller of
    // the two cross-products. In units that is 2 × crop × width across and
    // 2 × crop × height down, so each of the width × height cells is
    // 2 × crop units on both axes, and the centring margin on each axis is
    // half the leftover span. A cell's weights along an axis sum to its
    // cellSize, so its total weight is cellSize², i.e. cellArea.
    val unit = 2L * width * height
    val crop = minOf(this.width.toLong() * height, this.height.toLong() * width)
    val columns = coverage(start = (this.width.toLong() * height - crop) * width, cellSize = 2 * crop, unit = unit, cells = width)
    val rows = coverage(start = (this.height.toLong() * width - crop) * height, cellSize = 2 * crop, unit = unit, cells = height)
    val cellArea = 4 * crop * crop

    val out = ByteArray(width * height)
    for (ty in 0 until height) {
        val row = rows[ty]
        for (tx in 0 until width) {
            val column = columns[tx]
            var sum = 0L
            for (j in row.weights.indices) {
                val offset = (row.first + j) * this.width + column.first
                var line = 0L
                for (k in column.weights.indices) line += column.weights[k] * gray(offset + k)
                sum += row.weights[j] * line
            }
            out[ty * width + tx] = ((sum + cellArea / 2) / cellArea).toInt().toByte()
        }
    }
    return GrayImage(width, height, out)
}

/** The source pixels from [first] on that one output cell overlaps along an axis, and by how many units each. */
private class Coverage(val first: Int, val weights: LongArray)

/** Coverage of [cells] adjacent output cells, [cellSize] units each from [start], over source pixels of [unit] units. */
private fun coverage(start: Long, cellSize: Long, unit: Long, cells: Int) = Array(cells) { i ->
    val from = start + i * cellSize
    val to = from + cellSize
    val first = (from / unit).toInt()
    val last = ((to - 1) / unit).toInt()
    Coverage(first, LongArray(last - first + 1) { k ->
        val pixel = (first + k) * unit
        minOf(to, pixel + unit) - maxOf(from, pixel)
    })
}
