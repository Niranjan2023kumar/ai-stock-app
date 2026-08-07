
package com.example.myapplication3.strategy.engine
import com.example.myapplication3.ai.engine.AiAnalysisEngine
import com.example.myapplication3.core.common.AppException
import com.example.myapplication3.core.common.Constants
import com.example.myapplication3.core.common.Result
import com.example.myapplication3.core.domain.model.*
import com.example.myapplication3.database.dao.AuditTrailDao
import com.example.myapplication3.database.dao.RecommendationDao
import com.example.myapplication3.database.entity.AuditTrailEntity
import com.example.myapplication3.database.entity.RecommendationEntity
import com.example.myapplication3.news.repository.NewsRepository
import com.example.myapplication3.risk.calculator.RiskCalculator
import com.example.myapplication3.scanner.detector.ScanResult
import com.example.myapplication3.scanner.technical.TechnicalIndicators
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class RecommendationEngine @Inject constructor(
    private val aiEngine: AiAnalysisEngine,
    private val riskCalculator: RiskCalculator,
    private val newsRepository: NewsRepository,
    private val recommendationDao: RecommendationDao,
    private val auditTrailDao: AuditTrailDao,
    private val gson: Gson
) {

    fun getActiveRecommendations(): Flow<List<Recommendation>> =
        recommendationDao.getActiveRecommendations().map { entities ->
            entities.map { it.toDomain() }
        }

    suspend fun generateRecommendation(
        stock: Stock,
        candles: List<Candle>,
        marketData: MarketData,
        scanResult: ScanResult
    ): Result<Recommendation> {
        if (candles.size < 50) {
            return Result.Error(AppException.InsufficientDataException("Insufficient candle data for ${stock.symbol}"))
        }

        val riskMetrics = riskCalculator.calculateRisk(stock, candles, marketData)
        if (!riskMetrics.isTradeAllowed) {
            return Result.Error(AppException.InsufficientDataException(
                "Trade rejected for ${stock.symbol}: ${riskMetrics.rejectionReason}"
            ))
        }

        val indicators = buildIndicatorMap(candles)
        val newsResult = newsRepository.getSymbolNews(stock.symbol)
        val newsItems = if (newsResult is Result.Success) newsResult.data else emptyList()

        val aiResult = aiEngine.analyzeTrade(
            symbol = stock.symbol,
            signals = scanResult.signals,
            newsItems = newsItems,
            indicators = indicators,
            marketData = marketData
        )

        val aiAnalysis = if (aiResult is Result.Success) aiResult.data else null

        val direction = determineDirection(scanResult.signals)
        val entryPrice = marketData.lastPrice
        val atrValues = TechnicalIndicators.atr(candles, 14)
        // Stop-loss/targets must be based on a REAL ATR — never a fabricated 1%-of-price.
        val atr = atrValues.lastOrNull()
        if (atr == null || atr <= 0.0) {
            return Result.Error(AppException.InsufficientDataException("ATR unavailable for ${stock.symbol}"))
        }

        val (stopLoss, target1, target2) = calculateLevels(direction, entryPrice, atr)

        val rr = calculateRiskReward(direction, entryPrice, stopLoss, target1)
        // 1e-6 tolerance: with real prices reward/risk computes as e.g. 1.9999999999
        // instead of exactly 2.0, and a strict < would reject valid trades at random
        if (rr < Constants.MIN_RISK_REWARD_RATIO - 1e-6) {
            return Result.Error(AppException.InsufficientDataException(
                "Insufficient risk/reward for ${stock.symbol}: ${String.format("%.2f", rr)}"
            ))
        }

        val confidenceScore = calculateConfidence(
            scanResult = scanResult,
            riskMetrics = riskMetrics,
            newsItems = newsItems,
            aiAdjustment = aiAnalysis?.confidenceAdjustment ?: 0.0
        )

        if (confidenceScore < Constants.MIN_CONFIDENCE_SCORE) {
            return Result.Error(AppException.InsufficientDataException(
                "Confidence too low for ${stock.symbol}: ${String.format("%.2f", confidenceScore)}"
            ))
        }

        val thesis = aiAnalysis?.thesis ?: buildLocalThesis(stock.symbol, scanResult.signals, direction)
        val riskFactors = (aiAnalysis?.keyRisks ?: emptyList()) +
                riskMetrics.flags.map { it.description }

        val rec = Recommendation(
            id = UUID.randomUUID().toString(),
            symbol = stock.symbol,
            exchange = stock.exchange,
            generatedAt = System.currentTimeMillis(),
            direction = direction,
            currentPrice = marketData.lastPrice,
            entryPrice = entryPrice,
            stopLoss = stopLoss,
            target1 = target1,
            target2 = target2,
            riskRewardRatio = rr,
            confidenceScore = confidenceScore,
            riskScore = riskMetrics.overallRiskScore,
            thesis = thesis,
            triggeredSignals = scanResult.signals,
            contributingNews = newsItems.take(3).map { it.headline },
            riskFactors = riskFactors.distinct().take(5),
            indicatorValues = indicators
        )

        recommendationDao.insert(rec.toEntity())
        saveAuditTrail(rec, aiAnalysis?.modelUsed ?: "Local", candles.size, indicators, newsItems)

        return Result.Success(rec)
    }

    private fun buildIndicatorMap(candles: List<Candle>): Map<String, Double> {
        val map = mutableMapOf<String, Double>()
        TechnicalIndicators.rsi(candles, 14).lastOrNull()?.let { map["rsi"] = it }
        TechnicalIndicators.ema(candles, 20).lastOrNull()?.let { map["ema20"] = it }
        TechnicalIndicators.ema(candles, 50).lastOrNull()?.let { map["ema50"] = it }
        TechnicalIndicators.atr(candles, 14).lastOrNull()?.let { map["atr"] = it }
        TechnicalIndicators.vwap(candles).lastOrNull()?.let { map["vwap"] = it }
        TechnicalIndicators.macd(candles).lastOrNull()?.let {
            map["macd"] = it.macd
            map["macdSignal"] = it.signal
            map["macdHistogram"] = it.histogram
        }
        map["relativeVolume"] = TechnicalIndicators.relativeVolume(candles)
        return map
    }

    private fun determineDirection(signals: List<Signal>): TradeDirection {
        val bullish = signals.count { it.type.isBullish == true }
        val bearish = signals.count { it.type.isBullish == false }
        return if (bullish >= bearish) TradeDirection.LONG else TradeDirection.SHORT
    }

    private fun calculateLevels(direction: TradeDirection, entry: Double, atr: Double): Triple<Double, Double, Double> {
        // SL = 1.5×ATR → risk = 1.5×ATR; T1 = 3.0×ATR → reward = 3.0×ATR → RR = 2.0 (passes MIN_RISK_REWARD_RATIO)
        return if (direction == TradeDirection.LONG) {
            Triple(
                entry - atr * 1.5,
                entry + atr * 3.0,
                entry + atr * 5.0
            )
        } else {
            Triple(
                entry + atr * 1.5,
                entry - atr * 3.0,
                entry - atr * 5.0
            )
        }
    }

    private fun calculateRiskReward(direction: TradeDirection, entry: Double, sl: Double, target: Double): Double {
        val risk = abs(entry - sl)
        val reward = abs(target - entry)
        return if (risk > 0) reward / risk else 0.0
    }

    private fun calculateConfidence(
        scanResult: ScanResult,
        riskMetrics: RiskMetrics,
        newsItems: List<NewsItem>,
        aiAdjustment: Double
    ): Double {
        val signalScore = scanResult.overallScore * 0.4
        val riskPenalty = (1.0 - riskMetrics.overallRiskScore) * 0.3
        val newsBonus = if (newsItems.isEmpty()) 0.0 else {
            (newsItems.map { it.sentimentScore }.average() * 0.5 + 0.5) * 0.1
        }
        val dataQuality = riskMetrics.dataQualityScore * 0.2
        val raw = signalScore + riskPenalty + newsBonus + dataQuality + aiAdjustment
        return raw.coerceIn(0.0, 1.0)
    }

    private fun buildLocalThesis(symbol: String, signals: List<Signal>, direction: TradeDirection): String {
        val dirStr = if (direction == TradeDirection.LONG) "long" else "short"
        val topSignals = signals.take(2).joinToString(", ") { it.type.displayName }
        return "$symbol $dirStr setup triggered by: $topSignals"
    }

    private suspend fun saveAuditTrail(
        rec: Recommendation,
        aiModel: String,
        candleCount: Int,
        indicators: Map<String, Double>,
        newsItems: List<NewsItem>
    ) {
        val entry = AuditTrailEntity(
            id = UUID.randomUUID().toString(),
            recommendationId = rec.id,
            timestamp = rec.generatedAt,
            symbol = rec.symbol,
            // Real inputs: the actual candle count analysed and the model that ran.
            inputsJson = gson.toJson(mapOf("candleCount" to candleCount, "provider" to aiModel)),
            scoresJson = gson.toJson(mapOf("confidence" to rec.confidenceScore, "risk" to rec.riskScore, "rr" to rec.riskRewardRatio)),
            newsUsedJson = gson.toJson(newsItems.take(3).map { it.id }),
            indicatorsUsedJson = gson.toJson(indicators),
            signalsUsedJson = gson.toJson(rec.triggeredSignals.map { it.type.name }),
            finalRecommendation = "${rec.direction} ${rec.symbol} entry:${rec.entryPrice} sl:${rec.stopLoss} t1:${rec.target1}",
            aiModelUsed = aiModel
        )
        auditTrailDao.insert(entry)
    }

    private fun Recommendation.toEntity() = RecommendationEntity(
        id = id,
        symbol = symbol,
        exchange = exchange.name,
        generatedAt = generatedAt,
        direction = direction.name,
        currentPrice = currentPrice,
        entryPrice = entryPrice,
        stopLoss = stopLoss,
        target1 = target1,
        target2 = target2,
        riskRewardRatio = riskRewardRatio,
        confidenceScore = confidenceScore,
        riskScore = riskScore,
        thesis = thesis,
        triggeredSignalsJson = gson.toJson(triggeredSignals.map { it.type.name }),
        contributingNewsJson = gson.toJson(contributingNews),
        riskFactorsJson = gson.toJson(riskFactors),
        indicatorValuesJson = gson.toJson(indicatorValues),
        status = status.name,
        outcome = outcome?.name
    )

    private fun RecommendationEntity.toDomain(): Recommendation = Recommendation(
        id = id,
        symbol = symbol,
        exchange = Exchange.valueOf(exchange),
        generatedAt = generatedAt,
        direction = TradeDirection.valueOf(direction),
        currentPrice = currentPrice,
        entryPrice = entryPrice,
        stopLoss = stopLoss,
        target1 = target1,
        target2 = target2,
        riskRewardRatio = riskRewardRatio,
        confidenceScore = confidenceScore,
        riskScore = riskScore,
        thesis = thesis,
        // Rebuild the triggering signals from the persisted SignalType names instead
        // of dropping them, so the UI shows the real signals behind the recommendation.
        triggeredSignals = try {
            gson.fromJson(triggeredSignalsJson, Array<String>::class.java)
                .mapNotNull { name -> runCatching { SignalType.valueOf(name) }.getOrNull() }
                .map { t -> Signal(type = t, symbol = symbol, strength = 0.0, timestamp = generatedAt, description = t.displayName) }
        } catch (e: Exception) { emptyList() },
        contributingNews = try { gson.fromJson(contributingNewsJson, Array<String>::class.java).toList() } catch (e: Exception) { emptyList() },
        riskFactors = try { gson.fromJson(riskFactorsJson, Array<String>::class.java).toList() } catch (e: Exception) { emptyList() },
        indicatorValues = try { gson.fromJson(indicatorValuesJson, Map::class.java) as Map<String, Double> } catch (e: Exception) { emptyMap() },
        status = try { RecommendationStatus.valueOf(status) } catch (e: Exception) { RecommendationStatus.ACTIVE },
        outcome = outcome?.let { try { RecommendationOutcome.valueOf(it) } catch (e: Exception) { null } }
    )
}
