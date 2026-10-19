package com.wolffentp.stockanalyzer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ProviderApiKey(val name: String, val apiKey: String)

data class ModelSettings(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val model: String = "qwen3:4b",
    val finnhubApiKey: String = "",
    val providerApiKeys: List<ProviderApiKey> = emptyList(),
)

interface ModelSettingsStore {
    val settings: Flow<ModelSettings>
    suspend fun save(settings: ModelSettings)
}

class DataStoreModelSettingsStore(private val context: Context) : ModelSettingsStore {
    override val settings: Flow<ModelSettings> = context.modelSettingsDataStore.data.map { preferences ->
        ModelSettings(
            enabled = preferences[ENABLED] ?: false,
            endpoint = preferences[ENDPOINT].orEmpty(),
            model = preferences[MODEL] ?: "qwen3:4b",
            finnhubApiKey = preferences[FINNHUB_API_KEY].orEmpty(),
            providerApiKeys = Json.decodeFromString<List<ProviderApiKey>>(preferences[PROVIDER_API_KEYS] ?: "[]"),
        )
    }

    override suspend fun save(settings: ModelSettings) {
        context.modelSettingsDataStore.edit { preferences ->
            preferences[ENABLED] = settings.enabled
            preferences[ENDPOINT] = settings.endpoint.trim().trimEnd('/')
            preferences[MODEL] = settings.model.trim()
            preferences[FINNHUB_API_KEY] = settings.finnhubApiKey.trim()
            preferences[PROVIDER_API_KEYS] = Json.encodeToString(settings.providerApiKeys)
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("ollama_enabled")
        val ENDPOINT = stringPreferencesKey("ollama_endpoint")
        val MODEL = stringPreferencesKey("ollama_model")
        val FINNHUB_API_KEY = stringPreferencesKey("finnhub_api_key")
        val PROVIDER_API_KEYS = stringPreferencesKey("provider_api_keys")
    }
}

private val Context.modelSettingsDataStore by preferencesDataStore(name = "model_settings")
