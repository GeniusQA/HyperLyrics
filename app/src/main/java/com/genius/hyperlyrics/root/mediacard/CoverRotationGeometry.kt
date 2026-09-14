package com.genius.hyperlyrics.root.mediacard

internal data class CoverRotationPivot(
    val x: Float,
    val y: Float,
)

internal object CoverRotationGeometry {
    fun centeredPivot(width: Int, height: Int): CoverRotationPivot? {
        if (width <= 0 || height <= 0) return null
        return CoverRotationPivot(width / 2f, height / 2f)
    }
}
