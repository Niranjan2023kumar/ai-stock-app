package com.example.myapplication3.network.circuit

import com.example.myapplication3.core.common.AppException
import com.example.myapplication3.core.common.Constants
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class CircuitBreaker(
    private val providerName: String,
    private val failureThreshold: Int = Constants.CIRCUIT_BREAKER_FAILURE_THRESHOLD,
    private val timeoutMs: Long = Constants.CIRCUIT_BREAKER_TIMEOUT_MS
) {
    private enum class State { CLOSED, OPEN, HALF_OPEN }

    private val state = AtomicReference(State.CLOSED)
    private val failureCount = AtomicInteger(0)
    private val lastFailureTime = AtomicLong(0)

    val isClosed: Boolean get() = state.get() == State.CLOSED
    val isOpen: Boolean get() = state.get() == State.OPEN

    suspend fun <T> execute(block: suspend () -> T): T {
        when (state.get()) {
            State.OPEN -> {
                if (System.currentTimeMillis() - lastFailureTime.get() > timeoutMs) {
                    state.compareAndSet(State.OPEN, State.HALF_OPEN)
                } else {
                    throw AppException.CircuitBreakerOpenException(providerName)
                }
            }
            else -> {}
        }

        return try {
            val result = block()
            onSuccess()
            result
        } catch (e: Exception) {
            onFailure()
            throw e
        }
    }

    private fun onSuccess() {
        failureCount.set(0)
        state.set(State.CLOSED)
    }

    private fun onFailure() {
        lastFailureTime.set(System.currentTimeMillis())
        if (failureCount.incrementAndGet() >= failureThreshold) {
            state.set(State.OPEN)
        }
    }

    fun reset() {
        failureCount.set(0)
        state.set(State.CLOSED)
    }
}
