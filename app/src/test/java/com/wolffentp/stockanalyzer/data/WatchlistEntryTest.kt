package com.wolffentp.stockanalyzer.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchlistEntryTest {
    private val json = Json

    @Test
    fun holdingRoundTripPreservesUserEnteredValues() {
        val entry = WatchlistEntry(
            "MSFT",
            quantity = 12.5,
            averageCost = 310.75,
            lastOvernight = StoredSessionSnapshot(320.0, 1.5, 0.47),
            lastAfterHours = StoredSessionSnapshot(321.0, 2.5, 0.78),
        )
        val decoded = json.decodeFromString<WatchlistEntry>(json.encodeToString(entry))
        assertEquals(entry, decoded)
    }

    @Test
    fun watchlistOnlyEntryDoesNotInventHoldingValues() {
        val decoded = json.decodeFromString<WatchlistEntry>("{\"symbol\":\"AAPL\"}")
        assertNull(decoded.quantity)
        assertNull(decoded.averageCost)
    }

    @Test
    fun previousVersionWatchlistSurvivesSchemaAdditions() {
        val previousVersion = """[{"symbol":"MSFT","quantity":12.5,"averageCost":310.75,"futureField":"ignored"},{"symbol":"NVDA"}]"""
        val decoded = WatchlistCodec.decode(previousVersion)

        assertEquals(listOf("MSFT", "NVDA"), decoded.map { it.symbol })
        assertEquals(12.5, decoded.first().quantity ?: 0.0, 0.0)
        assertEquals(310.75, decoded.first().averageCost ?: 0.0, 0.0)
        assertNull(decoded.last().quantity)
    }
}