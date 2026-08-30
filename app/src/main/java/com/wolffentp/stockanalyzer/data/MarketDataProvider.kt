package com.wolffentp.stockanalyzer.data

import com.wolffentp.stockanalyzer.domain.CandleSeries
import com.wolffentp.stockanalyzer.domain.Quote
import com.wolffentp.stockanalyzer.domain.NewsSentimentBatch

interface MarketDataProvider {
    val displayName: String
    suspend fun getQuote(symbol: String): Quote
    suspend fun getIntradayCandles(symbol: String, intervalMinutes: Int, rangeMinutes: Int): CandleSeries
    suspend fun getNewsOrSentiment(symbol: String): NewsSentimentBatch? = null
}

sealed class MarketDataException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MissingConfiguration : MarketDataException("Market data proxy is not configured.")
    class NoInternet : MarketDataException("No internet connectivity.")
    class RateLimited : MarketDataException("Market data provider rate limit exceeded.")
    class MarketClosed : MarketDataException("Market is closed and no sufficiently recent data is available.")
    class UnsupportedSymbol : MarketDataException("The selected symbol is unsupported.")
    class ProviderUnavailable : MarketDataException("Market data provider is unavailable.")
    class InvalidResponse(detail: String) : MarketDataException("Provider response was invalid: $detail")
}