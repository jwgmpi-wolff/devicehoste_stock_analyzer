package com.wolffentp.stockanalyzer.ui

import com.wolffentp.stockanalyzer.domain.Direction

object DirectionPalette {
    const val RISE = 0xFF1565C0
    const val DROP = 0xFF7B1FA2
    const val NEUTRAL = 0xFF6B6B72

    fun argb(direction: Direction): Long = when (direction) {
        Direction.UP -> RISE
        Direction.DOWN -> DROP
        Direction.NEUTRAL,
        Direction.NEUTRAL_INSUFFICIENT_DATA -> NEUTRAL
    }
}