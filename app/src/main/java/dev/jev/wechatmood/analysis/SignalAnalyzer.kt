package dev.jev.wechatmood.analysis

import android.content.Context
import dev.jev.wechatmood.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object SignalAnalyzer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(2)
    private val client = JevHttpClient()
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val failureMessages = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var appContext: Context? = null
    fun failure(key: String): String? = failureMessages[key]
    fun retryFailure(key: String) { failures.remove(key); failureMessages.remove(key) }

    /** 初始化，传入 ApplicationContext，用于持久化存储。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun submit(input: AnalysisInput, stillVisible: () -> Boolean = { true }): String? {
        if (!ModulePrefs.canAnalyze) return null
        if (MessagePolicy.textOrNull(input.text) == null) return null
        val key = input.key
        if (System.currentTimeMillis() - (failures[key] ?: 0L) < 30_000) return key

        // 先检查持久化存储：如果已经分析过，直接使用缓存，不调用 AI
        appContext?.let { ctx ->
            ChatAnalysisStore.findByKey(ctx, key)?.let { cachedMood ->
                MoodStore.complete(key, cachedMood)
                MoodLog.i("使用持久化缓存，跳过 AI 调用：${cachedMood.label}")
                return key
            }
        }

        if (!MoodStore.claim(key)) return key
        failureMessages.remove(key)
        scope.launch {
            try {
                slots.withPermit {
                    ModulePrefs.reload()
                    if (!ModulePrefs.canAnalyze || !stillVisible()) { MoodStore.release(key); return@withPermit }
                    val mood = analyze(input) {
                        ModulePrefs.reload()
                        ModulePrefs.canAnalyze && stillVisible()
                    }
                    MoodStore.complete(key, mood)
                    // 保存到持久化存储
                    appContext?.let { ctx -> ChatAnalysisStore.save(ctx, input, mood) }
                    failures.remove(key)
                    failureMessages.remove(key)
                    MoodLog.i("Jev 闲聊解读完成：${mood.label}")
                    ModulePrefs.report("Jev 分析完成，已缓存 ${MoodStore.size()} 条")
                }
            } catch (e: CancellationException) {
                MoodStore.release(key)
                throw e
            } catch (e: Exception) {
                failures[key] = System.currentTimeMillis()
                failureMessages[key] = e.message ?: "分析失败，请稍后重试"
                MoodStore.release(key)
                MoodLog.e("分析失败：${e.message}")
                ModulePrefs.report("分析失败：${e.message}")
            }
        }
        return key
    }

    suspend fun requestMood(text: String, context: List<ContextMessage> = emptyList(),
        speaker: String = "对方"): Mood = analyze(AnalysisInput(text, "sample", context, speaker = speaker))

    private suspend fun analyze(input: AnalysisInput, shouldContinue: () -> Boolean = { true }): Mood = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        // Keep both rounds on the same endpoint and credential, even if settings change mid-request.
        val settings = ModulePrefs.apiSettings()
        // 优先使用按人配置的技能，如果没有配置则使用全局启用的技能
        val skillPrompt = resolveSkillPrompt(input.talker)
        check(settings.isConfigured) { "请先在言外设置中填写并保存 API Key" }
        try {
            ChatAnalysis.analyze(input, settings.model, { client.exchange(it, settings) }, {
                job.ensureActive()
                shouldContinue()
            }, skillPrompt)
        } catch (e: org.json.JSONException) {
            throw IllegalStateException("模型返回不完整，本次不显示判断")
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("模型返回不完整，本次不显示判断")
        }
    }

    /**
     * 解析技能提示词。
     * 优先使用按人配置的技能，如果没有配置则使用全局启用的技能。
     */
    private fun resolveSkillPrompt(talker: String): String {
        val context = appContext ?: return ModulePrefs.activeSkillPrompt ?: ""
        // 检查是否为当前聊天配置了技能
        val skillId = dev.jev.wechatmood.hook.ChatSkillStore.getSkillId(context, talker)
        if (!skillId.isNullOrBlank()) {
            val prefs = context.getSharedPreferences(ModulePrefs.FILE_NAME, Context.MODE_PRIVATE)
            val skill = SkillStore.loadAll(prefs).firstOrNull { it.id == skillId && it.enabled }
            if (skill != null) {
                return buildString {
                    append("【当前聊天已加载技能：${skill.name}，以下为该技能的核心原则，用于生成回复建议】\n\n")
                    append(SkillStore.skillPrompt(skill))
                    append("\n\n以上技能仅用于调整回复建议的风格和角度，必须严格按照题目要求的 JSON 格式输出。")
                }
            }
        }
        // 回退到全局启用的技能
        return ModulePrefs.activeSkillPrompt ?: ""
    }

}
