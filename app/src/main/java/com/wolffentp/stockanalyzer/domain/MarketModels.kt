package com.wolffentp.stockanalyzer.domain

import java.time.Instant

enum class Horizon(
    val durationMinutes: Int,
    val candleIntervalMinutes: Int,
    val rangeMinutes: Int,
    val label: String,
    val freshnessMinutes: Long,
) {
    TEN(10, 1, 120, "10m", 15),
    TWENTY(20, 1, 120, "20m", 15),
    THIRTY(30, 1, 120, "30m", 15),
    FORTY(40, 1, 120, "40m", 15),
    FIFTY(50, 1, 120, "50m", 15),
    SIXTY(60, 1, 120, "60m", 15),
    ONE_DAY(1_440, 1_440, 129_600, "1d", 7_200),
    FIVE_DAYS(7_200, 1_440, 129_600, "5d", 7_200),
    TEN_DAYS(14_400, 1_440, 129_600, "10d", 7_200),
    ;

    val periods: Int get() = durationMinutes / candleIntervalMinutes
    val isDaily: Boolean get() = candleIntervalMinutes == 1_440
}

enum class Direction { UP, DOWN, NEUTRAL, NEUTRAL_INSUFFICIENT_DATA }

enum class Recommendation { BUY, SELL, HOLD, UNAVAILABLE }

enum class MarketSession { OVERNIGHT, PRE_MARKET, REGULAR, AFTER_HOURS, CLOSED }

data class ProjectedPriceRange(
    val low: Double,
    val high: Double,
)

data class Quote(
    val symbol: String,
    val price: Double,
    val timestamp: Instant,
    val provider: String,
    val marketSession: MarketSession = MarketSession.CLOSED,
    val overnightPrice: Double? = null,
    val overnightChange: Double? = null,
    val overnightChangePercent: Double? = null,
    val overnightIsPrior: Boolean = false,
    val preMarketPrice: Double? = null,
    val preMarketChange: Double? = null,
    val preMarketChangePercent: Double? = null,
    val afterHoursPrice: Double? = null,
    val afterHoursChange: Double? = null,
    val afterHoursChangePercent: Double? = null,
    val afterHoursIsPrior: Boolean = false,
)

data class Candle(
    val timestamp: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long?,
)

data class MarketSnapshot(
    val symbol: String,
    val provider: String,
    val retrievedAt: Instant,
    val intervalMinutes: Int,
    val quote: Quote?,
    val candles: List<Candle>,
    val news: NewsSentimentBatch? = null,
    val newsWarning: String? = null,
)

data class CandleSeries(
    val provider: String,
    val retrievedAt: Instant,
    val intervalMinutes: Int,
    val candles: List<Candle>,
)

data class TimestampedSentiment(
    val score: Double,
    val source: String,
    val publishedAt: Instant,
    val headline: String,
    val url: String? = null,
    val scoringMethod: String,
)

data class NewsSentimentBatch(
    val provider: String,
    val retrievedAt: Instant,
    val items: List<TimestampedSentiment>,
)

data class IndicatorValues(
    val momentumPercent: Double?,
    val shortMovingAverage: Double?,
    val longMovingAverage: Double?,
    val relativeVolume: Double?,
    val rsi: Double?,
    val macd: Double?,
    val vwap: Double?,
    val sentimentAverage: Double?,
)

data class SignalContribution(val name: String, val value: Double?, val weight: Double, val contribution: Double?)

data class AnalysisResult(
    val symbol: String,
    val horizon: Horizon,
    val direction: Direction,
    val confidence: Int,
    val provider: String,
    val lastDataTimestamp: Instant?,
    val retrievedAt: Instant,
    val sourceAgeMinutes: Long?,
    val candleIntervalMinutes: Int,
    val quote: Quote?,
    val indicators: IndicatorValues?,
    val signals: List<SignalContribution>,
    val recommendation: Recommendation,
    val projectedPriceRange: ProjectedPriceRange?,
    val warnings: List<String>,
    val reason: String,
    val news: NewsSentimentBatch? = null,
)