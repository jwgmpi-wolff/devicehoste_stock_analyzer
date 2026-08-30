package com.wolffentp.stockanalyzer.data

import com.wolffentp.stockanalyzer.domain.AnalysisResult
import com.wolffentp.stockanalyzer.domain.Horizon
import com.wolffentp.stockanalyzer.domain.MarketSnapshot
import com.wolffentp.stockanalyzer.domain.StockMovementAnalyzer
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class MarketAnalysisRepository(
    private val provider: MarketDataProvider,
    private val analyzer: StockMovementAnalyzer = StockMovementAnalyzer(),
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun analyze(symbol: String, horizon: Horizon): AnalysisResult = coroutineScope {
        val normalized = symbol.trim().uppercase()
        require(SYMBOL.matches(normalized)) { "Symbol must contain 1-10 letters, digits, dot, or hyphen." }
        val quoteRequest = async { provider.getQuote(normalized) }
        val candleRequest = async {
            provider.getIntradayCandles(
                normalized,
                intervalMinutes = horizon.candleIntervalMinutes,
                rangeMinutes = horizon.rangeMinutes,
            )
        }
        val newsRequest = async { runCatching { provider.getNewsOrSentiment(normalized) } }
        val quote = quoteRequest.await()
        val series = candleRequest.await()
        val newsResult = newsRequest.await()
        val snapshot = MarketSnapshot(
            normalized,
            series.provider,
            series.retrievedAt,
            series.intervalMinutes,
            quote,
            series.candles,
            news = newsResult.getOrNull(),
            newsWarning = newsResult.exceptionOrNull()?.message,
        )
        analyzer.analyze(snapshot, horizon, clock())
    }

    private companion object { val SYMBOL = Regex("^[A-Z0-9.-]{1,10}$") }
}