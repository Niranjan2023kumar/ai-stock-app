package com.example.myapplication3.marketdata.mapper

import com.example.myapplication3.core.domain.model.Candle
import com.example.myapplication3.core.domain.model.CandleInterval
import com.example.myapplication3.core.domain.model.Exchange
import com.example.myapplication3.database.entity.CandleEntity
import com.example.myapplication3.network.api.polygon.PolygonCandle
import com.example.myapplication3.network.api.finnhub.FinnhubCandleResponse

object CandleMapper {

    fun fromPolygon(ticker: String, exchange: Exchange, interval: CandleInterval, candle: PolygonCandle): Candle =
        Candle(
            symbol = ticker.substringAfterLast(":"),
            timestamp = candle.timestamp,
            open = candle.open,
            high = candle.high,
            low = candle.low,
            close = candle.close,
            volume = candle.volume.toLong(),
            vwap = candle.vwap,
            interval = interval
        )

    fun fromFinnhub(symbol: String, interval: CandleInterval, response: FinnhubCandleResponse): List<Candle> {
        val closes = response.closes ?: return emptyList()
        val highs = response.highs ?: return emptyList()
        val lows = response.lows ?: return emptyList()
        val opens = response.opens ?: return emptyList()
        val volumes = response.volumes ?: return emptyList()
        val timestamps = response.timestamps ?: return emptyList()

        val size = minOf(closes.size, highs.size, lows.size, opens.size, volumes.size, timestamps.size)
        return (0 until size).map { i ->
            Candle(
                symbol = symbol,
                timestamp = timestamps[i] * 1000L,
                open = opens[i],
                high = highs[i],
                low = lows[i],
                close = closes[i],
                volume = volumes[i],
                interval = interval
            )
        }
    }

    fun toEntity(candle: Candle, exchange: Exchange): CandleEntity = CandleEntity(
        symbol = candle.symbol,
        exchange = exchange.code,
        interval = candle.interval.name,
        timestamp = candle.timestamp,
        open = candle.open,
        high = candle.high,
        low = candle.low,
        close = candle.close,
        volume = candle.volume,
        deliveryVolume = candle.deliveryVolume,
        vwap = candle.vwap,
        isComplete = candle.isComplete
    )

    fun fromEntity(entity: CandleEntity): Candle = Candle(
        symbol = entity.symbol,
        timestamp = entity.timestamp,
        open = entity.open,
        high = entity.high,
        low = entity.low,
        close = entity.close,
        volume = entity.volume,
        deliveryVolume = entity.deliveryVolume,
        vwap = entity.vwap,
        interval = CandleInterval.valueOf(entity.interval),
        isComplete = entity.isComplete
    )
}
