package com.wolffentp.stockanalyzer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wolffentp.stockanalyzer.BuildConfig
import com.wolffentp.stockanalyzer.data.MarketAnalysisRepository
import com.wolffentp.stockanalyzer.data.MarketDataException
import com.wolffentp.stockanalyzer.data.ModelReview
import com.wolffentp.stockanalyzer.data.ModelSettings
import com.wolffentp.stockanalyzer.data.ModelSettingsStore
import com.wolffentp.stockanalyzer.data.DataStoreModelSettingsStore
import com.wolffentp.stockanalyzer.data.OllamaModelAnalysisProvider
import com.wolffentp.stockanalyzer.data.YahooFinanceMarketDataProvider
import com.wolffentp.stockanalyzer.data.DataStoreWatchlistStore
import com.wolffentp.stockanalyzer.data.WatchlistEntry
import com.wolffentp.stockanalyzer.data.WatchlistStore
import com.wolffentp.stockanalyzer.data.StoredSessionSnapshot
import com.wolffentp.stockanalyzer.domain.AnalysisResult
import com.wolffentp.stockanalyzer.domain.AnalyzerConfig
import com.wolffentp.stockanalyzer.domain.Horizon
import com.wolffentp.stockanalyzer.domain.HoldingInputParser
import com.wolffentp.stockanalyzer.domain.StockMovementAnalyzer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.Instant

data class StockRowState(
    val symbol: String,
    val quantity: Double? = null,
    val averageCost: Double? = null,
    val analysis: AnalysisResult? = null,
    val lastOvernight: StoredSessionSnapshot? = null,
    val lastAfterHours: StoredSessionSnapshot? = null,
    val priceFlashArgb: Long? = null,
    val overnightFlashArgb: Long? = null,
    val preMarketFlashArgb: Long? = null,
    val afterHoursFlashArgb: Long? = null,
    val modelReview: ModelReview? = null,
    val modelError: String? = null,
    val error: String? = null,
)

data class StockUiState(
    val horizon: Horizon = Horizon.TEN,
    val rows: List<StockRowState> = emptyList(),
    val isRefreshing: Boolean = false,
    val autoRefresh: Boolean = true,
    val lastRefreshAt: Instant? = null,
    val selectedSymbol: String? = null,
    val modelSettings: ModelSettings = ModelSettings(),
    val endpointOptions: List<String> = DEFAULT_ENDPOINT_OPTIONS,
    val modelOptions: List<String> = DEFAULT_MODEL_OPTIONS,
    val modelStatus: String? = null,
)

class StockViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: MarketAnalysisRepository = MarketAnalysisRepository(
        provider = YahooFinanceMarketDataProvider(),
        analyzer = StockMovementAnalyzer(
            config = AnalyzerConfig(BuildConfig.POSITIVE_THRESHOLD, BuildConfig.NEGATIVE_THRESHOLD),
        ),
    ),
    private val watchlistStore: WatchlistStore = DataStoreWatchlistStore(application),
    private val modelSettingsStore: ModelSettingsStore = DataStoreModelSettingsStore(application),
    private val modelProvider: OllamaModelAnalysisProvider = OllamaModelAnalysisProvider(),
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(StockUiState())
    val state: StateFlow<StockUiState> = mutableState.asStateFlow()
    private var autoRefreshJob: Job? = null
    private var refreshJob: Job? = null

    init {
        loadModelSettings()
        refreshEndpointOptions(reportStatus = false)
        refreshModelOptions(reportStatus = false)
        loadWatchlist()
        startAutoRefresh()
    }

    private fun loadModelSettings() {
        viewModelScope.launch {
            val settings = modelSettingsStore.settings.first()
            mutableState.update { it.copy(modelSettings = settings) }
        }
    }

    private fun loadWatchlist() {
        viewModelScope.launch {
            val entries = watchlistStore.entries.first()
            mutableState.update { state -> state.copy(rows = entries.map(WatchlistEntry::toRowState)) }
            refresh()
        }
    }

    fun setHorizon(horizon: Horizon) {
        mutableState.update { it.copy(horizon = horizon) }
        refresh()
    }

    fun addSymbol(input: String): Boolean {
        val symbol = input.trim().uppercase()
        if (!Regex("^[A-Z0-9.-]{1,10}$").matches(symbol)) return false
        mutableState.update { current ->
            if (current.rows.any { it.symbol == symbol }) current
            else current.copy(rows = current.rows + StockRowState(symbol))
        }
        persistWatchlist()
        refresh()
        return true
    }

    fun deleteSymbol(symbol: String) {
        mutableState.update { state ->
            state.copy(
                rows = state.rows.filterNot { it.symbol == symbol },
                selectedSymbol = state.selectedSymbol.takeUnless { it == symbol },
            )
        }
        persistWatchlist()
    }

    fun clearWatchlist() {
        refreshJob?.cancel()
        mutableState.update { it.copy(rows = emptyList(), selectedSymbol = null, isRefreshing = false) }
        persistWatchlist()
    }

    fun saveHolding(symbol: String, quantityText: String, averageCostText: String): Boolean {
        val holding = HoldingInputParser.parse(quantityText, averageCostText) ?: return false
        mutableState.update { state ->
            state.copy(rows = state.rows.map { row ->
                if (row.symbol == symbol) row.copy(quantity = holding.quantity, averageCost = holding.averageCost) else row
            })
        }
        persistWatchlist()
        return true
    }

    fun clearHolding(symbol: String) {
        mutableState.update { state ->
            state.copy(rows = state.rows.map { row ->
                if (row.symbol == symbol) row.copy(quantity = null, averageCost = null) else row
            })
        }
        persistWatchlist()
    }

    fun select(symbol: String?) { mutableState.update { it.copy(selectedSymbol = symbol) } }

    fun setAutoRefresh(enabled: Boolean) {
        mutableState.update { it.copy(autoRefresh = enabled) }
        autoRefreshJob?.cancel()
        if (enabled) startAutoRefresh()
    }

    fun saveModelSettings(enabled: Boolean, endpoint: String, model: String, finnhubApiKey: String): Boolean {
        val normalizedEndpoint = endpoint.trim().trimEnd('/')
        val normalizedModel = normalizeRequestedModel(model.trim())
        if (enabled && (!normalizedEndpoint.matches(Regex("^https?://[^\\s]+$")) || normalizedModel.isBlank())) return false
        val settings = ModelSettings(enabled, normalizedEndpoint, normalizedModel.ifBlank { "qwen3:4b" }, finnhubApiKey.trim())
        mutableState.update {
            it.copy(
                modelSettings = settings,
                endpointOptions = mergedOptions(it.endpointOptions, listOf(normalizedEndpoint).filter(String::isNotBlank)),
                modelOptions = mergedOptions(it.modelOptions, listOf(settings.model).filter(String::isNotBlank)),
            )
        }
        viewModelScope.launch { modelSettingsStore.save(settings) }
        refresh()
        return true
    }

    fun refreshEndpointOptions(reportStatus: Boolean = true) {
        viewModelScope.launch {
            val currentEndpoint = mutableState.value.modelSettings.endpoint
            val discovered = discoverEndpointOptions(currentEndpoint)
            mutableState.update { state ->
                state.copy(
                    endpointOptions = discovered,
                    modelStatus = if (reportStatus) "Loaded ${discovered.size} endpoint option(s)." else state.modelStatus,
                )
            }
        }
    }

    fun refreshModelOptions(reportStatus: Boolean = true) {
        viewModelScope.launch {
            val settings = mutableState.value.modelSettings
            val base = mergedOptions(DEFAULT_MODEL_OPTIONS, listOf(settings.model).filter(String::isNotBlank))
            if (settings.endpoint.isBlank()) {
                mutableState.update {
                    it.copy(
                        modelOptions = base,
                        modelStatus = if (reportStatus) "Enter an endpoint to discover installed models." else it.modelStatus,
                    )
                }
                return@launch
            }
            runCatching { modelProvider.getModels(settings.endpoint) }
                .onSuccess { discovered ->
                    val merged = mergedOptions(base, discovered)
                    mutableState.update {
                        it.copy(
                            modelOptions = merged,
                            modelStatus = if (discovered.isEmpty()) "Ollama reachable; no installed models found." else "Found ${discovered.size} installed model(s).",
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            modelOptions = base,
                            modelStatus = error.message ?: "Ollama unavailable; technical analysis remains active.",
                        )
                    }
                }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            mutableState.update { it.copy(isRefreshing = true) }
            val horizon = mutableState.value.horizon
            val modelSettings = mutableState.value.modelSettings
            val updated = mutableState.value.rows.map { row ->
                try {
                    val analysis = repository.analyze(row.symbol, horizon)
                    val mergedAnalysis = analysis.withExtendedSessionFallback(
                        previous = row.analysis,
                        retainedOvernight = row.lastOvernight,
                        retainedAfterHours = row.lastAfterHours,
                    )
                    val refreshedRow = row.withRefreshedAnalysis(mergedAnalysis).withRetainedSessions(mergedAnalysis)
                    if (modelSettings.enabled && analysis.recommendation != com.wolffentp.stockanalyzer.domain.Recommendation.UNAVAILABLE) {
                        runCatching { modelProvider.analyze(mergedAnalysis, modelSettings) }
                            .fold(
                                onSuccess = { refreshedRow.copy(modelReview = it, modelError = null, error = null) },
                                onFailure = { refreshedRow.copy(modelReview = null, modelError = it.message ?: "Local model review unavailable", error = null) },
                            )
                    } else {
                        refreshedRow.copy(modelReview = null, modelError = null, error = null)
                    }
                } catch (error: Exception) {
                    row.copy(
                        analysis = null,
                        priceFlashArgb = null,
                        overnightFlashArgb = null,
                        preMarketFlashArgb = null,
                        afterHoursFlashArgb = null,
                        modelReview = null,
                        modelError = null,
                        error = error.displayMessage(),
                    )
                }
            }
            mutableState.update { it.copy(rows = updated, isRefreshing = false, lastRefreshAt = Instant.now()) }
            persistWatchlist()
        }
    }

    private fun startAutoRefresh() {
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(AUTO_REFRESH_MILLIS)
                refresh()
            }
        }
    }

    private fun persistWatchlist() {
        val entries = mutableState.value.rows.map(StockRowState::toWatchlistEntry)
        viewModelScope.launch { watchlistStore.save(entries) }
    }

    private fun Exception.displayMessage(): String = when (this) {
        is MarketDataException -> "Live data unavailable. $message"
        is IllegalArgumentException -> "Live data unavailable. Unsupported symbol."
        else -> "Live data unavailable. Provider request failed."
    }

    private companion object { const val AUTO_REFRESH_MILLIS = 60_000L }
}

private fun discoverEndpointOptions(currentEndpoint: String): List<String> {
    val values = linkedSetOf(
        "http://127.0.0.1:11434",
        "http://localhost:11434",
        "http://10.0.2.2:11434",
        "http://host.docker.internal:11434",
    )
    if (currentEndpoint.isNotBlank()) values.add(currentEndpoint.trim().trimEnd('/'))
    val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
    interfaces.forEach { network ->
        if (!network.isUp || network.isLoopback) return@forEach
        network.inetAddresses.toList()
            .filterIsInstance<Inet4Address>()
            .map { "http://${it.hostAddress}:11434" }
            .forEach(values::add)
    }
    return values.toList()
}

private fun mergedOptions(primary: List<String>, secondary: List<String>): List<String> =
    (primary + secondary).map { it.trim() }.filter(String::isNotBlank).distinctBy { it.lowercase() }

private fun <T> java.util.Enumeration<T>.toList(): List<T> {
    val values = mutableListOf<T>()
    while (hasMoreElements()) values += nextElement()
    return values
}

private val DEFAULT_ENDPOINT_OPTIONS = listOf(
    "http://127.0.0.1:11434",
    "http://localhost:11434",
    "http://10.0.2.2:11434",
    "http://host.docker.internal:11434",
)

private val DEFAULT_MODEL_OPTIONS = listOf(
    "gpt-5.3-codex",
    "qwen3:4b",
    "qwen3:8b",
    "llama3.1:8b",
    "mistral:7b",
    "phi4:latest",
)

private fun normalizeRequestedModel(model: String): String {
    val lowered = model.trim().lowercase()
    return when (lowered) {
        "gpt-5.3-codex", "gpt-5-codex", "gpt-5", "ghcp", "copilot" -> "qwen3:8b"
        else -> model.trim()
    }
}

internal fun AnalysisResult.withExtendedSessionFallback(
    previous: AnalysisResult?,
    retainedOvernight: StoredSessionSnapshot? = null,
    retainedAfterHours: StoredSessionSnapshot? = null,
): AnalysisResult {
    val currentQuote = quote ?: return this
    val previousQuote = previous?.quote

    val currentOvernight = currentQuote.sessionValues(
        currentQuote.overnightPrice,
        currentQuote.overnightChange,
        currentQuote.overnightChangePercent,
    )
    val overnight = currentOvernight ?: previousQuote?.sessionValues(
        previousQuote.overnightPrice,
        previousQuote.overnightChange,
        previousQuote.overnightChangePercent,
    ) ?: retainedOvernight?.toSessionValues()
    val preMarket = currentQuote.sessionValues(
        currentQuote.preMarketPrice,
        currentQuote.preMarketChange,
        currentQuote.preMarketChangePercent,
    ) ?: previousQuote?.sessionValues(
        previousQuote.preMarketPrice,
        previousQuote.preMarketChange,
        previousQuote.preMarketChangePercent,
    )
    val currentAfterHours = currentQuote.sessionValues(
        currentQuote.afterHoursPrice,
        currentQuote.afterHoursChange,
        currentQuote.afterHoursChangePercent,
    )
    val afterHours = currentAfterHours ?: previousQuote?.sessionValues(
        previousQuote.afterHoursPrice,
        previousQuote.afterHoursChange,
        previousQuote.afterHoursChangePercent,
    ) ?: retainedAfterHours?.toSessionValues()

    val mergedQuote = currentQuote.copy(
        overnightPrice = overnight?.price,
        overnightChange = overnight?.change,
        overnightChangePercent = overnight?.percent,
        overnightIsPrior = if (currentOvernight != null) currentQuote.overnightIsPrior else overnight != null,
        preMarketPrice = preMarket?.price,
        preMarketChange = preMarket?.change,
        preMarketChangePercent = preMarket?.percent,
        afterHoursPrice = afterHours?.price,
        afterHoursChange = afterHours?.change,
        afterHoursChangePercent = afterHours?.percent,
        afterHoursIsPrior = if (currentAfterHours != null) currentQuote.afterHoursIsPrior else afterHours != null,
    )
    return copy(quote = mergedQuote)
}

private data class SessionValues(val price: Double?, val change: Double?, val percent: Double?)

private fun StoredSessionSnapshot.toSessionValues() = SessionValues(price, change, percent)
private fun SessionValues.toStoredSnapshot(): StoredSessionSnapshot? =
    if (price != null && change != null) StoredSessionSnapshot(price, change, percent) else null

private fun com.wolffentp.stockanalyzer.domain.Quote.sessionValues(
    sessionPrice: Double?,
    sessionChange: Double?,
    sessionPercent: Double?,
): SessionValues? {
    if (sessionPrice == null && sessionChange == null) return null
    val resolvedPrice = sessionPrice ?: sessionChange?.let { price + it }
    val resolvedChange = sessionChange ?: resolvedPrice?.minus(price)
    val resolvedPercent = sessionPercent
        ?: resolvedChange?.let { if (price != 0.0) (it / price) * 100.0 else null }
    return SessionValues(resolvedPrice, resolvedChange, resolvedPercent)
}

private fun StockRowState.withRefreshedAnalysis(refreshedAnalysis: AnalysisResult): StockRowState {
    val previousQuote = analysis?.quote
    val refreshedQuote = refreshedAnalysis.quote
    return copy(
        analysis = refreshedAnalysis,
        priceFlashArgb = changeFlashArgb(previousQuote?.price, refreshedQuote?.price),
        overnightFlashArgb = changeFlashArgb(
            previousQuote?.run { sessionFlashValue(overnightPrice, overnightChange) },
            refreshedQuote?.run { sessionFlashValue(overnightPrice, overnightChange) },
        ),
        preMarketFlashArgb = changeFlashArgb(
            previousQuote?.run { sessionFlashValue(preMarketPrice, preMarketChange) },
            refreshedQuote?.run { sessionFlashValue(preMarketPrice, preMarketChange) },
        ),
        afterHoursFlashArgb = changeFlashArgb(
            previousQuote?.run { sessionFlashValue(afterHoursPrice, afterHoursChange) },
            refreshedQuote?.run { sessionFlashValue(afterHoursPrice, afterHoursChange) },
        ),
    )
}

private fun StockRowState.withRetainedSessions(refreshedAnalysis: AnalysisResult): StockRowState {
    val quote = refreshedAnalysis.quote ?: return this
    return copy(
        lastOvernight = quote.sessionValues(
            quote.overnightPrice,
            quote.overnightChange,
            quote.overnightChangePercent,
        )?.toStoredSnapshot() ?: lastOvernight,
        lastAfterHours = quote.sessionValues(
            quote.afterHoursPrice,
            quote.afterHoursChange,
            quote.afterHoursChangePercent,
        )?.toStoredSnapshot() ?: lastAfterHours,
    )
}

internal fun changeFlashArgb(previousValue: Double?, currentValue: Double?): Long? = when {
    previousValue == null || currentValue == null -> null
    currentValue - previousValue > 0.00005 -> 0x6634C759L
    currentValue - previousValue < -0.00005 -> 0x66FF3B30L
    else -> null
}

private fun com.wolffentp.stockanalyzer.domain.Quote.sessionFlashValue(sessionPrice: Double?, sessionChange: Double?): Double? =
    sessionPrice ?: sessionChange?.let { price + it }

private fun WatchlistEntry.toRowState() = StockRowState(
    symbol = symbol,
    quantity = quantity,
    averageCost = averageCost,
    lastOvernight = lastOvernight,
    lastAfterHours = lastAfterHours,
)

private fun StockRowState.toWatchlistEntry() = WatchlistEntry(
    symbol = symbol,
    quantity = quantity,
    averageCost = averageCost,
    lastOvernight = lastOvernight,
    lastAfterHours = lastAfterHours,
)