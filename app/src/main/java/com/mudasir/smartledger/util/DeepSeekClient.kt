package com.mudasir.smartledger.util

import com.mudasir.smartledger.util.AiSettings.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.net.UnknownHostException

/**
 * OpenAI 兼容的 LLM 客户端，适配 DeepSeek / GLM / Kimi 等。
 * 这些服务均暴露 /chat/completions 接口，请求/响应结构一致。
 */
object DeepSeekClient {

    private interface ChatApi {
        @POST("chat/completions")
        suspend fun chat(
            @Header("Authorization") auth: String,
            @Body body: ChatRequest
        ): Response<ChatResponse>
    }

    // 复用通用 OpenAI 兼容结构（与原 Groq data class 等价）
    data class ChatRequest(val model: String, val messages: List<AiMessage>, val temperature: Double? = null)
    data class AiMessage(val role: String, val content: String)
    data class ChatResponse(val choices: List<Choice> = emptyList())
    data class Choice(val message: AiMessage)

    // baseUrl -> service 的缓存，避免重复构建
    private val serviceCache = mutableMapOf<String, ChatApi>()

    @Synchronized
    private fun service(baseUrl: String): ChatApi {
        return serviceCache.getOrPut(baseUrl) {
            Retrofit.Builder()
                .baseUrl(ensureTrailingSlash(baseUrl))
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ChatApi::class.java)
        }
    }

    private fun ensureTrailingSlash(url: String): String =
        if (url.endsWith("/")) url else "$url/"

    /**
     * 发送一次对话。成功返回内容文本；失败抛出带友好提示的 [IllegalStateException]。
     */
    suspend fun complete(config: Config, userPrompt: String): String = withContext(Dispatchers.IO) {
        if (!config.isReady) throw IllegalStateException("请先在设置中配置 API Key。")
        try {
            val cleanPrompt = userPrompt.replace("\"", "'").replace("\n", " ").replace("\r", " ").replace("\\", "/")
            val req = ChatRequest(
                model = config.model,
                messages = listOf(AiMessage("user", cleanPrompt)),
                temperature = 0.4
            )
            val resp: Response<ChatResponse> = service(config.baseUrl)
                .chat("Bearer ${config.apiKey}", req)

            if (resp.isSuccessful) {
                resp.body()?.choices?.firstOrNull()?.message?.content
                    ?: throw IllegalStateException("模型未返回内容。")
            } else {
                val detail = runCatching { resp.errorBody()?.string() }.getOrNull().orEmpty()
                throw IllegalStateException(friendlyHttpError(resp.code(), detail))
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: UnknownHostException) {
            throw IllegalStateException("无网络连接，请检查网络后重试。")
        } catch (e: Exception) {
            throw IllegalStateException("AI 服务暂不可用，请稍后再试。")
        }
    }

    private fun friendlyHttpError(code: Int, detail: String): String = when (code) {
        400 -> "请求格式有误：$detail"
        401 -> "认证失败：API Key 无效，请检查设置。"
        403 -> "无访问权限：$detail"
        404 -> "接口或模型不存在，请检查 baseUrl 与模型名。"
        429 -> "请求过于频繁或额度不足，请稍后再试。"
        500, 502, 503 -> "服务端暂时不可用（$code），请稍后再试。"
        else -> "AI 请求失败（$code）。"
    }
}
