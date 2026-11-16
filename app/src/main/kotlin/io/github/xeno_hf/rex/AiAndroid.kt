package io.github.xeno_hf.rex

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 解讀的 Android 實作。
 * - Claude：Anthropic 官方 Java SDK。
 * - DeepSeek：OpenAI 相容的 HTTPS API（https://api.deepseek.com/chat/completions）。
 * 金鑰由使用者在設定裡填，存在 Android Keystore 加密的偏好設定裡。
 */
class AndroidAi : AiClient {
    override suspend fun complete(provider: AiProvider, apiKey: String, model: String, system: String, user: String): String = withContext(Dispatchers.IO) {
        when (provider) {
            AiProvider.CLAUDE -> claude(apiKey, model, system, user)
            AiProvider.DEEPSEEK -> deepseek(apiKey, model, system, user)
        }
    }

    private fun claude(apiKey: String, model: String, system: String, user: String): String {
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
        try {
            val b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(4000L)
                .system(system)
                .addUserMessage(user)
            // 被安全分類器拒答時，改由伺服器自動換別的模型重試（只有新一代模型支援這個參數）
            if (model in FALLBACK_MODELS) {
                b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
            val msg = client.messages().create(b.build())
            if (msg.stopReason().map { it.toString() }.orElse("") == "refusal") error("Claude 拒絕回答這次的內容，請稍後再試或改用其他模型。")
            val text = msg.content().mapNotNull { blk -> blk.text().map { it.text() }.orElse(null) }.joinToString("\n").trim()
            if (text.isEmpty()) error("Claude 沒有回傳文字（${msg.stopReason().map { it.toString() }.orElse("?")}）")
            return text
        } catch (e: AnthropicServiceException) {
            throw IllegalStateException("Claude API ${e.statusCode()}：${e.message}", e)
        } finally {
            client.close()
        }
    }

    private fun deepseek(apiKey: String, model: String, system: String, user: String): String {
        val c = URL("https://api.deepseek.com/chat/completions").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000; c.readTimeout = 120_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $apiKey")
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 1500)
                .put("stream", false)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)))
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
            if (code !in 200..299) error("DeepSeek API $code：${text.take(300)}")
            val content = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
            if (content.isNullOrBlank()) error("DeepSeek 沒有回傳文字")
            return content
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private val FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5", "claude-fable-5-2", "claude-fable-5-1")
    }
}
