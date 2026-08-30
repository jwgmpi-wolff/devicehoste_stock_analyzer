package com.wolffentp.stockanalyzer.domain

import kotlin.math.max

object Indicators {
    fun simpleMovingAverage(values: List<Double>, period: Int): Double? =
        values.takeLast(period).takeIf { it.size == period }?.average()

    fun momentumPercent(values: List<Double>, periods: Int): Double? {
        if (values.size <= periods || values[values.lastIndex - periods] == 0.0) return null
        val start = values[values.lastIndex - periods]
        return ((values.last() - start) / start) * 100.0
    }

    fun relativeVolume(volumes: List<Long?>, period: Int): Double? {
        val actual = volumes.takeLast(period + 1).mapNotNull { it?.toDouble() }
        if (actual.size != period + 1) return null
        val baseline = actual.dropLast(1).average()
        return if (baseline > 0) actual.last() / baseline else null
    }

    fun rsi(values: List<Double>, period: Int = 14): Double? {
        if (values.size < period + 1) return null
        val changes = values.takeLast(period + 1).zipWithNext { first, second -> second - first }
        val gains = changes.sumOf { max(it, 0.0) } / period
        val losses = changes.sumOf { max(-it, 0.0) } / period
        if (losses == 0.0) return if (gains == 0.0) 50.0 else 100.0
        return 100.0 - (100.0 / (1.0 + gains / losses))
    }

    fun exponentialMovingAverage(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        val multiplier = 2.0 / (period + 1)
        return values.drop(period).fold(values.take(period).average()) { ema, value ->
            (value - ema) * multiplier + ema
        }
    }

    fun macd(values: List<Double>): Double? {
        val fast = exponentialMovingAverage(values, 12) ?: return null
        val slow = exponentialMovingAverage(values, 26) ?: return null
        return fast - slow
    }

    fun vwap(candles: List<Candle>): Double? {
        if (candles.isEmpty() || candles.any { it.volume == null }) return null
        val volume = candles.sumOf { it.volume!! }
        if (volume == 0L) return null
        return candles.sumOf { ((it.high + it.low + it.close) / 3.0) * it.volume!! } / volume
    }
}