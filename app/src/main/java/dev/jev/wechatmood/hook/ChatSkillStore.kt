package dev.jev.wechatmood.hook

import android.content.Context
import android.content.SharedPreferences

/**
 * 按聊天对象配置技能。
 *
 * 每个聊天对象（talker）可以选择一个技能，用于生成该聊天的回复建议。
 * 存储在微信进程的 SharedPreferences 中，key 为 talker 的 hashCode。
 *
 * 与 ChatSwitchStore（会话级分析开关）和 ChatProfileStore（关系描述）类似，
 * 都是按人配置的存储。
 */
object ChatSkillStore {
    private const val FILE_NAME = "yanwai_chat_skills"
    private const val KEY_PREFIX = "chat_skill_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * 获取指定聊天对象配置的技能 ID。
     *
     * @param context 上下文
     * @param talker 聊天对象标识（微信内部的 talker 字段）
     * @return 技能 ID，如果未配置返回 null
     */
    fun getSkillId(context: Context, talker: String): String? {
        val key = "$KEY_PREFIX${talker.hashCode()}"
        return prefs(context).getString(key, null)
    }

    /**
     * 设置指定聊天对象的技能。
     *
     * @param context 上下文
     * @param talker 聊天对象标识
     * @param skillId 技能 ID，传 null 表示清除配置
     */
    fun setSkillId(context: Context, talker: String, skillId: String?) {
        val key = "$KEY_PREFIX${talker.hashCode()}"
        val editor = prefs(context).edit()
        if (skillId.isNullOrBlank()) {
            editor.remove(key)
        } else {
            editor.putString(key, skillId)
        }
        editor.apply()
    }

    /**
     * 清除指定聊天对象的技能配置。
     */
    fun clearSkill(context: Context, talker: String) {
        setSkillId(context, talker, null)
    }

    /**
     * 获取所有已配置技能的聊天对象数量。
     */
    fun configuredCount(context: Context): Int {
        return prefs(context).all.count { it.key.startsWith(KEY_PREFIX) }
    }
}
