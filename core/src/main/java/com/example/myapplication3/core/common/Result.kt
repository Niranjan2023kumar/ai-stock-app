package com.example.myapplication3.core.common

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val exception: AppException) : Result<Nothing>()
    data object Loading : Result<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error
    val isLoading: Boolean get() = this is Loading

    fun getOrNull(): T? = (this as? Success)?.data

    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Error -> throw exception
        is Loading -> throw IllegalStateException("Result is still loading")
    }

    inline fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
        is Loading -> Loading
    }

    inline fun onSuccess(action: (T) -> Unit): Result<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onError(action: (AppException) -> Unit): Result<T> {
        if (this is Error) action(exception)
        return this
    }
}

sealed class AppException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NetworkException(message: String, val code: Int = -1, cause: Throwable? = null) : AppException(message, cause)
    class ApiException(message: String, val code: Int, val provider: String) : AppException(message)
    class RateLimitException(message: String, val retryAfterMs: Long) : AppException(message)
    class CircuitBreakerOpenException(val provider: String) : AppException("Circuit breaker open for $provider")
    class DatabaseException(message: String, cause: Throwable? = null) : AppException(message, cause)
    class DataValidationException(message: String, val field: String) : AppException(message)
    class InsufficientDataException(message: String) : AppException(message)
    class MarketClosedException(message: String) : AppException(message)
    class NoInternetException : AppException("No internet connection")
    class UnknownException(message: String, cause: Throwable? = null) : AppException(message, cause)
}
