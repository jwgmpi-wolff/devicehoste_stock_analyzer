package com.wolffentp.stockanalyzer.domain

import java.time.Duration
import java.time.Instant

class AnalysisValidator {
    fun validate(snapshot: MarketSnapshot, horizon: Horizon, now: Instant): List<String> {
        val warnings = mutableListOf<String>()
        if (snapshot.provider.isBlank()) warnings += "Data provider is missing."
        if (snapshot.quote == null) warnings += "Latest quote is unavailable."
        if (snapshot.intervalMinutes <= 0) {
            warnings += "Candle interval is invalid."
            return warnings
        }
        val latest = snapshot.candles.maxByOrNull { it.timestamp }
        if (latest == null) {
            warnings += "Timestamped intraday candles are unavailable."
            return warnings
        }
        val freshnessMinutes = MarketHoursPolicy.effectiveFreshnessMinutes(horizon, now)
        val age = Duration.between(latest.timestamp, now).toMinutes()
        if (age < 0) warnings += "Source timestamp is in the future."
        if (age > freshnessMinutes) warnings += "Market data is stale (${age} minutes old)."
        if (snapshot.intervalMinutes != horizon.candleIntervalMinutes) {
            warnings += "Candle interval does not match the ${horizon.label} horizon."
        }
        val requiredForHorizon = horizon.periods + 1
        if (snapshot.candles.size < requiredForHorizon) {
            warnings += "Insufficient candles for the ${horizon.label} horizon."
        }
        snapshot.quote?.let { quote ->
            val quoteAge = Duration.between(quote.timestamp, now).toMinutes()
            if (quoteAge < 0) warnings += "Quote timestamp is in the future."
            if (quoteAge > freshnessMinutes) warnings += "Latest quote is stale (${quoteAge} minutes old)."
            if (quote.provider != snapshot.provider) warnings += "Quote provider does not match candle provider."
            if (quote.price <= 0.0) warnings += "Provider returned an invalid quote price."
        }
        if (snapshot.candles.any { it.close <= 0.0 || it.high < it.low }) {
            warnings += "Provider returned invalid candle values."
        }
        return warnings
    }
}