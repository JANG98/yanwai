package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.ApiSettings
import dev.jev.wechatmood.core.MoodLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Shared by the Android app and opt-in live verification. Never reports raw service bodies. */
class JevHttpClient {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun exchange(payload: JSONObject, settings: ApiSettings): String {
        check(settings.isConfigured) { "请先填写并保存 API Key" }
        val request = Request.Builder().url(settings.endpoint)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (!response.isSuccessful || json?.has("error") == true) {
                    val error = json?.optJSONObject("error")
                    val code = if (response.isSuccessful) error?.optInt("code", response.code) ?: response.code else response.code
                    val reason = when {
                        error?.optString("type") == "customer_verification_required" ->
                            "Vercel 账户尚未验证，请到 AI Gateway 控制台绑定信用卡后重试"
                        code == 401 -> "API Key 无效，请检查所选渠道与密钥是否对应"
                        code == 403 -> "账户或模型尚未授权，请到所选渠道控制台检查"
                        code == 402 -> "模型账户额度不足，请到所选渠道充值或检查额度"
                        code == 429 -> "请求过于频繁，请稍后重试"
                        code == 400 || code == 404 || code == 422 -> "接口地址或模型不受支持，请检查渠道和配置"
                        code in 300..399 -> "接口发生重定向，请填写最终的 Jev 接口地址"
                        else -> "模型服务暂不可用，请稍后重试"
                    }
                    throw IllegalStateException("$reason（HTTP ${response.code}）")
                }
                return body
            }
        } catch (_: java.io.IOException) {
            throw IllegalStateException("连接超时或网络不可用，请稍后重试")
        }
    }

    /**
     * 使用标准 OpenAI Chat Completions 接口生成 3 条回复建议。
     * 独立于 TypeSafe 协议，失败时返回空列表，不影响主要分析功能。
     */
    fun generateReplies(
        message: String,
        context: List<dev.jev.wechatmood.core.ContextMessage>,
        relationship: String,
        skillPrompt: String,
        settings: ApiSettings
    ): List<String> {
        return runCatching {
            val chatEndpoint = resolveChatEndpoint(settings.endpoint)
                ?: return emptyList()
            val chatModel = resolveChatModel(settings)

            // 构造系统提示词
            val systemPrompt = buildString {
                append("你是一个聊天回复助手。根据对方的消息、聊天上下文、关系描述和回复风格，生成 3 条可以直接复制发送的回复建议。\n\n")
                append("要求：\n")
                append("1. 每条都是完整的一句话，可直接发送，不要加序号、引号或解释\n")
                append("2. 3 条风格要有差异：一条稳妥自然、一条略带轻松/调侃、一条稍主动/推进关系\n")
                append("3. 必须结合对方原话回应，不要说空话套话\n")
                append("4. 考虑关系描述设定的亲密度和边界\n")
                append("5. 不要道歉、不要过度解释、不要问太多问题，每条只承载一个核心意思\n")
                if (relationship.isNotBlank()) append("\n关系描述：$relationship\n")
                if (skillPrompt.isNotBlank()) append("\n回复风格参考：\n$skillPrompt\n")
            }

            // 构造用户消息（包含上下文）
            val userMessage = buildString {
                if (context.isNotEmpty()) {
                    append("聊天上下文：\n")
                    context.takeLast(6).forEach { append("${it.speaker}：${it.text}\n") }
                    append("\n")
                }
                append("对方当前消息：$message\n\n")
                append("请生成 3 条回复建议，直接输出 JSON 数组格式，例如：[\"回复1\",\"回复2\",\"回复3\"]")
            }

            val payload = JSONObject()
                .put("model", chatModel)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userMessage)))
                .put("temperature", 0.8)
                .put("max_tokens", 300)

            val request = Request.Builder().url(chatEndpoint)
                .header("Authorization", "Bearer ${settings.apiKey}")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    MoodLog.w("GENERATE_REPLIES_FAILED HTTP ${response.code}: ${body.take(200)}")
                    return emptyList()
                }
                val json = JSONObject(body)
                val content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")

                // 解析 JSON 数组
                val replies = parseRepliesFromContent(content)
                MoodLog.i("生成回复建议 ${replies.size} 条")
                return replies
            }
        }.getOrElse {
            MoodLog.w("GENERATE_REPLIES_EXCEPTION: ${it.message}")
            emptyList()
        }
    }

    /**
     * 从模型返回的内容中解析回复建议数组。
     * 尝试多种格式：纯 JSON 数组、包含 JSON 数组的文本、按行分割。
     */
    private fun parseRepliesFromContent(content: String): List<String> {
        val trimmed = content.trim()
        // 尝试直接解析 JSON 数组
        runCatching {
            val arr = JSONArray(trimmed)
            val result = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val text = arr.getString(i).trim()
                if (text.isNotBlank()) result.add(text)
            }
            if (result.isNotEmpty()) return result.take(3)
        }
        // 尝试提取文本中的 JSON 数组
        runCatching {
            val start = trimmed.indexOf('[')
            val end = trimmed.lastIndexOf(']')
            if (start >= 0 && end > start) {
                val arr = JSONArray(trimmed.substring(start, end + 1))
                val result = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val text = arr.getString(i).trim()
                    if (text.isNotBlank()) result.add(text)
                }
                if (result.isNotEmpty()) return result.take(3)
            }
        }
        // 按行分割，去掉序号
        val lines = trimmed.lines()
            .map { it.trim().removePrefix("1.").removePrefix("2.").removePrefix("3.").removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotBlank() && it.length > 1 }
            .take(3)
        return lines
    }

    /**
     * 把 TypeSafe 协议的 endpoint 转换成标准 Chat Completions endpoint。
     */
    private fun resolveChatEndpoint(typeSafeEndpoint: String): String? {
        return when {
            typeSafeEndpoint.contains("openrouter.ai") ->
                typeSafeEndpoint.replace("/systemone", "/chat/completions")
            typeSafeEndpoint.contains("ai-gateway.vercel.sh") ->
                typeSafeEndpoint.replace("/typesafe/v1/systemone", "/v1/chat/completions")
            typeSafeEndpoint.contains("api.typesafe.ai") ->
                typeSafeEndpoint.replace("/systemone", "/chat/completions")
            typeSafeEndpoint.endsWith("/systemone") ->
                typeSafeEndpoint.replace("/systemone", "/chat/completions")
            else -> null
        }
    }

    /**
     * 解析用于 Chat Completions 的模型名。
     * TypeSafe 协议的模型名（如 jev-1.13.0）不能直接用于 Chat Completions，
     * 需要转换成通用模型名。
     */
    private fun resolveChatModel(settings: ApiSettings): String {
        val model = settings.model
        return when {
            // OpenRouter 使用带前缀的模型名
            settings.endpoint.contains("openrouter.ai") -> "openai/gpt-4o-mini"
            // Vercel AI Gateway 使用带前缀的模型名
            settings.endpoint.contains("ai-gateway.vercel.sh") -> "openai/gpt-4o-mini"
            // 其他情况，如果模型名看起来像 TypeSafe 模型，使用通用模型
            model.startsWith("jev-") || model.contains("typesafe") -> "gpt-4o-mini"
            // 否则使用用户配置的模型名
            else -> model
        }
    }
}
