package com.example.myapplication3.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

class RetryInterceptor(private val maxRetries: Int = 3) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response: Response? = null
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                response?.close()
                response = chain.proceed(request)
                if (response!!.isSuccessful) return response!!
                if (response!!.code in 400..499) return response!!
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    Thread.sleep((1000L * (attempt + 1)).coerceAtMost(5000L))
                }
            }
        }

        return response ?: throw lastException ?: Exception("Request failed after $maxRetries retries")
    }
}
