package com.wolffentp.stockanalyzer.ui

import com.wolffentp.stockanalyzer.domain.AnalysisResult
import com.wolffentp.stockanalyzer.domain.Direction
import com.wolffentp.stockanalyzer.domain.Horizon
import com.wolffentp.stockanalyzer.domain.MarketSession
import com.wolffentp.stockanalyzer.domain.Quote
import com.wolffentp.stockanalyzer.domain.Recommendation
import com.wolffentp.stockanalyzer.data.StoredSessionSnapshot
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendedSessionRetentionTest {
    private val now = Instant.parse("2026-08-12T15:00:00Z")

    @Test
    fun missingRefreshRetainsExactOvernightAndAfterHoursSnapshots() {
        val previous = result(
            regularPrice = 100.0,
            overnight = Triple(102.0, 2.0, 2.0),
            afterHours = Triple(103.0, 3.0, 3.0),
        )
        val refreshed = result(regularPrice = 110.0).withExtendedSessionFallback(previous)
        val quote = refreshed.quote!!

        assertEquals(102.0, quote.overnightPrice ?: 0.0, 0.0)
        assertEquals(2.0, quote.overnightChange ?: 0.0, 0.0)
        assertEquals(2.0, quote.overnightChangePercent ?: 0.0, 0.0)
        assertTrue(quote.overnightIsPrior)
        assertEquals(103.0, quote.afterHoursPrice ?: 0.0, 0.0)
        assertEquals(3.0, quote.afterHoursChange ?: 0.0, 0.0)
        assertEquals(3.0, quote.afterHoursChangePercent ?: 0.0, 0.0)
        assertTrue(quote.afterHoursIsPrior)
    }

    @Test
    fun newSessionSnapshotsReplaceRetainedValues() {
        val previous = result(
            regularPrice = 100.0,
            overnight = Triple(102.0, 2.0, 2.0),
            afterHours = Triple(103.0, 3.0, 3.0),
        )
        val refreshed = result(
            regularPrice = 110.0,
            overnight = Triple(111.0, 1.0, 0.91),
            afterHours = Triple(112.0, 2.0, 1.82),
        ).withExtendedSessionFallback(previous)
        val quote = refreshed.quote!!

        assertEquals(111.0, quote.overnightPrice ?: 0.0, 0.0)
        assertFalse(quote.overnightIsPrior)
        assertEquals(112.0, quote.afterHoursPrice ?: 0.0, 0.0)
        assertFalse(quote.afterHoursIsPrior)
    }

    @Test
    fun persistedSnapshotsRestoreAfterRestart() {
        val refreshed = result(regularPrice = 110.0).withExtendedSessionFallback(
            previous = null,
            retainedOvernight = StoredSessionSnapshot(102.0, 2.0, 2.0),
            retainedAfterHours = StoredSessionSnapshot(103.0, 3.0, 3.0),
        )
        val quote = refreshed.quote!!

        assertEquals(102.0, quote.overnightPrice ?: 0.0, 0.0)
        assertTrue(quote.overnightIsPrior)
        assertEquals(103.0, quote.afterHoursPrice ?: 0.0, 0.0)
        assertTrue(quote.afterHoursIsPrior)
    }

    private fun result(
        regularPrice: Double,
        overnight: Triple<Double, Double, Double>? = null,
        afterHours: Triple<Double, Double, Double>? = null,
    ) = AnalysisResult(
        symbol = "MSFT",
        horizon = Horizon.TEN,
        direction = Direction.NEUTRAL,
        confidence = 0,
        provider = "Mock provider (test only)",
        lastDataTimestamp = now,
        retrievedAt = now,
        sourceAgeMinutes = 0,
        candleIntervalMinutes = 1,
        quote = Quote(
            symbol = "MSFT",
            price = regularPrice,
            timestamp = now,
            provider = "Mock provider (test only)",
            marketSession = MarketSession.REGULAR,
            overnightPrice = overnight?.first,
            overnightChange = overnight?.second,
            overnightChangePercent = overnight?.third,
            afterHoursPrice = afterHours?.first,
            afterHoursChange = afterHours?.second,
            afterHoursChangePercent = afterHours?.third,
        ),
        indicators = null,
        signals = emptyList(),
        recommendation = Recommendation.HOLD,
        projectedPriceRange = null,
        warnings = emptyList(),
        reason = "Test fixture",
    )
}