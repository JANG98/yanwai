package dev.jev.wechatmood.core

import java.io.File

/**
 * SKILL.md 解析器。
 *
 * 解析标准 skill 格式的 SKILL.md 文件：
 * - frontmatter（--- 包裹的 YAML）中的 name 和 description
 * - 正文内容作为技能提示词（prompt）
 *
 * 参考格式：
 * ```
 * ---
 * name: skill-name
 * description: 技能描述
 * ---
 *
 * # 技能标题
 * 正文提示词内容...
 * ```
 */
object SkillParser {

    /**
     * 解析 SKILL.md 文件内容。
     *
     * @param content SKILL.md 完整文本
     * @param fallbackName 当 frontmatter 中没有 name 时使用的备用名称
     * @return Triple(name, description, prompt)
     */
    fun parse(content: String, fallbackName: String = "未命名技能"): Triple<String, String, String> {
        val trimmed = content.trim()

        // 尝试解析 frontmatter
        if (trimmed.startsWith("---")) {
            val endIndex = trimmed.indexOf("---", 3)
            if (endIndex > 0) {
                val frontmatter = trimmed.substring(3, endIndex).trim()
                val body = trimmed.substring(endIndex + 3).trim()

                val name = extractYamlField(frontmatter, "name") ?: fallbackName
                val description = extractYamlField(frontmatter, "description") ?: ""

                return Triple(name, description, body)
            }
        }

        // 没有 frontmatter，用第一行作为名称，其余作为 prompt
        val lines = trimmed.lines()
        val name = lines.firstOrNull()?.removePrefix("#")?.trim() ?: fallbackName
        val description = ""
        val prompt = trimmed

        return Triple(name, description, prompt)
    }

    /**
     * 从单个 SKILL.md 文件解析。
     *
     * @param file SKILL.md 文件
     * @return 解析结果 Triple(name, description, prompt)
     */
    fun parseFromFile(file: File): Triple<String, String, String> {
        val fallbackName = file.parentFile?.name ?: "未命名技能"
        return parse(file.readText(), fallbackName)
    }

    /**
     * 从目录中查找并解析 SKILL.md。
     * 支持根目录和子目录中的 SKILL.md。
     *
     * @param dir skill 仓库根目录
     * @return 解析结果，如果找不到 SKILL.md 返回 null
     */
    fun parseFromDir(dir: File): Triple<String, String, String>? {
        // 先在根目录找
        val rootSkillMd = File(dir, "SKILL.md")
        if (rootSkillMd.exists()) {
            val fallbackName = dir.name
            return parse(rootSkillMd.readText(), fallbackName)
        }

        // 递归查找（最多两层）
        dir.listFiles()?.forEach { subDir ->
            if (subDir.isDirectory) {
                val nestedSkillMd = File(subDir, "SKILL.md")
                if (nestedSkillMd.exists()) {
                    val fallbackName = subDir.name
                    return parse(nestedSkillMd.readText(), fallbackName)
                }
            }
        }

        return null
    }

    /**
     * 从 YAML frontmatter 中提取字段值。
     * 支持简单的 key: value 格式，值可能被单引号或双引号包裹。
     */
    private fun extractYamlField(frontmatter: String, key: String): String? {
        // 匹配 key: value（支持引号包裹的值）
        val regex = Regex("""(?m)^\s*${Regex.escape(key)}\s*:\s*(.+?)\s*$""")
        val match = regex.find(frontmatter) ?: return null
        var value = match.groupValues[1].trim()

        // 去除引号
        if ((value.startsWith("\"") && value.endsWith("\"")) ||
            (value.startsWith("'") && value.endsWith("'"))
        ) {
            value = value.substring(1, value.length - 1)
        }

        return value.ifBlank { null }
    }

    /**
     * 截断 prompt 到最大长度，避免 token 超限。
     */
    fun truncatePrompt(prompt: String, maxLength: Int = 8000): String {
        if (prompt.length <= maxLength) return prompt
        return prompt.substring(0, maxLength) + "\n\n...（内容已截断，完整内容请查看技能库文件）"
    }
}
