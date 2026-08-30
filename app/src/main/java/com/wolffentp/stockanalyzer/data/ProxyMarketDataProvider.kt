package com.wolffentp.stockanalyzer.data

import android.util.Log
import com.wolffentp.stockanalyzer.domain.Candle
import com.wolffentp.stockanalyzer.domain.CandleSeries
import com.wolffentp.stockanalyzer.domain.NewsSentimentBatch
import com.wolffentp.stockanalyzer.domain.Quote
import com.wolffentp.stockanalyzer.domain.TimestampedSentiment
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class ProxyMarketDataProvider(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MarketDataProvider {
    override val displayName = "Configured market data provider"

    override suspend fun getQuote(symbol: String): Quote {
        val dto = request<QuoteResponse>("v1/quote/${encode(symbol)}")
        return Quote(dto.symbol, dto.price, parseInstant(dto.timestamp), dto.provider)
    }

    override suspend fun getIntradayCandles(symbol: String, intervalMinutes: Int, rangeMinutes: Int): CandleSeries {
        val dto = request<CandleResponse>("v1/candles/${encode(symbol)}?interval=$intervalMinutes&range=$rangeMinutes")
        return CandleSeries(
            provider = dto.provider,
            retrievedAt = parseInstant(dto.retrievedAt),
            intervalMinutes = dto.intervalMinutes,
            candles = dto.candles.map {
                Candle(parseInstant(it.timestamp), it.open, it.high, it.low, it.close, it.volume)
            },
        )
    }

    override suspend fun getNewsOrSentiment(symbol: String): NewsSentimentBatch {
        val dto = request<NewsResponse>("v1/news/${encode(symbol)}")
        return NewsSentimentBatch(
            provider = dto.provider,
            retrievedAt = parseInstant(dto.retrievedAt),
            items = dto.items.map {
                TimestampedSentiment(
                    score = it.score.coerceIn(-1.0, 1.0),
                    source = it.source,
                    publishedAt = parseInstant(it.publishedAt),
                    headline = it.headline,
                    url = it.url,
                    scoringMethod = it.scoringMethod,
                )
            },
        )
    }

    private suspend inline fun <reified T> request(path: String): T = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) throw MarketDataException.MissingConfiguration()
        if (!baseUrl.startsWith("https://")) throw MarketDataException.InvalidResponse("proxy URL must use HTTPS")
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/$path").get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw mapStatus(response.code)
                val body = response.body?.string() ?: throw MarketDataException.InvalidResponse("empty body")
                val parsed = try {
                    json.decodeFromString<T>(body)
                } catch (error: Exception) {
                    throw MarketDataException.InvalidResponse("malformed JSON")
                }
                Log.i(TAG, "Market data call succeeded: endpoint=${path.substringBefore('?')} status=${response.code}")
                parsed
            }
        } catch (error: MarketDataException) {
            Log.w(TAG, "Market data call failed: endpoint=${path.substringBefore('?')} type=${error::class.simpleName}")
            throw error
        } catch (error: IOException) {
            Log.w(TAG, "Market data call failed: endpoint=${path.substringBefore('?')} type=Network")
            throw MarketDataException.NoInternet()
        }
    }

    private fun mapStatus(status: Int): MarketDataException = when (status) {
        404 -> MarketDataException.UnsupportedSymbol()
        423 -> MarketDataException.MarketClosed()
        429 -> MarketDataException.RateLimited()
        else -> MarketDataException.ProviderUnavailable()
    }

    private fun parseInstant(value: String): Instant = try {
        Instant.parse(value)
    } catch (error: Exception) {
        throw MarketDataException.InvalidResponse("invalid timestamp")
    }

    private fun encode(value: String): String = URLEncoder.encode(value.trim().uppercase(), Charsets.UTF_8.name())

    private companion object { const val TAG = "MarketDataProvider" }
}

@Serializable
private data class QuoteResponse(val symbol: String, val price: Double, val timestamp: String, val provider: String)

@Serializable
private data class CandleResponse(
    val provider: String,
    val retrievedAt: String,
    val intervalMinutes: Int,
    val candles: List<CandleDto>,
)

@Serializable
private data class CandleDto(
    val timestamp: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long? = null,
)

@Serializable
private data class NewsResponse(
    val provider: String,
    val retrievedAt: String,
    val items: List<NewsItemDto>,
)

@Serializable
private data class NewsItemDto(
    val score: Double,
    val source: String,
    val publishedAt: String,
    val headline: String,
    val url: String? = null,
    val scoringMethod: String,
)