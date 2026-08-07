package com.example.myapplication3.network.health

import com.example.myapplication3.network.circuit.CircuitBreaker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class DataProvider(val displayName: String) {
    POLYGON("Polygon.io"),
    FINNHUB("Finnhub"),
    ALPHAVANTAGE("Alpha Vantage"),
    OPENAI("OpenAI"),
    ANTHROPIC("Anthropic")
}

data class ProviderHealth(
    val provider: DataProvider,
    val isHealthy: Boolean,
    val lastSuccessMs: Long = 0L,
    val lastFailureMs: Long = 0L,
    val consecutiveFailures: Int = 0,
    val avgLatencyMs: Long = 0L
)

@Singleton
class ProviderHealthMonitor @Inject constructor() {

    private val circuitBreakers = mapOf(
        DataProvider.POLYGON to CircuitBreaker("Polygon"),
        DataProvider.FINNHUB to CircuitBreaker("Finnhub"),
        DataProvider.ALPHAVANTAGE to CircuitBreaker("AlphaVantage"),
        DataProvider.OPENAI to CircuitBreaker("OpenAI"),
        DataProvider.ANTHROPIC to CircuitBreaker("Anthropic")
    )

    private val _healthState = MutableStateFlow(
        DataProvider.values().associate { it to ProviderHealth(it, true) }
    )
    val healthState: StateFlow<Map<DataProvider, ProviderHealth>> = _healthState.asStateFlow()

    fun getCircuitBreaker(provider: DataProvider): CircuitBreaker =
        circuitBreakers[provider] ?: error("Unknown provider: $provider")

    fun recordSuccess(provider: DataProvider, latencyMs: Long) {
        val current = _healthState.value[provider] ?: return
        _healthState.value = _healthState.value + (provider to current.copy(
            isHealthy = true,
            lastSuccessMs = System.currentTimeMillis(),
            consecutiveFailures = 0,
            avgLatencyMs = if (current.avgLatencyMs == 0L) latencyMs else (current.avgLatencyMs * 4 + latencyMs) / 5
        ))
    }

    fun recordFailure(provider: DataProvider) {
        val current = _healthState.value[provider] ?: return
        val failures = current.consecutiveFailures + 1
        _healthState.value = _healthState.value + (provider to current.copy(
            isHealthy = failures < 3,
            lastFailureMs = System.currentTimeMillis(),
            consecutiveFailures = failures
        ))
    }

    fun isProviderHealthy(provider: DataProvider): Boolean =
        _healthState.value[provider]?.isHealthy == true &&
                circuitBreakers[provider]?.isClosed == true

    fun getActivePrimaryProvider(): DataProvider {
        if (isProviderHealthy(DataProvider.POLYGON)) return DataProvider.POLYGON
        if (isProviderHealthy(DataProvider.FINNHUB)) return DataProvider.FINNHUB
        return DataProvider.ALPHAVANTAGE
    }

    fun getActiveAiProvider(): DataProvider? {
        if (isProviderHealthy(DataProvider.OPENAI)) return DataProvider.OPENAI
        if (isProviderHealthy(DataProvider.ANTHROPIC)) return DataProvider.ANTHROPIC
        return null
    }
}
