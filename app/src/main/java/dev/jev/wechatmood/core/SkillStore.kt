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
 *
 * 支持两种来源的技能：
 * - custom：用户在应用内手动创建的简单技能
 * - library：从 GitHub 下载的完整 skill 仓库（文件存储在 files/skills/ 目录）
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
                    source = obj.optString("source", Skill.SOURCE_CUSTOM),
                    version = obj.optString("version", ""),
                    dirName = obj.optString("dirName", ""),
                    repoUrl = obj.optString("repoUrl", ""),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun enabledSkills(prefs: SharedPreferences): List<Skill> =
        loadAll(prefs).filter { it.enabled }

    /** 获取所有自定义技能（custom 来源） */
    fun customSkills(prefs: SharedPreferences): List<Skill> =
        loadAll(prefs).filter { it.source == Skill.SOURCE_CUSTOM }

    /** 获取所有技能库技能（library 来源） */
    fun librarySkills(prefs: SharedPreferences): List<Skill> =
        loadAll(prefs).filter { it.source == Skill.SOURCE_LIBRARY }

    fun saveAll(prefs: SharedPreferences, skills: List<Skill>) {
        val arr = JSONArray()
        skills.forEach { s ->
            arr.put(JSONObject()
                .put("id", s.id)
                .put("name", s.name)
                .put("description", s.description)
                .put("prompt", s.prompt)
                .put("enabled", s.enabled)
                .put("source", s.source)
                .put("version", s.version)
                .put("dirName", s.dirName)
                .put("repoUrl", s.repoUrl))
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

    /**
     * 删除技能库技能，同时删除本地文件目录。
     */
    fun deleteLibrarySkill(context: Context, prefs: SharedPreferences, id: String): List<Skill> {
        val skill = loadAll(prefs).firstOrNull { it.id == id }
        if (skill?.isLibrarySkill == true && skill.dirName.isNotBlank()) {
            SkillDownloader.deleteSkillDir(context, skill.dirName)
        }
        return delete(prefs, id)
    }

    fun toggle(prefs: SharedPreferences, id: String, enabled: Boolean): List<Skill> {
        val all = loadAll(prefs).map { if (it.id == id) it.copy(enabled = enabled) else it }
        saveAll(prefs, all)
        return all
    }

    /**
     * 将已启用技能的提示拼接成一段注入文本，供分析流程使用。
     * 功能总开关关闭或没有启用技能时返回 null。
     *
     * 对于技能库技能（library），使用完整的 SKILL.md 内容作为提示；
     * 对于自定义技能（custom），使用用户输入的提示词。
     */
    fun activePrompt(context: Context): String? {
        val prefs = context.getSharedPreferences(ModulePrefs.FILE_NAME, Context.MODE_PRIVATE)
        if (!isFeatureEnabled(prefs)) return null
        val active = enabledSkills(prefs)
        if (active.isEmpty()) return null

        return buildString {
            append("你现在需要参考以下已启用的技能/角色设定来生成回复建议：\n\n")
            active.forEachIndexed { index, s ->
                append("=== 技能 ${index + 1}：${s.name} ===\n")
                if (s.description.isNotBlank()) {
                    append("【技能描述】${s.description}\n")
                }
                append("【技能指令】\n")
                append(s.prompt.ifBlank { s.description })
                append("\n\n")
            }
            append("请严格参考上述技能的风格、原则和指令来生成回复建议。")
        }
    }
}
