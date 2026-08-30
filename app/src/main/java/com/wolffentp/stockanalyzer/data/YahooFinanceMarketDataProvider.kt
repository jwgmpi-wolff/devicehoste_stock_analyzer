package com.wolffentp.stockanalyzer.data

import android.util.Log
import com.wolffentp.stockanalyzer.domain.Candle
import com.wolffentp.stockanalyzer.domain.CandleSeries
import com.wolffentp.stockanalyzer.domain.NewsSentimentBatch
import com.wolffentp.stockanalyzer.domain.MarketSession
import com.wolffentp.stockanalyzer.domain.Quote
import com.wolffentp.stockanalyzer.domain.TimestampedSentiment
import java.io.IOException
import java.net.URLEncoder
import java.time.Duration
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class YahooFinanceMarketDataProvider(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val baseUrl: String = "https://query1.finance.yahoo.com",
    private val clock: () -> Instant = Instant::now,
) : MarketDataProvider {
    override val displayName = PROVIDER
    private val cacheMutex = Mutex()
    private val chartCache = mutableMapOf<String, CachedChart>()

    override suspend fun getQuote(symbol: String): Quote {
        val observedAt = clock()
        val result = chart(symbol, interval = "1m", range = "1d")
        val summary = runCatching { quoteSummary(symbol) }.getOrNull()
        val price = result.meta.regularMarketPrice ?: summary?.regularMarketPrice
            ?: throw MarketDataException.InvalidResponse("current price was unavailable")
        val timestamp = result.meta.regularMarketTime ?: summary?.regularMarketTime
            ?: throw MarketDataException.InvalidResponse("quote timestamp was unavailable")
        val extended = runCatching { extendedSession(symbol, observedAt) }.getOrNull()
        val session = marketSession(observedAt)
        val preMarketChange = result.meta.preMarketChange
            ?: summary?.preMarketChange
        val directAfterHoursChange = result.meta.postMarketChange
            ?: summary?.postMarketChange
        val overnightPrice = extended?.overnight?.price
        val preMarketPrice = result.meta.preMarketPrice
            ?: summary?.preMarketPrice
            ?: extended?.preMarket?.price
            ?: preMarketChange?.let { price + it }
        val directAfterHoursPrice = result.meta.postMarketPrice
            ?: summary?.postMarketPrice
        val afterHoursPrice = directAfterHoursPrice
            ?: directAfterHoursChange?.let { price + it }
            ?: extended?.afterHours?.price
        val preMarketChangePercent = result.meta.preMarketChangePercent
            ?: summary?.preMarketChangePercent
            ?: preMarketPrice?.let { sessionPrice -> if (price != 0.0) ((sessionPrice - price) / price) * 100.0 else null }
        val afterHoursChange = directAfterHoursChange
            ?: directAfterHoursPrice?.minus(price)
            ?: extended?.afterHours?.change
        val afterHoursChangePercent = result.meta.postMarketChangePercent
            ?: summary?.postMarketChangePercent
            ?: if (directAfterHoursPrice != null || directAfterHoursChange != null) {
                afterHoursChange?.let { if (price != 0.0) (it / price) * 100.0 else null }
            } else extended?.afterHours?.percent
        val overnightIsPrior = extended?.overnight?.let { !isCurrentOvernightSample(it.timestamp, observedAt) } ?: false
        val overnightChange = extended?.overnight?.change
            ?: overnightPrice?.takeUnless { overnightIsPrior }?.minus(price)
        val overnightChangePercent = extended?.overnight?.percent
            ?: overnightChange?.let { if (price != 0.0) (it / price) * 100.0 else null }
        return Quote(
            symbol = symbol,
            price = price,
            timestamp = Instant.ofEpochSecond(timestamp),
            provider = PROVIDER,
            marketSession = session,
            overnightPrice = overnightPrice,
            overnightChange = overnightChange,
            overnightChangePercent = overnightChangePercent,
            overnightIsPrior = overnightIsPrior,
            preMarketPrice = preMarketPrice,
            preMarketChange = preMarketChange ?: preMarketPrice?.let { it - price },
            preMarketChangePercent = preMarketChangePercent,
            afterHoursPrice = afterHoursPrice,
            afterHoursChange = afterHoursChange ?: afterHoursPrice?.let { it - price },
            afterHoursChangePercent = afterHoursChangePercent,
            afterHoursIsPrior = when {
                afterHoursPrice == null -> false
                session != MarketSession.AFTER_HOURS -> true
                directAfterHoursPrice != null || directAfterHoursChange != null -> false
                else -> extended?.afterHours?.let { !isCurrentAfterHoursSample(it.timestamp, observedAt) } ?: true
            },
        )
    }

    override suspend fun getIntradayCandles(symbol: String, intervalMinutes: Int, rangeMinutes: Int): CandleSeries {
        val daily = intervalMinutes == 1_440
        if (!daily && intervalMinutes != 1) throw MarketDataException.InvalidResponse("unsupported candle interval")
        val result = chart(symbol, interval = if (daily) "1d" else "1m", range = if (daily) "6mo" else "1d")
        val values = result.indicators.quote.firstOrNull()
            ?: throw MarketDataException.InvalidResponse("candle values were unavailable")
        val candles = result.timestamp.mapIndexedNotNull { index, timestamp ->
            val open = values.open.getOrNull(index) ?: return@mapIndexedNotNull null
            val high = values.high.getOrNull(index) ?: return@mapIndexedNotNull null
            val low = values.low.getOrNull(index) ?: return@mapIndexedNotNull null
            val close = values.close.getOrNull(index) ?: return@mapIndexedNotNull null
            Candle(Instant.ofEpochSecond(timestamp), open, high, low, close, values.volume.getOrNull(index))
        }
        if (candles.isEmpty()) throw MarketDataException.MarketClosed()
        return CandleSeries(PROVIDER, clock(), intervalMinutes, candles)
    }

    override suspend fun getNewsOrSentiment(symbol: String): NewsSentimentBatch {
        val encoded = encode(symbol)
        val response = request<YahooSearchResponse>("/v1/finance/search?q=$encoded&quotesCount=0&newsCount=30")
        val items = response.news
            .filter { article -> article.relatedTickers.any { it.equals(symbol, ignoreCase = true) } }
            .mapNotNull { article ->
                val timestamp = article.providerPublishTime ?: return@mapNotNull null
                val headline = article.title?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val source = article.publisher?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                TimestampedSentiment(
                    score = HeadlineSentimentScorer.score(headline),
                    source = source,
                    publishedAt = Instant.ofEpochSecond(timestamp),
                    headline = headline,
                    url = article.link?.takeIf { it.startsWith("https://") },
                    scoringMethod = "Deterministic headline lexicon",
                )
            }
        return NewsSentimentBatch(PROVIDER, clock(), items)
    }

    private suspend fun chart(symbol: String, interval: String, range: String): YahooChartResult {
        val key = "$symbol:$interval:$range"
        return cacheMutex.withLock {
            val now = clock()
            chartCache[key]?.takeIf { Duration.between(it.retrievedAt, now).seconds in 0..10 }?.result
                ?: request<YahooChartResponse>("/v8/finance/chart/${encode(symbol)}?interval=$interval&range=$range")
                    .chart.result?.firstOrNull()
                    ?.also { chartCache[key] = CachedChart(now, it) }
                ?: throw MarketDataException.UnsupportedSymbol()
        }
    }

    private suspend fun quoteSummary(symbol: String): YahooQuoteResult? {
        val encoded = encode(symbol)
        return request<YahooQuoteEnvelope>("/v7/finance/quote?symbols=$encoded")
            .quoteResponse
            .result
            .firstOrNull { item -> item.symbol.equals(symbol, ignoreCase = true) }
    }

    private suspend fun extendedSession(symbol: String, observedAt: Instant): ExtendedSessionPrices {
        val key = "$symbol:extended-session"
        val result = cacheMutex.withLock {
            val now = clock()
            chartCache[key]?.takeIf { Duration.between(it.retrievedAt, now).seconds in 0..10 }?.result
                ?: requestExtendedSessionChart(symbol)
                    ?.also { chartCache[key] = CachedChart(now, it) }
                ?: throw MarketDataException.UnsupportedSymbol()
        }
        val closes = result.indicators.quote.firstOrNull()?.close.orEmpty()
        val sessionPrices = result.timestamp.mapIndexedNotNull { index, value ->
            closes.getOrNull(index)?.let { close -> Instant.ofEpochSecond(value) to close }
        }.filter { (instant, _) -> !instant.isAfter(observedAt) }
        fun latestSessionPrice(startMinute: Int, endMinute: Int): Pair<Instant, Double>? = sessionPrices
            .filter { (instant, _) ->
                val time = instant.atZone(NEW_YORK).toLocalTime()
                val minute = time.hour * 60 + time.minute
                minute in startMinute until endMinute
            }
            .maxByOrNull { it.first }
        fun regularClose(date: LocalDate): Double? = sessionPrices
            .filter { (instant, _) ->
                val dateTime = instant.atZone(NEW_YORK)
                val minute = dateTime.hour * 60 + dateTime.minute
                dateTime.toLocalDate() == date && minute in REGULAR_MARKET_OPEN_MINUTE until REGULAR_MARKET_CLOSE_MINUTE
            }
            .maxByOrNull { it.first }
            ?.second
        fun sessionSample(value: Pair<Instant, Double>?, baselineDate: (Instant) -> LocalDate): SessionSample? {
            val (timestamp, sessionPrice) = value ?: return null
            val baseline = regularClose(baselineDate(timestamp))
            val change = baseline?.let { sessionPrice - it }
            val percent = change?.let { if (baseline != 0.0) (it / baseline) * 100.0 else null }
            return SessionSample(sessionPrice, timestamp, change, percent)
        }
        fun latestOvernightPrice(): Pair<Instant, Double>? = sessionPrices
            .filter { (instant, _) ->
                val time = instant.atZone(NEW_YORK).toLocalTime()
                val minute = time.hour * 60 + time.minute
                minute >= AFTER_HOURS_CLOSE_MINUTE || minute < OVERNIGHT_CLOSE_MINUTE
            }
            .maxByOrNull { it.first }
        val overnight = sessionSample(latestOvernightPrice()) { instant ->
            instant.atZone(NEW_YORK).let { dateTime ->
                if (dateTime.hour < 4) dateTime.toLocalDate().minusDays(1) else dateTime.toLocalDate()
            }
        }
        val afterHours = sessionSample(
            latestSessionPrice(REGULAR_MARKET_CLOSE_MINUTE, AFTER_HOURS_CLOSE_MINUTE),
        ) { instant -> instant.atZone(NEW_YORK).toLocalDate() }
        return ExtendedSessionPrices(
            overnight = overnight,
            preMarket = latestSessionPrice(PRE_MARKET_OPEN_MINUTE, REGULAR_MARKET_OPEN_MINUTE)
                ?.let { (timestamp, sessionPrice) -> SessionSample(sessionPrice, timestamp, null, null) },
            afterHours = afterHours,
        )
    }

    private suspend fun requestExtendedSessionChart(symbol: String): YahooChartResult? {
        val path = "/v8/finance/chart/${encode(symbol)}?interval=1m&range=5d&includePrePost=true"
        val candidateBaseUrls = listOf(
            if (baseUrl.contains("query1.finance.yahoo.com")) EXTENDED_HOURS_BASE_URL else baseUrl,
            baseUrl,
        ).distinct()
        for (candidateBaseUrl in candidateBaseUrls) {
            val chartResult = runCatching {
                request<YahooChartResponse>(path, candidateBaseUrl).chart.result?.firstOrNull()
            }.getOrNull()
            if (chartResult != null) return chartResult
        }
        return null
    }

    private suspend inline fun <reified T> request(path: String, requestBaseUrl: String = baseUrl): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${requestBaseUrl.trimEnd('/')}$path")
            .header("Accept", "application/json")
            .header("User-Agent", "StockMovementAnalyzer/1.4")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw mapStatus(response.code)
                val body = response.body?.string() ?: throw MarketDataException.InvalidResponse("empty body")
                try {
                    json.decodeFromString<T>(body)
                } catch (error: Exception) {
                    throw MarketDataException.InvalidResponse("malformed JSON")
                }
            }
        } catch (error: MarketDataException) {
            Log.w(TAG, "Yahoo Finance request failed: endpoint=${path.substringBefore('?')} type=${error::class.simpleName}")
            throw error
        } catch (error: IOException) {
            Log.w(TAG, "Yahoo Finance request failed: endpoint=${path.substringBefore('?')} type=Network")
            throw MarketDataException.NoInternet()
        }
    }

    private fun mapStatus(status: Int): MarketDataException = when (status) {
        404 -> MarketDataException.UnsupportedSymbol()
        429 -> MarketDataException.RateLimited()
        else -> MarketDataException.ProviderUnavailable()
    }

    private fun encode(value: String): String = URLEncoder.encode(value.trim().uppercase(), Charsets.UTF_8.name())

    internal fun marketSession(now: Instant): MarketSession {
        val dateTime = now.atZone(NEW_YORK)
        val minute = dateTime.hour * 60 + dateTime.minute
        if (dateTime.dayOfWeek == DayOfWeek.SATURDAY ||
            dateTime.dayOfWeek == DayOfWeek.SUNDAY && minute < AFTER_HOURS_CLOSE_MINUTE ||
            dateTime.dayOfWeek == DayOfWeek.FRIDAY && minute >= AFTER_HOURS_CLOSE_MINUTE
        ) return MarketSession.CLOSED
        return when {
            minute < OVERNIGHT_CLOSE_MINUTE || minute >= AFTER_HOURS_CLOSE_MINUTE -> MarketSession.OVERNIGHT
            minute < REGULAR_MARKET_OPEN_MINUTE -> MarketSession.PRE_MARKET
            minute < REGULAR_MARKET_CLOSE_MINUTE -> MarketSession.REGULAR
            else -> MarketSession.AFTER_HOURS
        }
    }

    private fun isCurrentOvernightSample(sample: Instant, observedAt: Instant): Boolean {
        if (marketSession(observedAt) != MarketSession.OVERNIGHT) return false
        fun Instant.sessionDate() = atZone(NEW_YORK).let { dateTime ->
            if (dateTime.hour < 4) dateTime.toLocalDate().minusDays(1) else dateTime.toLocalDate()
        }
        return sample.sessionDate() == observedAt.sessionDate()
    }

    private fun isCurrentAfterHoursSample(sample: Instant, observedAt: Instant): Boolean =
        marketSession(observedAt) == MarketSession.AFTER_HOURS &&
            sample.atZone(NEW_YORK).toLocalDate() == observedAt.atZone(NEW_YORK).toLocalDate()

    private data class CachedChart(val retrievedAt: Instant, val result: YahooChartResult)
    private data class SessionSample(
        val price: Double,
        val timestamp: Instant,
        val change: Double?,
        val percent: Double?,
    )
    private data class ExtendedSessionPrices(
        val overnight: SessionSample?,
        val preMarket: SessionSample?,
        val afterHours: SessionSample?,
    )

    private companion object {
        const val PROVIDER = "Yahoo Finance"
        const val TAG = "YahooMarketData"
        const val EXTENDED_HOURS_BASE_URL = "https://query2.finance.yahoo.com"
        const val PRE_MARKET_OPEN_MINUTE = 4 * 60
        const val REGULAR_MARKET_OPEN_MINUTE = 9 * 60 + 30
        const val REGULAR_MARKET_CLOSE_MINUTE = 16 * 60
        const val AFTER_HOURS_CLOSE_MINUTE = 20 * 60
        const val OVERNIGHT_CLOSE_MINUTE = 4 * 60
        val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    }
}

internal object HeadlineSentimentScorer {
    private val positive = setOf("beat", "beats", "bullish", "gain", "gains", "growth", "higher", "improve", "improves", "profit", "profits", "raise", "raises", "record", "surge", "surges", "upgrade", "upgrades")
    private val negative = setOf("bearish", "cut", "cuts", "decline", "declines", "downgrade", "downgrades", "drop", "drops", "fall", "falls", "fraud", "investigation", "lawsuit", "loss", "losses", "lower", "miss", "misses", "risk", "risks")

    fun score(headline: String): Double {
        val words = Regex("[a-z]+").findAll(headline.lowercase()).map { it.value }
        val raw = words.sumOf { word -> (if (word in positive) 1 else 0) - (if (word in negative) 1 else 0) }
        return (raw / 3.0).coerceIn(-1.0, 1.0)
    }
}

@Serializable
private data class YahooChartResponse(val chart: YahooChartContainer)

@Serializable
private data class YahooChartContainer(val result: List<YahooChartResult>? = null)

@Serializable
private data class YahooChartResult(
    val meta: YahooChartMeta,
    val timestamp: List<Long> = emptyList(),
    val indicators: YahooIndicators,
)

@Serializable
private data class YahooChartMeta(
    val regularMarketPrice: Double? = null,
    val regularMarketTime: Long? = null,
    val preMarketPrice: Double? = null,
    val preMarketChange: Double? = null,
    val preMarketChangePercent: Double? = null,
    val postMarketPrice: Double? = null,
    val postMarketChange: Double? = null,
    val postMarketChangePercent: Double? = null,
)

@Serializable
private data class YahooIndicators(val quote: List<YahooQuoteValues> = emptyList())

@Serializable
private data class YahooQuoteValues(
    val open: List<Double?> = emptyList(),
    val high: List<Double?> = emptyList(),
    val low: List<Double?> = emptyList(),
    val close: List<Double?> = emptyList(),
    val volume: List<Long?> = emptyList(),
)

@Serializable
private data class YahooSearchResponse(val news: List<YahooNewsItem> = emptyList())

@Serializable
private data class YahooQuoteEnvelope(val quoteResponse: YahooQuoteResponse = YahooQuoteResponse())

@Serializable
private data class YahooQuoteResponse(val result: List<YahooQuoteResult> = emptyList())

@Serializable
private data class YahooQuoteResult(
    val symbol: String,
    val regularMarketPrice: Double? = null,
    val regularMarketTime: Long? = null,
    val preMarketPrice: Double? = null,
    val preMarketChange: Double? = null,
    val preMarketChangePercent: Double? = null,
    val postMarketPrice: Double? = null,
    val postMarketChange: Double? = null,
    val postMarketChangePercent: Double? = null,
)

@Serializable
private data class YahooNewsItem(
    val title: String? = null,
    val publisher: String? = null,
    val link: String? = null,
    val providerPublishTime: Long? = null,
    @SerialName("relatedTickers") val relatedTickers: List<String> = emptyList(),
)
