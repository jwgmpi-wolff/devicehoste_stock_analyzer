package com.wolffentp.stockanalyzer.data

import com.wolffentp.stockanalyzer.domain.AnalysisResult
import com.wolffentp.stockanalyzer.domain.MarketHoursPolicy
import com.wolffentp.stockanalyzer.domain.Recommendation
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class ModelReview(
    val recommendation: Recommendation,
    val low: Double,
    val high: Double,
    val rationale: String,
    val model: String,
)

class OllamaModelAnalysisProvider(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun getModels(endpoint: String): List<String> = withContext(Dispatchers.IO) {
        val normalizedEndpoint = endpoint.trim().trimEnd('/')
        require(normalizedEndpoint.matches(Regex("^https?://[^\\s]+$"))) { "Enter a valid Ollama endpoint." }
        val request = Request.Builder()
            .url("$normalizedEndpoint/api/tags")
            .get()
            .build()
        val responseText = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Ollama returned HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        } catch (error: IOException) {
            error("Ollama is unreachable")
        }
        val envelope = json.decodeFromString(OllamaTagsResponse.serializer(), responseText)
        envelope.models.mapNotNull { model -> model.name?.trim()?.takeIf(String::isNotBlank) }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
    }

    suspend fun analyze(result: AnalysisResult, settings: ModelSettings): ModelReview = withContext(Dispatchers.IO) {
        require(settings.enabled && settings.endpoint.isNotBlank() && settings.model.isNotBlank())
        require(result.recommendation != Recommendation.UNAVAILABLE) { "Validated analysis unavailable" }
        val promptTime = clock()
        val freshnessMinutes = MarketHoursPolicy.effectiveFreshnessMinutes(result.horizon, promptTime)
        val latestTimestamp = result.lastDataTimestamp ?: error("Validated market timestamp unavailable")
        require(Duration.between(latestTimestamp, promptTime).toMinutes() in 0..freshnessMinutes) {
            "Validated analysis is no longer current"
        }
        val quote = result.quote ?: error("Validated quote unavailable")
        require(Duration.between(quote.timestamp, promptTime).toMinutes() in 0..freshnessMinutes) {
            "Validated quote is no longer current"
        }
        val signals = result.signals.filter { it.contribution != null }.joinToString("\n") { signal ->
            "${signal.name}: value=${signal.value}, weight=${signal.weight}, contribution=${signal.contribution}"
        }
        val maximumNewsAgeMinutes = if (result.horizon.isDaily) 10_080L else 1_440L
        val headlines = result.news?.items.orEmpty()
            .filter { item ->
                Duration.between(item.publishedAt, promptTime).toMinutes() in 0..maximumNewsAgeMinutes &&
                    item.source.isNotBlank() && item.headline.isNotBlank()
            }
            .take(8)
            .joinToString("\n") { "- ${it.publishedAt}: ${it.headline} (${it.source})" }
        val prompt = """
            You are reviewing a current validated stock-analysis snapshot. Use only the timestamped evidence below and no prior model knowledge. Do not invent prices, events, or fundamentals.
            Return JSON only: {"recommendation":"BUY|SELL|HOLD","low":number,"high":number,"rationale":"plain text under 280 characters"}.
            Symbol: ${result.symbol}
            Horizon: ${result.horizon.label}
            Analysis refreshed at: ${result.retrievedAt}
            Latest market timestamp: $latestTimestamp
            Current price: ${quote.price}
            Technical baseline: ${result.recommendation}
            Technical projected range: ${result.projectedPriceRange?.low} to ${result.projectedPriceRange?.high}
            Confidence: ${result.confidence}%
            Signals:
            $signals
            Recent sourced headlines:
            ${headlines.ifBlank { "None available" }}
        """.trimIndent()
        val requestBody = json.encodeToString(
            OllamaGenerateRequest.serializer(),
            OllamaGenerateRequest(settings.model, prompt),
        )
        val request = Request.Builder()
            .url("${settings.endpoint.trimEnd('/')}/api/generate")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val responseText = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Ollama returned HTTP ${response.code}")
                val envelope = json.decodeFromString<OllamaGenerateResponse>(response.body?.string().orEmpty())
                envelope.response
            }
        } catch (error: IOException) {
            error("Ollama is unreachable")
        }
        val parsed = json.decodeFromString<OllamaReviewResponse>(responseText)
        val recommendation = runCatching { Recommendation.valueOf(parsed.recommendation.uppercase()) }
            .getOrElse { error("Ollama returned an unsupported recommendation") }
        require(recommendation != Recommendation.UNAVAILABLE)
        require(parsed.low.isFinite() && parsed.high.isFinite() && parsed.low > 0.0 && parsed.low < parsed.high)
        require(parsed.low >= quote.price * 0.5 && parsed.high <= quote.price * 1.5) { "Ollama range failed validation" }
        ModelReview(recommendation, parsed.low, parsed.high, parsed.rationale.trim().take(280), settings.model)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

@Serializable
private data class OllamaGenerateRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = false,
    val format: String = "json",
    val options: OllamaOptions = OllamaOptions(),
)

@Serializable
private data class OllamaOptions(val temperature: Double = 0.0)

@Serializable
private data class OllamaGenerateResponse(val response: String)

@Serializable
private data class OllamaTagsResponse(val models: List<OllamaTagModel> = emptyList())

@Serializable
private data class OllamaTagModel(val name: String? = null)

@Serializable
private data class OllamaReviewResponse(
    val recommendation: String,
    val low: Double,
    val high: Double,
    val rationale: String,
)
