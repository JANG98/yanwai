package dev.jev.wechatmood.hook

import android.content.Context

/**
 * 聊天对象关系描述存储。
 *
 * 按 talker（微信号/聊天对象标识）存储用户填写的关系描述，
 * 如"暧昧对象""刚认识的朋友""女朋友"等，作为消息分析和回复建议的首要参考。
 *
 * 存储在微信进程的 SharedPreferences 中。
 */
object ChatProfileStore {
    private const val FILE_NAME = "yanwai_chat_profiles"

    /**
     * 获取某个聊天对象的关系描述。
     * 没有设置时返回空字符串。
     */
    fun getRelationship(context: Context, talker: String): String {
        if (talker.isBlank()) return ""
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return prefs.getString(keyOf(talker), "") ?: ""
    }

    /**
     * 设置某个聊天对象的关系描述。
     */
    fun setRelationship(context: Context, talker: String, relationship: String) {
        if (talker.isBlank()) return
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        if (relationship.isBlank()) {
            prefs.edit().remove(keyOf(talker)).apply()
        } else {
            prefs.edit().putString(keyOf(talker), relationship.trim()).apply()
        }
    }

    /**
     * 检查某个聊天对象是否设置了关系描述。
     */
    fun hasRelationship(context: Context, talker: String): Boolean =
        getRelationship(context, talker).isNotBlank()

    /**
     * 清除所有聊天对象的关系描述。
     */
    fun clearAll(context: Context) {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    private fun keyOf(talker: String): String = "chat_relation_${talker.hashCode()}"
}
