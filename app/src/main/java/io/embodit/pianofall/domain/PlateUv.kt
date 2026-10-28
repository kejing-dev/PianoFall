package io.embodit.pianofall.domain

/**
 * Texture coordinates of every textured quad (taste profile D10, the mirrored / upside-down plates of Chime
 * Bells, Light Planner, Sky Dome and Fire Plan): the engine samples a bitmap with v = 0 at its BOTTOM row, so a
 * quad's top edge takes v = 1. `PlateUvTest` checks it; the debug `platetest` shows an asymmetric "F↑" plate.
 */
object PlateUv {
    const val V_BOTTOM = 0f
    const val V_TOP = 1f

    /** Bitmap row (0 = top, as a fraction of the height) that the engine samples at [v]. */
    fun bitmapRow(v: Float): Float = 1f - v
}
