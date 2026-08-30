package com.wolffentp.stockanalyzer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class StoredSessionSnapshot(
    val price: Double,
    val change: Double,
    val percent: Double? = null,
)

@Serializable
data class WatchlistEntry(
    val symbol: String,
    val quantity: Double? = null,
    val averageCost: Double? = null,
    val lastOvernight: StoredSessionSnapshot? = null,
    val lastAfterHours: StoredSessionSnapshot? = null,
)

interface WatchlistStore {
    val entries: Flow<List<WatchlistEntry>>
    suspend fun save(entries: List<WatchlistEntry>)
}

class DataStoreWatchlistStore(
    private val context: Context,
    private val json: Json = WATCHLIST_JSON_CODEC,
) : WatchlistStore {
    override val entries: Flow<List<WatchlistEntry>> = context.watchlistDataStore.data.map { preferences ->
        WatchlistCodec.decode(preferences[WATCHLIST_JSON], json)
    }

    override suspend fun save(entries: List<WatchlistEntry>) {
        context.watchlistDataStore.edit { preferences ->
            preferences[WATCHLIST_JSON] = json.encodeToString(entries)
        }
    }

    private companion object {
        val WATCHLIST_JSON = stringPreferencesKey("watchlist_json")
    }
}

internal object WatchlistCodec {
    fun decode(value: String?, json: Json = WATCHLIST_JSON_CODEC): List<WatchlistEntry> {
        if (value.isNullOrBlank()) return DEFAULT_WATCHLIST
        return runCatching { json.decodeFromString<List<WatchlistEntry>>(value) }
            .getOrDefault(DEFAULT_WATCHLIST)
            .filter { SYMBOL.matches(it.symbol) }
            .distinctBy { it.symbol }
    }

    private val SYMBOL = Regex("^[A-Z0-9.-]{1,10}$")
    private val DEFAULT_WATCHLIST = listOf("MSFT", "AAPL", "NVDA").map(::WatchlistEntry)
}

private val WATCHLIST_JSON_CODEC = Json { ignoreUnknownKeys = true }
private val Context.watchlistDataStore by preferencesDataStore(name = "watchlist")