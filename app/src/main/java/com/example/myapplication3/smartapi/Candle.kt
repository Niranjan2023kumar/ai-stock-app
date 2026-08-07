package com.example.myapplication3.smartapi

/**
 * One OHLCV bar returned by Angel One SmartAPI's historical candle endpoint.
 *
 * @property ts     Bar open time as epoch milliseconds (UTC instant). Angel One sends
 *                  timestamps like "2026-07-18T09:15:00+05:30" (IST, exchange time);
 *                  they are parsed with their zone offset, so [ts] is unambiguous.
 * @property open   Opening price of the bar.
 * @property high   Highest traded price during the bar.
 * @property low    Lowest traded price during the bar.
 * @property close  Closing (last) price of the bar.
 * @property volume Traded volume during the bar.
 */
data class Candle(
    val ts: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)
