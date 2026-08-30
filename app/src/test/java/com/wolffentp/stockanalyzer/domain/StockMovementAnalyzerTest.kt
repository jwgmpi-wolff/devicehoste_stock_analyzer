package com.wolffentp.stockanalyzer.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StockMovementAnalyzerTest {
    private val now = Instant.parse("2026-08-11T15:00:00Z")

    @Test
    fun staleDataNeverProducesDirectionalPrediction() {
        val result = StockMovementAnalyzer().analyze(snapshot(now.minusSeconds(3600)), Horizon.TEN, now)
        assertEquals(Direction.NEUTRAL_INSUFFICIENT_DATA, result.direction)
        assertEquals(Recommendation.UNAVAILABLE, result.recommendation)
        assertEquals(null, result.projectedPriceRange)
        assertEquals(0, result.confidence)
        assertTrue(result.warnings.any { it.contains("stale") })
    }

    @Test
    fun missingQuoteNeverProducesDirectionalPrediction() {
        val source = snapshot(now.minusSeconds(60)).copy(quote = null)
        val result = StockMovementAnalyzer().analyze(source, Horizon.TEN, now)
        assertEquals(Direction.NEUTRAL_INSUFFICIENT_DATA, result.direction)
        assertTrue(result.reason.startsWith("Insufficient live data"))
    }

    @Test
    fun risingTimestampedCandlesProduceExplainableResult() {
        val result = StockMovementAnalyzer().analyze(snapshot(now.minusSeconds(60)), Horizon.TEN, now)
        assertEquals(Direction.UP, result.direction)
        assertEquals(Recommendation.BUY, result.recommendation)
        assertTrue(result.projectedPriceRange!!.low < result.quote!!.price)
        assertTrue(result.projectedPriceRange!!.high > result.quote!!.price)
        assertTrue(result.confidence in 1..100)
        assertTrue(result.signals.any { it.name == "Momentum" && it.contribution != null })
    }

    @Test
    fun indicatorsDoNotInventUnavailableVolume() {
        assertEquals(null, Indicators.vwap(candles(now, includeVolume = false)))
        assertEquals(null, Indicators.relativeVolume(List(12) { null }, 10))
    }

    @Test
    fun indicatorCalculationsUseRetrievedValues() {
        assertEquals(4.0, Indicators.simpleMovingAverage(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3) ?: 0.0, 0.0001)
        assertEquals(100.0, Indicators.rsi((1..16).map(Int::toDouble)) ?: 0.0, 0.0001)
        assertTrue((Indicators.macd((1..30).map(Int::toDouble)) ?: 0.0) > 0.0)
    }

    @Test
    fun fallingTimestampedCandlesProduceExplainableDownResult() {
        val source = snapshot(now.minusSeconds(60))
        val falling = source.candles.mapIndexed { index, candle ->
            val close = 130.0 - index
            candle.copy(open = close + 0.2, high = close + 0.4, low = close - 0.4, close = close)
        }
        val result = StockMovementAnalyzer().analyze(source.copy(
            quote = source.quote?.copy(price = falling.last().close),
            candles = falling,
        ), Horizon.TEN, now)
        assertEquals(Direction.DOWN, result.direction)
        assertEquals(Recommendation.SELL, result.recommendation)
        assertTrue(result.projectedPriceRange!!.low < result.projectedPriceRange!!.high)
        assertTrue(result.reason.contains("weighted score"))
    }

    @Test
    fun flatValidatedCandlesProduceHoldWithProjectedRange() {
        val source = snapshot(now.minusSeconds(60))
        val flat = source.candles.map { candle ->
            candle.copy(open = 100.0, high = 100.1, low = 99.9, close = 100.0)
        }
        val result = StockMovementAnalyzer().analyze(
            source.copy(quote = source.quote?.copy(price = 100.0), candles = flat),
            Horizon.TEN,
            now,
        )

        assertEquals(Recommendation.HOLD, result.recommendation)
        assertEquals(Direction.NEUTRAL, result.direction)
        assertTrue(result.projectedPriceRange!!.low < 100.0)
        assertTrue(result.projectedPriceRange!!.high > 100.0)
    }

    @Test
    fun invalidIntervalFailsClosedInsteadOfDividingByZero() {
        val result = StockMovementAnalyzer().analyze(snapshot(now.minusSeconds(60)).copy(intervalMinutes = 0), Horizon.TEN, now)
        assertEquals(Direction.NEUTRAL_INSUFFICIENT_DATA, result.direction)
        assertTrue(result.warnings.any { it.contains("interval") })
    }

    @Test
    fun dailyProjectionUsesDailyCandlesAndFailsClosedWhenTooOld() {
        val latest = now.minusSeconds(6 * 24 * 60 * 60L)
        val daily = snapshot(latest).copy(
            intervalMinutes = 1_440,
            candles = candles(latest, includeVolume = true).mapIndexed { index, candle ->
                candle.copy(timestamp = latest.minusSeconds((29L - index) * 24 * 60 * 60))
            },
        )
        val result = StockMovementAnalyzer().analyze(daily, Horizon.FIVE_DAYS, now)
        assertEquals(Direction.NEUTRAL_INSUFFICIENT_DATA, result.direction)
        assertTrue(result.warnings.any { it.contains("stale") })
    }

    @Test
    fun freshTimestampedNewsContributesAndStaleNewsIsExcluded() {
        val source = snapshot(now.minusSeconds(60))
        val fresh = TimestampedSentiment(0.8, "Verified test source", now.minusSeconds(600), "Earnings outlook improves", scoringMethod = "Test method")
        val stale = TimestampedSentiment(-1.0, "Old test source", now.minusSeconds(3 * 24 * 60 * 60), "Old headline", scoringMethod = "Test method")
        val result = StockMovementAnalyzer().analyze(
            source.copy(news = NewsSentimentBatch("Mock provider (test only)", now, listOf(fresh, stale))),
            Horizon.TEN,
            now,
        )
        assertEquals(0.8, result.indicators?.sentimentAverage ?: 0.0, 0.0001)
        assertTrue(result.signals.any { it.name == "News sentiment" && it.contribution != null })
        assertEquals(1, result.news?.items?.size)
        assertEquals("Earnings outlook improves", result.news?.items?.single()?.headline)
    }

    private fun snapshot(latest: Instant): MarketSnapshot {
        val candles = candles(latest, includeVolume = true)
        return MarketSnapshot("MSFT", "Mock provider (test only)", now, 1, Quote("MSFT", candles.last().close, latest, "Mock provider (test only)"), candles)
    }

    private fun candles(latest: Instant, includeVolume: Boolean): List<Candle> =
        (0 until 30).map { index ->
            val close = 100.0 + index
            Candle(latest.minusSeconds((29L - index) * 60), close - 0.2, close + 0.4, close - 0.4, close, if (includeVolume) 1_000L + index * 20 else null)
        }
}