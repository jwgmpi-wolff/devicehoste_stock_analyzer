package com.wolffentp.stockanalyzer.data

import com.wolffentp.stockanalyzer.domain.AnalysisResult
import com.wolffentp.stockanalyzer.domain.Direction
import com.wolffentp.stockanalyzer.domain.Horizon
import com.wolffentp.stockanalyzer.domain.NewsSentimentBatch
import com.wolffentp.stockanalyzer.domain.ProjectedPriceRange
import com.wolffentp.stockanalyzer.domain.Quote
import com.wolffentp.stockanalyzer.domain.Recommendation
import com.wolffentp.stockanalyzer.domain.TimestampedSentiment
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OllamaModelAnalysisProviderTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun acceptsBoundedStructuredFreeModelReview() {
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"response":"{\"recommendation\":\"BUY\",\"low\":95.0,\"high\":112.0,\"rationale\":\"Signals align.\"}"}"""))
            val review = provider().analyze(result(), settings())
            assertEquals(Recommendation.BUY, review.recommendation)
            assertEquals("qwen3:4b", review.model)
            assertEquals(95.0, review.low, 0.0)
        }
    }

    @Test
    fun rejectsHallucinatedOutlierRange() {
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"response":"{\"recommendation\":\"BUY\",\"low\":1.0,\"high\":1000.0,\"rationale\":\"Invented.\"}"}"""))
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { provider().analyze(result(), settings()) }
            }
        }
    }

    @Test
    fun sendsOnlyNewsCurrentAtModelCallTime() {
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"response":"{\"recommendation\":\"HOLD\",\"low\":95.0,\"high\":105.0,\"rationale\":\"Current evidence is mixed.\"}"}"""))
            provider().analyze(result(withNews = true), settings())
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("Current headline"))
            assertTrue(body.contains("2026-08-11T14:50:00Z"))
            assertFalse(body.contains("Stale headline"))
            assertFalse(body.contains("Future headline"))
        }
    }

    @Test
    fun rejectsUnavailableAnalysisBeforeCallingModel() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                provider().analyze(result().copy(recommendation = Recommendation.UNAVAILABLE), settings())
            }
        }
        assertEquals(0, server.requestCount)
    }

    private fun settings() = ModelSettings(true, server.url("/").toString(), "qwen3:4b")

    private fun provider() = OllamaModelAnalysisProvider(clock = { NOW })

    private fun result(withNews: Boolean = false): AnalysisResult {
        return AnalysisResult(
            "MSFT", Horizon.TEN, Direction.UP, 70, "Test", NOW, NOW, 0, 1,
            Quote("MSFT", 100.0, NOW, "Test"), null, emptyList(), Recommendation.BUY,
            ProjectedPriceRange(96.0, 108.0), emptyList(), "Test analysis",
            if (withNews) NewsSentimentBatch("Test", NOW, listOf(
                TimestampedSentiment(0.5, "Current source", NOW.minusSeconds(600), "Current headline", null, "Test"),
                TimestampedSentiment(-0.5, "Old source", NOW.minusSeconds(25 * 60 * 60), "Stale headline", null, "Test"),
                TimestampedSentiment(0.5, "Future source", NOW.plusSeconds(60), "Future headline", null, "Test"),
            )) else null,
        )
    }

    private companion object { val NOW = Instant.parse("2026-08-11T15:00:00Z") }
}
