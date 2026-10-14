package com.lmarchery.core.model

data class NormalizedPoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite()) { "NormalizedPoint x must be finite." }
        require(y.isFinite()) { "NormalizedPoint y must be finite." }
    }
}
