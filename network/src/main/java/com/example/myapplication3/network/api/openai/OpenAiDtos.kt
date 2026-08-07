package com.example.myapplication3.network.api.openai

import com.google.gson.annotations.SerializedName

data class OpenAiChatRequest(
    @SerializedName("model") val model: String = "gpt-4o",
    @SerializedName("messages") val messages: List<OpenAiMessage>,
    @SerializedName("temperature") val temperature: Double = 0.3,
    @SerializedName("max_tokens") val maxTokens: Int = 1000,
    @SerializedName("response_format") val responseFormat: OpenAiResponseFormat? = null
)

data class OpenAiMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class OpenAiResponseFormat(
    @SerializedName("type") val type: String = "json_object"
)

data class OpenAiChatResponse(
    @SerializedName("id") val id: String,
    @SerializedName("object") val obj: String,
    @SerializedName("created") val created: Long,
    @SerializedName("model") val model: String,
    @SerializedName("choices") val choices: List<OpenAiChoice>,
    @SerializedName("usage") val usage: OpenAiUsage?
)

data class OpenAiChoice(
    @SerializedName("index") val index: Int,
    @SerializedName("message") val message: OpenAiMessage,
    @SerializedName("finish_reason") val finishReason: String
)

data class OpenAiUsage(
    @SerializedName("prompt_tokens") val promptTokens: Int,
    @SerializedName("completion_tokens") val completionTokens: Int,
    @SerializedName("total_tokens") val totalTokens: Int
)
