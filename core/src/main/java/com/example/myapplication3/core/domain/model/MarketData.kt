package com.example.myapplication3.core.domain.model

data class MarketData(
    val symbol: String,
    val exchange: Exchange,
    val lastPrice: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val previousClose: Double,
    val change: Double,
    val changePercent: Double,
    val volume: Long,
    val deliveryVolume: Long = 0L,
    val vwap: Double = 0.0,
    val bidPrice: Double = 0.0,
    val askPrice: Double = 0.0,
    val bidQty: Long = 0L,
    val askQty: Long = 0L,
    val fiftyTwoWeekHigh: Double = 0.0,
    val fiftyTwoWeekLow: Double = 0.0,
    val averageVolume20D: Long = 0L,
    val timestamp: Long,
    val isMarketOpen: Boolean = false
) {
    val spread: Double get() = if (askPrice > 0 && bidPrice > 0) askPrice - bidPrice else 0.0
    val spreadPercent: Double get() = if (bidPrice > 0) (spread / bidPrice) * 100 else 0.0
    val relativeVolume: Double get() = if (averageVolume20D > 0) volume.toDouble() / averageVolume20D else 0.0
    val deliveryPercent: Double get() = if (volume > 0) (deliveryVolume.toDouble() / volume) * 100 else 0.0
    val gapPercent: Double get() = if (previousClose > 0) ((open - previousClose) / previousClose) * 100 else 0.0
    val isGapUp: Boolean get() = gapPercent > 0.5
    val isGapDown: Boolean get() = gapPercent < -0.5
}

data class MarketDepth(
    val symbol: String,
    val bids: List<DepthLevel>,
    val asks: List<DepthLevel>,
    val timestamp: Long
) {
    val totalBidQty: Long get() = bids.sumOf { it.quantity }
    val totalAskQty: Long get() = asks.sumOf { it.quantity }
    val bidAskRatio: Double get() = if (totalAskQty > 0) totalBidQty.toDouble() / totalAskQty else 0.0
}

data class DepthLevel(val price: Double, val quantity: Long, val orders: Int = 0)

data class SectorData(
    val sector: Sector,
    val advances: Int,
    val declines: Int,
    val unchanged: Int,
    val strengthScore: Double,
    val momentumScore: Double,
    val timestamp: Long
) {
    val advanceDeclineRatio: Double get() = if (declines > 0) advances.toDouble() / declines else advances.toDouble()
    val totalStocks: Int get() = advances + declines + unchanged
    val advancePercent: Double get() = if (totalStocks > 0) (advances.toDouble() / totalStocks) * 100 else 0.0
}

data class MarketBreadth(
    val advances: Int,
    val declines: Int,
    val unchanged: Int,
    val newHighs: Int,
    val newLows: Int,
    val aboveVwap: Int,
    val belowVwap: Int,
    val timestamp: Long
) {
    val totalStocks: Int get() = advances + declines + unchanged
    val advanceDeclineRatio: Double get() = if (declines > 0) advances.toDouble() / declines else advances.toDouble()
    val healthScore: Double get() {
        val adScore = (advanceDeclineRatio.coerceAtMost(5.0) / 5.0) * 50
        val highLowScore = if (newHighs + newLows > 0)
            (newHighs.toDouble() / (newHighs + newLows)) * 50 else 25.0
        return adScore + highLowScore
    }
}
