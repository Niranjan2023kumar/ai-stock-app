package com.example.myapplication3.network.api.openai

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface OpenAiApiService {

    @POST("chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiChatRequest
    ): Response<OpenAiChatResponse>
}
