package com.wolffentp.stockanalyzer.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class AnalyzerConfig(val positiveThreshold: Double = 0.2, val negativeThreshold: Double = -0.2)

class StockMovementAnalyzer(
    private val validator: AnalysisValidator = AnalysisValidator(),
    private val config: AnalyzerConfig = AnalyzerConfig(),
) {
    fun analyze(snapshot: MarketSnapshot, horizon: Horizon, now: Instant = Instant.now()): AnalysisResult {
        val latestTimestamp = snapshot.candles.maxOfOrNull { it.timestamp }
        val age = latestTimestamp?.let { Duration.between(it, now).toMinutes() }
        val currentNews = currentNews(snapshot, horizon, now)
        val warnings = validator.validate(snapshot, horizon, now)
        if (warnings.isNotEmpty()) return insufficient(snapshot, horizon, latestTimestamp, age, warnings, news = currentNews)

        val ordered = snapshot.candles.sortedBy { it.timestamp }
        val closes = ordered.map { it.close }
        val horizonPeriods = horizon.periods
        val indicators = IndicatorValues(
            momentumPercent = Indicators.momentumPercent(closes, horizonPeriods),
            shortMovingAverage = Indicators.simpleMovingAverage(closes, 5),
            longMovingAverage = Indicators.simpleMovingAverage(closes, 12),
            relativeVolume = Indicators.relativeVolume(ordered.map { it.volume }, 10),
            rsi = Indicators.rsi(closes),
            macd = Indicators.macd(closes),
            vwap = Indicators.vwap(ordered.takeLast(20)),
            sentimentAverage = currentNews?.items?.takeIf { it.isNotEmpty() }?.map { it.score.coerceIn(-1.0, 1.0) }?.average(),
        )
        val signals = listOf(
            contribution("Momentum", indicators.momentumPercent?.coerceIn(-2.0, 2.0)?.div(2.0), 0.30),
            contribution("Trend", trendScore(indicators), 0.20),
            contribution("Volume", volumeScore(indicators, closes.last()), 0.10),
            contribution("RSI", rsiScore(indicators.rsi), 0.15),
            contribution("MACD", indicators.macd?.let { if (it > 0) 1.0 else if (it < 0) -1.0 else 0.0 }, 0.15),
            contribution("News sentiment", indicators.sentimentAverage, 0.10),
        )
        val availableWeight = signals.filter { it.contribution != null }.sumOf { it.weight }
        if (availableWeight < 0.6) {
            return insufficient(snapshot, horizon, latestTimestamp, age, listOf("Too few supported signals to calculate a prediction."), indicators, signals, currentNews)
        }
        val score = signals.sumOf { it.contribution ?: 0.0 } / availableWeight
        val direction = when {
            score >= config.positiveThreshold -> Direction.UP
            score <= config.negativeThreshold -> Direction.DOWN
            else -> Direction.NEUTRAL
        }
        val confidence = (abs(score) * 100).roundToInt().coerceIn(0, 100)
        val recommendation = when (direction) {
            Direction.UP -> Recommendation.BUY
            Direction.DOWN -> Recommendation.SELL
            Direction.NEUTRAL -> Recommendation.HOLD
            Direction.NEUTRAL_INSUFFICIENT_DATA -> Recommendation.UNAVAILABLE
        }
        val projectedPriceRange = projectedPriceRange(ordered, snapshot.quote!!.price, horizon, score)
        val scope = if (horizon.isDaily) "daily" else "intraday"
        val newsSummary = if (indicators.sentimentAverage != null) " Fresh timestamped news sentiment was included." else " News sentiment was unavailable or stale and was excluded."
        val reason = "Probabilistic $scope analysis from ${signals.count { it.contribution != null }} supported trend, momentum, volume, technical, and sourced news signals; weighted score ${"%.3f".format(score)}. The ${recommendation.name.lowercase()} classification and projected range use validated recent price behavior, not a guaranteed target.$newsSummary This is not financial advice."
        val limitations = buildList {
            if (indicators.relativeVolume == null || indicators.vwap == null) add("Volume or VWAP was unavailable and was not used.")
            if (indicators.rsi == null) add("RSI was unavailable and was not used.")
            if (indicators.macd == null) add("MACD was unavailable and was not used.")
            if (indicators.sentimentAverage == null) add(snapshot.newsWarning ?: "Fresh timestamped sentiment was unavailable and was not used.")
        }
        return AnalysisResult(snapshot.symbol, horizon, direction, confidence, snapshot.provider, latestTimestamp, snapshot.retrievedAt, age, snapshot.intervalMinutes, snapshot.quote, indicators, signals, recommendation, projectedPriceRange, limitations, reason, currentNews)
    }

    private fun projectedPriceRange(candles: List<Candle>, currentPrice: Double, horizon: Horizon, score: Double): ProjectedPriceRange? {
        val returns = candles.takeLast(21).zipWithNext { first, second ->
            if (first.close > 0.0) (second.close - first.close) / first.close else null
        }.filterNotNull()
        if (returns.size < 5) return null
        val averageReturn = returns.average()
        val variance = returns.sumOf { (it - averageReturn) * (it - averageReturn) } / returns.size
        val projectedVolatility = sqrt(variance).coerceAtLeast(0.001) * sqrt(horizon.periods.toDouble())
        val halfSpan = currentPrice * projectedVolatility.coerceAtMost(0.35)
        val center = currentPrice + (score.coerceIn(-1.0, 1.0) * halfSpan * 0.5)
        return ProjectedPriceRange(
            low = (center - halfSpan).coerceAtLeast(0.01),
            high = center + halfSpan,
        )
    }

    private fun contribution(name: String, value: Double?, weight: Double) =
        SignalContribution(name, value, weight, value?.times(weight))

    private fun trendScore(values: IndicatorValues): Double? {
        val short = values.shortMovingAverage ?: return null
        val long = values.longMovingAverage ?: return null
        return when { short > long -> 1.0; short < long -> -1.0; else -> 0.0 }
    }

    private fun volumeScore(values: IndicatorValues, latestPrice: Double): Double? {
        val relative = values.relativeVolume ?: return null
        val vwap = values.vwap ?: return null
        if (relative < 1.1) return 0.0
        return if (latestPrice >= vwap) 1.0 else -1.0
    }

    private fun rsiScore(rsi: Double?): Double? = when {
        rsi == null -> null
        rsi >= 70 -> -1.0
        rsi <= 30 -> 1.0
        else -> 0.0
    }

    private fun currentNews(snapshot: MarketSnapshot, horizon: Horizon, now: Instant): NewsSentimentBatch? {
        if (snapshot.news?.provider != snapshot.provider) return null
        val maximumAgeMinutes = if (horizon.isDaily) 10_080L else 1_440L
        val fresh = snapshot.news?.items.orEmpty().filter { item ->
            val age = Duration.between(item.publishedAt, now).toMinutes()
            age in 0..maximumAgeMinutes && item.source.isNotBlank() && item.headline.isNotBlank()
        }
        return snapshot.news?.copy(items = fresh)
    }

    private fun insufficient(snapshot: MarketSnapshot, horizon: Horizon, timestamp: Instant?, age: Long?, warnings: List<String>, indicators: IndicatorValues? = null, signals: List<SignalContribution> = emptyList(), news: NewsSentimentBatch? = null) =
        AnalysisResult(snapshot.symbol, horizon, Direction.NEUTRAL_INSUFFICIENT_DATA, 0, snapshot.provider.ifBlank { "Unknown" }, timestamp, snapshot.retrievedAt, age, snapshot.intervalMinutes, snapshot.quote, indicators, signals, Recommendation.UNAVAILABLE, null, warnings, "Insufficient live data. ${warnings.joinToString(" ")} No directional prediction was generated.", news)
}