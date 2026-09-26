package dev.jev.wechatmood.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 技能（Skill）存储。
 *
 * 以 JSON 数组形式保存在 SharedPreferences 中，支持增删改查和启用/禁用。
 * 技能列表随设置同步广播发送给微信进程，分析时读取已启用的技能。
 */
object SkillStore {
    private const val KEY_SKILLS = "skills_json"
    private const val KEY_SKILL_ENABLED = "skill_feature_enabled"

    /** 技能功能总开关：关闭时即使有已启用的技能也不生效。 */
    fun isFeatureEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SKILL_ENABLED, false)

    fun setFeatureEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SKILL_ENABLED, enabled).apply()
    }

    fun loadAll(prefs: SharedPreferences): List<Skill> {
        val raw = prefs.getString(KEY_SKILLS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Skill(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    description = obj.optString("description", ""),
                    prompt = obj.optString("prompt", ""),
                    enabled = obj.optBoolean("enabled", true),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun enabledSkills(prefs: SharedPreferences): List<Skill> =
        loadAll(prefs).filter { it.enabled }

    fun saveAll(prefs: SharedPreferences, skills: List<Skill>) {
        val arr = JSONArray()
        skills.forEach { s ->
            arr.put(JSONObject()
                .put("id", s.id)
                .put("name", s.name)
                .put("description", s.description)
                .put("prompt", s.prompt)
                .put("enabled", s.enabled))
        }
        prefs.edit().putString(KEY_SKILLS, arr.toString()).apply()
    }

    fun add(prefs: SharedPreferences, skill: Skill): List<Skill> {
        val all = loadAll(prefs).toMutableList()
        all.add(skill)
        saveAll(prefs, all)
        return all
    }

    fun update(prefs: SharedPreferences, skill: Skill): List<Skill> {
        val all = loadAll(prefs).map { if (it.id == skill.id) skill else it }
        saveAll(prefs, all)
        return all
    }

    fun delete(prefs: SharedPreferences, id: String): List<Skill> {
        val all = loadAll(prefs).filter { it.id != id }
        saveAll(prefs, all)
        return all
    }

    fun toggle(prefs: SharedPreferences, id: String, enabled: Boolean): List<Skill> {
        val all = loadAll(prefs).map { if (it.id == id) it.copy(enabled = enabled) else it }
        saveAll(prefs, all)
        return all
    }

    /**
     * 将已启用技能的提示拼接成一段注入文本，供分析流程使用。
     * 功能总开关关闭或没有启用技能时返回 null。
     */
    fun activePrompt(context: Context): String? {
        val prefs = context.getSharedPreferences(ModulePrefs.FILE_NAME, Context.MODE_PRIVATE)
        if (!isFeatureEnabled(prefs)) return null
        val active = enabledSkills(prefs)
        if (active.isEmpty()) return null
        return active.joinToString("\n") { s ->
            "- ${s.name}：${s.prompt.ifBlank { s.description }}"
        }
    }
}
