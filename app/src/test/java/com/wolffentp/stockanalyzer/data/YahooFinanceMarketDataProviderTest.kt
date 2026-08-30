package com.wolffentp.stockanalyzer.data

import java.time.Instant
import com.wolffentp.stockanalyzer.domain.MarketSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YahooFinanceMarketDataProviderTest {
    private lateinit var server: MockWebServer
    private val now = Instant.parse("2026-08-12T03:00:00Z")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun decodesTimestampedQuoteAndCandlesWithoutCredentials() = runBlocking {
        server.enqueue(MockResponse().setBody(CHART_JSON).setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody(QUOTE_JSON).setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody(EXTENDED_SESSION_CHART_JSON).setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody(CHART_JSON).setHeader("Content-Type", "application/json"))
        val provider = provider()

        val quote = provider.getQuote("MSFT")
        val candles = provider.getIntradayCandles("MSFT", 1, 120)

        assertEquals("Yahoo Finance", quote.provider)
        assertEquals(503.81, quote.price, 0.0)
        assertEquals(Instant.ofEpochSecond(1_786_478_400), quote.timestamp)
        assertEquals(504.01, quote.overnightPrice ?: 0.0, 0.0)
        assertEquals(0.20, quote.overnightChange ?: 0.0, 0.0001)
        assertEquals(0.04, quote.overnightChangePercent ?: 0.0, 0.01)
        assertTrue(!quote.overnightIsPrior)
        assertEquals(502.90, quote.preMarketPrice ?: 0.0, 0.0)
        assertEquals(-0.18, quote.preMarketChangePercent ?: 0.0, 0.01)
        assertEquals(504.72, quote.afterHoursPrice ?: 0.0, 0.0)
        assertEquals(0.18, quote.afterHoursChangePercent ?: 0.0, 0.01)
        assertTrue(quote.afterHoursIsPrior)
        assertEquals(2, candles.candles.size)
        assertEquals(3, server.requestCount)
        assertTrue(server.takeRequest().path.orEmpty().contains("/v8/finance/chart"))
        assertTrue(server.takeRequest().path.orEmpty().contains("/v7/finance/quote"))
        val extendedPath = server.takeRequest().path.orEmpty()
        assertTrue(extendedPath.contains("includePrePost=true"))
        assertTrue(extendedPath.contains("range=5d"))
    }

    @Test
    fun keepsOnlyTickerRelatedSourcedNewsAndLabelsLocalScoring() = runBlocking {
        server.enqueue(MockResponse().setBody(NEWS_JSON).setHeader("Content-Type", "application/json"))
        val news = provider().getNewsOrSentiment("MSFT")

        assertEquals(1, news.items.size)
        assertEquals("Reuters", news.items.single().source)
        assertEquals("Deterministic headline lexicon", news.items.single().scoringMethod)
        assertTrue(news.items.single().score > 0)
    }

    @Test
    fun headlineScorerIsDeterministicAndBounded() {
        assertEquals(1.0, HeadlineSentimentScorer.score("profit growth beats record and surges"), 0.0)
        assertEquals(-1.0, HeadlineSentimentScorer.score("fraud loss misses and stock falls"), 0.0)
        assertEquals(0.0, HeadlineSentimentScorer.score("company schedules annual meeting"), 0.0)
    }

    @Test
    fun overnightSessionUsesSundayOpenAndFridayClose() {
        val provider = provider()
        assertEquals(MarketSession.CLOSED, provider.marketSession(Instant.parse("2026-08-16T23:00:00Z")))
        assertEquals(MarketSession.OVERNIGHT, provider.marketSession(Instant.parse("2026-08-17T00:30:00Z")))
        assertEquals(MarketSession.OVERNIGHT, provider.marketSession(Instant.parse("2026-08-17T06:00:00Z")))
        assertEquals(MarketSession.CLOSED, provider.marketSession(Instant.parse("2026-08-15T00:30:00Z")))
    }

    private fun provider() = YahooFinanceMarketDataProvider(
        baseUrl = server.url("/").toString(),
        clock = { now },
    )

    private companion object {
        const val CHART_JSON = """{"chart":{"result":[{"meta":{"regularMarketPrice":503.81,"regularMarketTime":1786478400},"timestamp":[1786478340,1786478400],"indicators":{"quote":[{"open":[503.1,503.2],"high":[503.5,504.0],"low":[502.9,503.0],"close":[503.2,503.81],"volume":[1000,1200]}]}}],"error":null}}"""
        const val QUOTE_JSON = """{"quoteResponse":{"result":[],"error":null}}"""
        const val EXTENDED_SESSION_CHART_JSON = """{"chart":{"result":[{"meta":{"regularMarketPrice":503.81,"regularMarketTime":1786478400},"timestamp":[1786437000,1786478340,1786482000,1786501260,1786507200],"indicators":{"quote":[{"open":[502.7,503.7,504.5,503.9,999.0],"high":[503.0,504.0,504.9,504.2,999.0],"low":[502.5,503.5,504.2,503.7,999.0],"close":[502.90,503.81,504.72,504.01,999.0],"volume":[100,1200,100,100,100]}]}}],"error":null}}"""
        const val NEWS_JSON = """{"news":[{"title":"Profit growth beats outlook","publisher":"Reuters","link":"https://example.com/msft","providerPublishTime":1786478000,"relatedTickers":["MSFT"]},{"title":"Other company falls","publisher":"Wire","providerPublishTime":1786477000,"relatedTickers":["OTHER"]}]}"""
    }
}
