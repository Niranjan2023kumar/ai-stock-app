package com.example.myapplication3.network.api.anthropic

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface AnthropicApiService {

    @POST("messages")
    suspend fun createMessage(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: AnthropicMessageRequest
    ): Response<AnthropicMessageResponse>
}
