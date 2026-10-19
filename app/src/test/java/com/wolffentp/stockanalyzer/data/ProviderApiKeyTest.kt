package com.wolffentp.stockanalyzer.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderApiKeyTest {
    @Test
    fun providerNamesAndKeysRoundTrip() {
        val entries = listOf(
            ProviderApiKey("Example provider", "test-only-key"),
            ProviderApiKey("Second provider", "key-with-\"quotes\"-and-\\slashes"),
        )
        assertEquals(entries, Json.decodeFromString<List<ProviderApiKey>>(Json.encodeToString(entries)))
    }

    @Test
    fun missingSavedProvidersDefaultToEmpty() {
        assertTrue(ModelSettings().providerApiKeys.isEmpty())
        assertEquals(emptyList<ProviderApiKey>(), Json.decodeFromString<List<ProviderApiKey>>("[]"))
    }

    @Test
    fun otherSettingsChangesPreserveProviders() {
        val settings = ModelSettings(providerApiKeys = listOf(ProviderApiKey("Example", "test-only-key")))
        assertEquals(settings.providerApiKeys, settings.copy(model = "qwen3:8b").providerApiKeys)
    }
}
