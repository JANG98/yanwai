package dev.jev.wechatmood.core

data class ContextMessage(val speaker: String, val text: String)

data class AnalysisInput(
    val text: String,
    val talker: String,
    val context: List<ContextMessage> = emptyList(),
    val messageId: Long = 0,
    val speaker: String = "对方",
    /** 聊天对象的关系描述（如"暧昧对象""女朋友"），作为分析和建议的首要参考 */
    val relationship: String = "",
) {
    val key: String get() = MoodStore.keyOf(text, talker, context, messageId, speaker)
}

object MessagePolicy {
    const val MAX_CHARACTERS = 1000
    const val MAX_CONTEXT_MESSAGES = 10

    fun textOrNull(text: String): String? {
        if (text.codePointCount(0, text.length) > MAX_CHARACTERS) return null
        return text.trim().takeIf { it.isNotEmpty() }
    }
}
