package com.example.myapplication3.core.domain.model

data class Stock(
    val symbol: String,
    val exchange: Exchange,
    val name: String,
    val sector: Sector,
    val isin: String = "",
    val faceValue: Double = 0.0,
    val marketCap: Double = 0.0,
    val freeFloat: Double = 0.0,
    val isCircuitRestricted: Boolean = false,
    val isSuspended: Boolean = false
) {
    val fullSymbol: String get() = "${exchange.code}:$symbol"
}

enum class Exchange(val code: String, val displayName: String) {
    NSE("NSE", "National Stock Exchange"),
    BSE("BSE", "Bombay Stock Exchange")
}

enum class Sector(val displayName: String) {
    BANKING("Banking"),
    FINANCIAL_SERVICES("Financial Services"),
    IT("Information Technology"),
    PHARMA("Pharmaceuticals"),
    AUTO("Automobiles"),
    FMCG("FMCG"),
    METALS("Metals & Mining"),
    OIL_GAS("Oil & Gas"),
    REALTY("Realty"),
    TELECOM("Telecom"),
    INFRA("Infrastructure"),
    POWER("Power"),
    MEDIA("Media"),
    CHEMICALS("Chemicals"),
    CONSUMER_DURABLES("Consumer Durables"),
    OTHERS("Others")
}
