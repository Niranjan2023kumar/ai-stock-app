package com.example.myapplication3.network.api.anthropic

import com.google.gson.annotations.SerializedName

data class AnthropicMessageRequest(
    @SerializedName("model") val model: String = "claude-sonnet-4-6",
    @SerializedName("max_tokens") val maxTokens: Int = 1000,
    @SerializedName("messages") val messages: List<AnthropicMessage>,
    @SerializedName("system") val system: String? = null,
    @SerializedName("temperature") val temperature: Double = 0.3
)

data class AnthropicMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class AnthropicMessageResponse(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String,
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: List<AnthropicContent>,
    @SerializedName("model") val model: String,
    @SerializedName("stop_reason") val stopReason: String,
    @SerializedName("usage") val usage: AnthropicUsage
) {
    val text: String get() = content.filterIsInstance<AnthropicContent>()
        .firstOrNull()?.text ?: ""
}

data class AnthropicContent(
    @SerializedName("type") val type: String,
    @SerializedName("text") val text: String = ""
)

data class AnthropicUsage(
    @SerializedName("input_tokens") val inputTokens: Int,
    @SerializedName("output_tokens") val outputTokens: Int
)
