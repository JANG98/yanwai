package dev.jev.wechatmood.hook

import android.content.Context

/**
 * 会话级分析开关存储。
 *
 * 每个微信聊天（按 talker 区分）有独立的分析开关，默认关闭。
 * 用户在聊天界面通过「绘制」开关单独开启/关闭当前聊天的分析。
 *
 * 存储在微信进程的 SharedPreferences 中，不需要跨进程同步。
 */
object ChatSwitchStore {
    private const val FILE_NAME = "yanwai_chat_switches"

    /**
     * 检查某个聊天是否开启了分析。
     * 默认关闭（没有记录即为关闭）。
     */
    fun isEnabled(context: Context, talker: String): Boolean {
        if (talker.isBlank()) return false
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(keyOf(talker), false)
    }

    /**
     * 设置某个聊天的分析开关。
     */
    fun setEnabled(context: Context, talker: String, enabled: Boolean) {
        if (talker.isBlank()) return
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(keyOf(talker), enabled).apply()
    }

    /**
     * 切换某个聊天的分析开关，返回切换后的状态。
     */
    fun toggle(context: Context, talker: String): Boolean {
        val newState = !isEnabled(context, talker)
        setEnabled(context, talker, newState)
        return newState
    }

    /**
     * 清除所有聊天的开关记录（用于重置）。
     */
    fun clearAll(context: Context) {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    private fun keyOf(talker: String): String = "chat_enabled_${talker.hashCode()}"
}
