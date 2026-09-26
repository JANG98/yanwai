package dev.jev.wechatmood.core

/**
 * 技能仓库数据模型。
 *
 * 一个仓库可以包含多个技能（SKILL.md 文件）。
 * 仓库只是容器，真正起作用的是里面的单个技能。
 */
data class SkillRepository(
    val id: String,
    val name: String,
    val url: String,
    val dirName: String,
    val version: String,
    val skillCount: Int = 0,
)

/**
 * 单个技能数据模型。
 *
 * 每个技能对应一个 SKILL.md 文件，可以独立开关。
 * 技能可以属于某个仓库（source=library），也可以是用户自定义（source=custom）。
 *
 * 注意：技能只影响"建议"环节，不改变情绪判断和事件解读；
 * 也不会自动发送消息，仅作为模型选择候选动作时的参考约束。
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
    /** 所属仓库 ID（library 技能，custom 为空） */
    val repositoryId: String = "",
    /** 在仓库中的相对路径（library 技能，custom 为空） */
    val filePath: String = "",
) {
    companion object {
        const val SOURCE_CUSTOM = "custom"
        const val SOURCE_LIBRARY = "library"

        fun newId(): String = "skill_${System.currentTimeMillis()}_${(0..9999).random()}"
    }

    /** 是否为从 GitHub 下载的技能库技能 */
    val isLibrarySkill: Boolean get() = source == SOURCE_LIBRARY
}
