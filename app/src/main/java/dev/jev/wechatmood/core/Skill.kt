package dev.jev.wechatmood.core

/**
 * 技能（Skill）定义。
 *
 * 开启后，在输出回复建议时会将技能提示注入模型请求，
 * 让模型在选择下一步动作时参考技能设定的风格或规则。
 *
 * 注意：技能只影响"建议"环节，不改变情绪判断和事件解读；
 * 也不会自动发送消息，仅作为模型选择候选动作时的参考约束。
 *
 * 支持两种来源：
 * - [SOURCE_CUSTOM]：用户在应用内手动创建的简单技能（名称+描述+提示词）
 * - [SOURCE_LIBRARY]：从 GitHub 下载的完整 skill 仓库（含 SKILL.md、references 等）
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val prompt: String,
    val enabled: Boolean,
    /** 来源：custom（用户自定义）或 library（从 GitHub 下载的技能库） */
    val source: String = SOURCE_CUSTOM,
    /** 版本号（library 来源时从仓库获取，custom 来源为空） */
    val version: String = "",
    /** 本地存储目录名（library 来源时为 files/skills/ 下的子目录名，custom 来源为空） */
    val dirName: String = "",
    /** GitHub 仓库地址（library 来源时记录，用于更新；custom 来源为空） */
    val repoUrl: String = "",
) {
    companion object {
        const val SOURCE_CUSTOM = "custom"
        const val SOURCE_LIBRARY = "library"

        fun newId(): String = "skill_${System.currentTimeMillis()}_${(0..9999).random()}"
    }

    /** 是否为从 GitHub 下载的技能库技能 */
    val isLibrarySkill: Boolean get() = source == SOURCE_LIBRARY
}
