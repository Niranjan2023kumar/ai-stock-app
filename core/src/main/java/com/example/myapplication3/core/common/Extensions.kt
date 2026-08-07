package com.example.myapplication3.core.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

fun Double.roundTo(decimals: Int): Double {
    val factor = Math.pow(10.0, decimals.toDouble())
    return Math.round(this * factor) / factor
}

fun Double.toPercent(): String = "%.2f%%".format(this)

fun Double.toRupee(): String = "₹%.2f".format(this)

fun Double.toCompactRupee(): String = when {
    this >= 1_00_00_000 -> "₹%.2fCr".format(this / 1_00_00_000)
    this >= 1_00_000 -> "₹%.2fL".format(this / 1_00_000)
    this >= 1_000 -> "₹%.1fK".format(this / 1_000)
    else -> "₹%.2f".format(this)
}

fun Long.toCompactVolume(): String = when {
    this >= 1_00_00_000 -> "%.1fCr".format(this / 1_00_00_000.0)
    this >= 1_00_000 -> "%.1fL".format(this / 1_00_000.0)
    this >= 1_000 -> "%.1fK".format(this / 1_000.0)
    else -> this.toString()
}

fun Double.scoreToColor(): Long = when {
    this >= 0.8 -> 0xFF00C853L
    this >= 0.6 -> 0xFF69F0AEL
    this >= 0.4 -> 0xFFFFD740L
    this >= 0.2 -> 0xFFFF6D00L
    else -> 0xFFD50000L
}

fun <T> Flow<T>.resultFlow(): Flow<Result<T>> = this
    .map { Result.Success(it) as Result<T> }
    .catch { e ->
        emit(Result.Error(
            when (e) {
                is AppException -> e
                else -> AppException.UnknownException(e.message ?: "Unknown error", e)
            }
        ))
    }

fun List<Double>.mean(): Double = if (isEmpty()) 0.0 else sum() / size

fun List<Double>.stdDev(): Double {
    if (size < 2) return 0.0
    val avg = mean()
    return Math.sqrt(map { (it - avg) * (it - avg) }.sum() / (size - 1))
}

fun List<Double>.percentile(p: Double): Double {
    if (isEmpty()) return 0.0
    val sorted = sorted()
    val index = (p / 100.0 * (sorted.size - 1))
    val lower = sorted[index.toInt()]
    val upper = if (index.toInt() + 1 < sorted.size) sorted[index.toInt() + 1] else lower
    return lower + (upper - lower) * (index - index.toInt())
}
