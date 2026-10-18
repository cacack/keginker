package io.github.cacack.keginker.image

import kotlin.random.Random

/** Seeded cases each property runs. */
const val PROPERTY_CASES = 100

/**
 * Runs [check] once per case in [cases], naming the failing case (as
 * "[label] N") so it can be replayed.
 */
inline fun forEachCase(cases: IntRange, label: String, check: (Int) -> Unit) {
    for (case in cases) {
        try {
            check(case)
        } catch (e: Throwable) {
            // Any failure, not just assertions: an exception from the code under test needs its case too.
            throw AssertionError("Failed for $label $case: $e", e)
        }
    }
}

/** Runs [check] with [PROPERTY_CASES] generators seeded 0, 1, 2 …; a failure names its seed. */
inline fun forEachSeed(check: (Random) -> Unit) =
    forEachCase(0 until PROPERTY_CASES, "seed") { check(Random(it)) }

/** A random image up to [maxSide] pixels on each side. */
fun Random.nextGrayImage(maxSide: Int = 32): GrayImage {
    val width = nextInt(1, maxSide + 1)
    val height = nextInt(1, maxSide + 1)
    return GrayImage(width, height, nextBytes(width * height))
}

/** The image flipped left to right. */
fun GrayImage.mirrored() = GrayImage(width, height, ByteArray(width * height) { pixels[it - it % width + width - 1 - it % width] })

/** The image flipped top to bottom. */
fun GrayImage.flipped() = GrayImage(width, height, ByteArray(width * height) { pixels[(height - 1 - it / width) * width + it % width] })

/** Pixel values row-major, as 0–255. */
fun GrayImage.grays() = List(width * height) { gray(it) }
