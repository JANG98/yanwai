package dev.jev.wechatmood.core

/**
 * 技能（Skill）定义。
 *
 * 开启后，在输出回复建议时会将技能提示注入模型请求，
 * 让模型在选择下一步动作时参考技能设定的风格或规则。
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
) {
    companion object {
        fun newId(): String = "skill_${System.currentTimeMillis()}_${(0..9999).random()}"
    }
}
