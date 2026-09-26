package dev.jev.wechatmood.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 技能（Skill）存储。
 *
 * 支持仓库-技能两级结构：
 * - 仓库（SkillRepository）：从 GitHub 下载的技能库，只是容器
 * - 技能（Skill）：仓库中的单个 SKILL.md 文件，可以独立开关
 *
 * 以 JSON 数组形式保存在 SharedPreferences 中，支持增删改查和启用/禁用。
 * 技能列表随设置同步广播发送给微信进程，分析时读取已启用的技能。
 */
object SkillStore {
    private const val KEY_SKILLS = "skills_json"
    private const val KEY_REPOSITORIES = "skill_repositories_json"
    private const val KEY_SKILL_ENABLED = "skill_feature_enabled"

    /** 技能功能总开关：关闭时即使有已启用的技能也不生效。 */
    fun isFeatureEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SKILL_ENABLED, false)

    fun setFeatureEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SKILL_ENABLED, enabled).apply()
    }

    // ==================== 仓库管理 ====================

    fun loadRepositories(prefs: SharedPreferences): List<SkillRepository> {
        val raw = prefs.getString(KEY_REPOSITORIES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SkillRepository(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    url = obj.optString("url", ""),
                    dirName = obj.optString("dirName", ""),
                    version = obj.optString("version", ""),
                    skillCount = obj.optInt("skillCount", 0),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveRepositories(prefs: SharedPreferences, repos: List<SkillRepository>) {
        val arr = JSONArray()
        repos.forEach { r ->
            arr.put(JSONObject()
                .put("id", r.id)
                .put("name", r.name)
                .put("url", r.url)
                .put("dirName", r.dirName)
                .put("version", r.version)
                .put("skillCount", r.skillCount))
        }
        prefs.edit().putString(KEY_REPOSITORIES, arr.toString()).apply()
    }

    fun addRepository(prefs: SharedPreferences, repo: SkillRepository): List<SkillRepository> {
        val all = loadRepositories(prefs).toMutableList()
        all.add(repo)
        saveRepositories(prefs, all)
        return all
    }

    fun deleteRepository(context: Context, prefs: SharedPreferences, repoId: String): List<SkillRepository> {
        val repo = loadRepositories(prefs).firstOrNull { it.id == repoId }
        // 删除仓库下的所有技能
        val skills = loadAll(prefs).filter { it.repositoryId != repoId }
        saveAll(prefs, skills)
        // 删除本地文件目录
        if (repo?.dirName?.isNotBlank() == true) {
            SkillDownloader.deleteSkillDir(context, repo.dirName)
        }
        val repos = loadRepositories(prefs).filter { it.id != repoId }
        saveRepositories(prefs, repos)
        return repos
    }

    fun skillsOfRepository(prefs: SharedPreferences, repositoryId: String): List<Skill> =
        loadAll(prefs).filter { it.repositoryId == repositoryId }

    // ==================== 技能管理 ====================

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
                    repositoryId = obj.optString("repositoryId", ""),
                    filePath = obj.optString("filePath", ""),
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
                .put("repositoryId", s.repositoryId)
                .put("filePath", s.filePath))
        }
        prefs.edit().putString(KEY_SKILLS, arr.toString()).apply()
    }

    fun add(prefs: SharedPreferences, skill: Skill): List<Skill> {
        val all = loadAll(prefs).toMutableList()
        all.add(skill)
        saveAll(prefs, all)
        return all
    }

    /** 批量添加技能（下载仓库后使用） */
    fun addAll(prefs: SharedPreferences, skills: List<Skill>): List<Skill> {
        val all = loadAll(prefs).toMutableList()
        all.addAll(skills)
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
     *
     * 对于技能库技能（library），智能提取核心原则作为提示（避免完整 SKILL.md
     * 中的输出格式指令与 JevProtocol 的 JSON 格式冲突）；
     * 对于自定义技能（custom），使用用户输入的提示词。
     *
     * 总长度限制在 1200 字以内，避免稀释原始指令。
     */
    fun activePrompt(context: Context): String? {
        val prefs = context.getSharedPreferences(ModulePrefs.FILE_NAME, Context.MODE_PRIVATE)
        if (!isFeatureEnabled(prefs)) return null
        val active = enabledSkills(prefs)
        if (active.isEmpty()) return null

        return buildString {
            append("【已启用的回复风格参考，仅用于调整建议的语气和角度，不改变输出格式】\n\n")
            active.forEachIndexed { index, s ->
                append("=== 风格参考 ${index + 1}：${s.name} ===\n")
                val effective = if (s.isLibrarySkill) {
                    extractCorePrinciples(s.prompt.ifBlank { s.description })
                } else {
                    s.prompt.ifBlank { s.description }
                }
                if (effective.isNotBlank()) {
                    append(effective.take(600))
                }
                append("\n\n")
            }
            append("以上仅作为回复风格和角度的参考，必须严格按照题目要求的 JSON 格式输出，不能输出自由文本分析。")
        }
    }

    /**
     * 获取指定技能的提示文本（用于按人配置技能时）。
     */
    fun skillPrompt(skill: Skill): String {
        val effective = if (skill.isLibrarySkill) {
            extractCorePrinciples(skill.prompt.ifBlank { skill.description })
        } else {
            skill.prompt.ifBlank { skill.description }
        }
        return effective.take(800)
    }

    /**
     * 从完整的 SKILL.md 中提取核心原则。
     * 优先提取"核心原则"章节，移除输出格式相关的章节（如"每次分析"、"首次使用"等），
     * 避免 skill 的输出格式指令与 JevProtocol 的 JSON 格式冲突。
     */
    private fun extractCorePrinciples(fullText: String): String {
        if (fullText.isBlank()) return ""

        // 尝试提取"核心原则"章节
        val coreSection = extractSection(fullText, "核心原则")
        if (coreSection.isNotBlank()) {
            return cleanMarkdown(coreSection)
        }

        // 如果没有核心原则章节，尝试提取前两个非空章节
        val sections = extractAllSections(fullText).take(2)
        if (sections.isNotEmpty()) {
            return cleanMarkdown(sections.joinToString("\n"))
        }

        // 最后回退：取前 600 字
        return cleanMarkdown(fullText.take(600))
    }

    /**
     * 提取指定标题的章节内容（到下一个同级或更高级标题为止）。
     */
    private fun extractSection(text: String, title: String): String {
        val lines = text.lines()
        var inSection = false
        val result = mutableListOf<String>()
        var sectionLevel = 0

        for (line in lines) {
            val headerMatch = Regex("^(#{1,6})\\s+(.*)$").find(line)
            if (headerMatch != null) {
                val level = headerMatch.groupValues[1].length
                val headerTitle = headerMatch.groupValues[2].trim()
                if (inSection) {
                    if (level <= sectionLevel) break
                    result.add(line)
                } else if (headerTitle.contains(title, ignoreCase = true)) {
                    inSection = true
                    sectionLevel = level
                    result.add(line)
                }
            } else if (inSection) {
                result.add(line)
            }
        }
        return result.joinToString("\n").trim()
    }

    /**
     * 提取所有章节标题和内容。
     */
    private fun extractAllSections(text: String): List<String> {
        val lines = text.lines()
        val sections = mutableListOf<String>()
        var current = mutableListOf<String>()

        for (line in lines) {
            if (Regex("^#{1,6}\\s+").containsMatchIn(line)) {
                if (current.isNotEmpty()) {
                    sections.add(current.joinToString("\n").trim())
                    current = mutableListOf()
                }
            }
            current.add(line)
        }
        if (current.isNotEmpty()) sections.add(current.joinToString("\n").trim())
        return sections.filter { it.isNotBlank() }
    }

    /**
     * 清理 markdown 格式，移除表格、代码块、链接等，只保留纯文本原则。
     */
    private fun cleanMarkdown(text: String): String {
        return text
            // 移除代码块
            .replace(Regex("```[\\s\\S]*?```"), "")
            // 移除表格行
            .replace(Regex("^\\|.*\\|$", RegexOption.MULTILINE), "")
            // 移除 markdown 链接，保留文字
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            // 移除标题标记，保留文字
            .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
            // 移除粗体/斜体标记
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\*([^*]+)\\*"), "$1")
            // 移除列表标记
            .replace(Regex("^[-*]\\s+", RegexOption.MULTILINE), "· ")
            // 合并多余空行
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
