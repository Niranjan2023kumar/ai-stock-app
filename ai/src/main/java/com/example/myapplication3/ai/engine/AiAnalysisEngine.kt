package com.example.myapplication3.ai.engine

import com.example.myapplication3.core.common.Result
import com.example.myapplication3.core.domain.model.*
import com.example.myapplication3.network.api.anthropic.AnthropicApiService
import com.example.myapplication3.network.api.anthropic.AnthropicMessage
import com.example.myapplication3.network.api.anthropic.AnthropicMessageRequest
import com.example.myapplication3.network.api.openai.OpenAiApiService
import com.example.myapplication3.network.api.openai.OpenAiChatRequest
import com.example.myapplication3.network.api.openai.OpenAiMessage
import com.example.myapplication3.network.api.openai.OpenAiResponseFormat
import com.example.myapplication3.network.health.DataProvider
import com.example.myapplication3.network.health.ProviderHealthMonitor
import com.example.myapplication3.ai.local.LocalQuantEngine
import com.google.gson.Gson
import com.google.gson.JsonParser
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class AiAnalysisResult(
    val thesis: String,
    val sentimentSummary: String,
    val keyRisks: List<String>,
    val confidenceAdjustment: Double,
    val explanation: String,
    val modelUsed: String
)

@Singleton
class AiAnalysisEngine @Inject constructor(
    private val openAiApi: OpenAiApiService,
    private val anthropicApi: AnthropicApiService,
    private val localEngine: LocalQuantEngine,
    private val healthMonitor: ProviderHealthMonitor,
    private val gson: Gson,
    @Named("openAiApiKey") private val openAiKey: String,
    @Named("anthropicApiKey") private val anthropicKey: String
) {

    // Blank or placeholder keys ("your_openai_key_here" from local.properties)
    // mean the cloud call is guaranteed to 401 — skip straight to the local
    // engine instead of burning two network round-trips per recommendation.
    private fun hasUsableKey(key: String): Boolean =
        key.isNotBlank() && !key.startsWith("your_")

    suspend fun analyzeTrade(
        symbol: String,
        signals: List<Signal>,
        newsItems: List<NewsItem>,
        indicators: Map<String, Double>,
        marketData: MarketData
    ): Result<AiAnalysisResult> {
        val provider = healthMonitor.getActiveAiProvider()

        return when {
            provider == DataProvider.OPENAI && hasUsableKey(openAiKey) ->
                analyzeWithOpenAi(symbol, signals, newsItems, indicators, marketData)
            provider == DataProvider.ANTHROPIC && hasUsableKey(anthropicKey) ->
                analyzeWithAnthropic(symbol, signals, newsItems, indicators, marketData)
            else -> Result.Success(localEngine.analyze(symbol, signals, newsItems, indicators, marketData))
        }
    }

    private suspend fun analyzeWithOpenAi(
        symbol: String,
        signals: List<Signal>,
        newsItems: List<NewsItem>,
        indicators: Map<String, Double>,
        marketData: MarketData
    ): Result<AiAnalysisResult> {
        return try {
            val prompt = buildAnalysisPrompt(symbol, signals, newsItems, indicators, marketData)
            val request = OpenAiChatRequest(
                model = "gpt-4o",
                messages = listOf(
                    OpenAiMessage("system", SYSTEM_PROMPT),
                    OpenAiMessage("user", prompt)
                ),
                responseFormat = OpenAiResponseFormat("json_object"),
                temperature = 0.2
            )
            val response = healthMonitor.getCircuitBreaker(DataProvider.OPENAI).execute {
                openAiApi.chatCompletion("Bearer $openAiKey", request)
            }

            if (!response.isSuccessful) {
                healthMonitor.recordFailure(DataProvider.OPENAI)
                return if (hasUsableKey(anthropicKey))
                    analyzeWithAnthropic(symbol, signals, newsItems, indicators, marketData)
                else
                    Result.Success(localEngine.analyze(symbol, signals, newsItems, indicators, marketData))
            }

            val content = response.body()?.choices?.firstOrNull()?.message?.content
                ?: return Result.Success(localEngine.analyze(symbol, signals, newsItems, indicators, marketData))

            healthMonitor.recordSuccess(DataProvider.OPENAI, 0)
            // On unparseable content, degrade to the real local analysis (computed
            // from real inputs) — never a canned "AI analysis unavailable" placeholder.
            Result.Success(parseAiResponse(content, "GPT-4o")
                ?: localEngine.analyze(symbol, signals, newsItems, indicators, marketData))
        } catch (e: Exception) {
            healthMonitor.recordFailure(DataProvider.OPENAI)
            if (hasUsableKey(anthropicKey))
                analyzeWithAnthropic(symbol, signals, newsItems, indicators, marketData)
            else
                Result.Success(localEngine.analyze(symbol, signals, newsItems, indicators, marketData))
        }
    }

    private suspend fun analyzeWithAnthropic(
        symbol: String,
        signals: List<Signal>,
        newsItems: List<NewsItem>,
        indicators: Map<String, Double>,
        marketData: MarketData
    ): Result<AiAnalysisResult> {
        return try {
            val prompt = buildAnalysisPrompt(symbol, signals, newsItems, indicators, marketData)
            val request = AnthropicMessageRequest(
                model = "claude-sonnet-4-6",
                maxTokens = 1000,
                system = SYSTEM_PROMPT,
                messages = listOf(AnthropicMessage("user", "$prompt\n\nRespond with valid JSON only.")),
                temperature = 0.2
            )
            val response = healthMonitor.getCircuitBreaker(DataProvider.ANTHROPIC).execute {
                anthropicApi.createMessage(anthropicKey, request = request)
            }

            if (!response.isSuccessful) {
                healthMonitor.recordFailure(DataProvider.ANTHROPIC)
                return Result.Success(localEngine.analyze(symbol, signals, newsItems, indicators, marketData))
            }

            val content = response.body()?.content?.firstOrNull()?.text
                ?: return Result.Success(localEngine.analyze(symbol, signals, newsItems, indicators, marketData))

            healthMonitor.recordSuccess(DataProvider.ANTHROPIC, 0)
            Result.Success(parseAiResponse(content, "Claude Sonnet 4.6")
                ?: localEngine.analyze(symbol, signals, newsItems, indicators, marketData))
        } catch (e: Exception) {
            healthMonitor.recordFailure(DataProvider.ANTHROPIC)
            Result.Success(localEngine.analyze(symbol, signals, newsItems, indicators, marketData))
        }
    }

    // Returns null when the response can't be parsed, so the caller can fall back
    // to the real local engine instead of emitting canned placeholder text.
    private fun parseAiResponse(json: String, model: String): AiAnalysisResult? {
        return try {
            // Models (Claude especially — no json_object mode) often wrap the JSON
            // in ```json ... ``` fences despite instructions. Strip them, and grab
            // the outermost {...} in case of stray prose, before parsing.
            val cleaned = json
                .replace("```json", "")
                .replace("```", "")
                .trim()
                .let { s ->
                    val start = s.indexOf('{')
                    val end = s.lastIndexOf('}')
                    if (start >= 0 && end > start) s.substring(start, end + 1) else s
                }
            val obj = JsonParser.parseString(cleaned).asJsonObject
            AiAnalysisResult(
                thesis = obj.get("thesis")?.asString ?: "",
                sentimentSummary = obj.get("sentiment_summary")?.asString ?: "",
                keyRisks = obj.get("key_risks")?.asJsonArray?.map { it.asString } ?: emptyList(),
                confidenceAdjustment = obj.get("confidence_adjustment")?.asDouble ?: 0.0,
                explanation = obj.get("explanation")?.asString ?: "",
                modelUsed = model
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun buildAnalysisPrompt(
        symbol: String,
        signals: List<Signal>,
        newsItems: List<NewsItem>,
        indicators: Map<String, Double>,
        marketData: MarketData
    ): String = buildString {
        appendLine("Analyze this intraday trade opportunity for NSE/BSE stock: $symbol")
        appendLine()
        appendLine("Current Price: ₹${marketData.lastPrice}")
        appendLine("Change: ${String.format("%.2f", marketData.changePercent)}%")
        appendLine("Volume: ${marketData.volume} (${String.format("%.1f", marketData.relativeVolume)}x avg)")
        appendLine()
        appendLine("Triggered Signals:")
        signals.forEach { appendLine("- ${it.type.displayName}: ${it.description} (strength: ${String.format("%.2f", it.strength)})") }
        appendLine()
        appendLine("Technical Indicators:")
        indicators.forEach { (k, v) -> appendLine("- $k: ${String.format("%.4f", v)}") }
        appendLine()
        if (newsItems.isNotEmpty()) {
            appendLine("Recent News:")
            newsItems.take(3).forEach { appendLine("- [${it.sentiment.displayName}] ${it.headline}") }
        }
        appendLine()
        appendLine("Return JSON with fields: thesis, sentiment_summary, key_risks (array), confidence_adjustment (-0.2 to 0.2), explanation")
    }

    companion object {
        private const val SYSTEM_PROMPT = """You are a quantitative trading analyst specializing in Indian markets (NSE/BSE).
Analyze trade setups based on technical signals, indicators, and news.
Be conservative and prioritize capital protection. Only recommend when conviction is high.
Always identify risks. Your confidence adjustment should penalize uncertainty.
Respond ONLY with valid JSON. No explanations outside JSON."""
    }
}
