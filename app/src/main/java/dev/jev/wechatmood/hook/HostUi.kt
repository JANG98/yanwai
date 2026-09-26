package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import dev.jev.wechatmood.analysis.SignalAnalyzer
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.Diagnostics
import dev.jev.wechatmood.core.MoodLog

/** Add controls to the existing header; never replace the chat's content or action bar. */
class HostUi(private val activity: Activity) {
    private var control: Switch? = null
    private var syncing = false
    private var status = ""
    private var messages = emptyList<AnalysisInput>()
    private var currentTalker = ""
    private var dialog: AlertDialog? = null
    private var title: TextView? = null
    private var oldTitleWidth = Int.MAX_VALUE
    private var oldEllipsize: TextUtils.TruncateAt? = null
    private val content = activity.findViewById<ViewGroup>(android.R.id.content)
    private var settingsWrapper: LinearLayout? = null
    private var settingsHost: View? = null
    private var settingsParams: ViewGroup.LayoutParams? = null

    fun showStatus(value: String, current: List<AnalysisInput>, talker: String = "") {
        restoreSettings()
        status = value
        messages = current
        currentTalker = talker
        ensureControl()
        syncing = true
        control?.apply {
            visibility = View.VISIBLE
            // 绘制开关状态由当前聊天的会话级开关决定，而非全局开关
            isChecked = if (talker.isNotBlank()) ChatSwitchStore.isEnabled(activity, talker) else ModulePrefs.showBadge
            contentDescription = "绘制分析结果；$value；长按打开分析与设置"
        }
        syncing = false
    }

    private fun ensureControl() {
        val decor = activity.window.decorView
        val all = descendants(decor)
        val chat = all.firstOrNull { it.javaClass.name.endsWith(".ChattingUILayout") && it.isShown }
        val scope = if (chat != null) descendants(chat) else all
        val header = scope.firstOrNull {
            it.isShown && it.javaClass.name == "androidx.appcompat.widget.ActionBarContainer"
        } as? FrameLayout
        if (header != null && control?.parent === header && control?.isShown == true) return
        removeControl()
        if (header == null) return
        val toggle = Switch(activity).apply {
            text = "绘制"
            textSize = 12f
            switchPadding = dp(3)
            minHeight = dp(48)
            setPadding(dp(4), 0, dp(4), 0)
            setOnCheckedChangeListener { _, checked ->
                if (!syncing) {
                    // 绘制开关只控制当前聊天的会话级分析开关
                    if (currentTalker.isNotBlank()) {
                        ChatSwitchStore.setEnabled(activity, currentTalker, checked)
                        if (!checked) BubbleDecorator.clearAll()
                        MessageSniffer.refresh()
                    } else {
                        // 没有当前聊天时（如设置页面），回退到全局开关
                        if (!ModulePrefs.setSwitch(ModulePrefs.KEY_SHOW_BADGE, checked)) {
                            syncing = true
                            isChecked = ModulePrefs.showBadge
                            syncing = false
                            Diagnostics.showFailure(activity, "开关未确认保存", ModulePrefs.lastBridgeError ?: "BRIDGE_SAVE_FAILED")
                        }
                        if (!ModulePrefs.showBadge) BubbleDecorator.clearAll()
                        MessageSniffer.refresh()
                    }
                }
            }
            setOnLongClickListener { showActions(); true }
        }
        control = toggle
        val headerPos = IntArray(2).also { header.getLocationOnScreen(it) }
        val menuLeft = descendants(header).filter { it.isShown && it.isClickable && it.width in 1..(header.width / 3) }
            .map { view -> IntArray(2).also { view.getLocationOnScreen(it) }[0] - headerPos[0] }
            .filter { it > header.width * 0.7 }.minOrNull()
        val menuSpace = menuLeft?.let { header.width - it + dp(4) } ?: dp(60)
        header.addView(toggle, FrameLayout.LayoutParams(-2, dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = menuSpace
        })
        toggle.post {
            if (control !== toggle || !toggle.isAttachedToWindow) return@post
            title = descendants(header).filterIsInstance<TextView>().firstOrNull {
                it !== toggle && it.isShown && it.text.isNotBlank() && it.width > dp(90)
            }
            title?.let {
                oldTitleWidth = it.maxWidth
                oldEllipsize = it.ellipsize
                it.maxWidth = (header.width - 2 * (toggle.width + menuSpace)).coerceAtLeast(dp(60))
                it.ellipsize = TextUtils.TruncateAt.END
            }
        }
    }

    private fun showActions() {
        if (dialog?.isShowing == true) return
        val hasTalker = currentTalker.isNotBlank()
        val relation = if (hasTalker) ChatProfileStore.getRelationship(activity, currentTalker) else ""
        val currentSkillId = if (hasTalker) ChatSkillStore.getSkillId(activity, currentTalker) else null
        val items = if (hasTalker) {
            arrayOf("分析本屏", "设置关系描述", "加载 skill", "助手设置", "导出运行日志")
        } else {
            arrayOf("分析本屏", "助手设置", "导出运行日志")
        }
        dialog = AlertDialog.Builder(activity).setTitle("言外 · $status")
            .setItems(items) { _, which ->
                val offset = if (hasTalker) 0 else 2
                when (which + offset) {
                    0 -> {
                        // 分析本屏：开启当前聊天的会话级开关 + 全局绘制开关
                        if (currentTalker.isNotBlank()) {
                            ChatSwitchStore.setEnabled(activity, currentTalker, true)
                        }
                        if (ModulePrefs.setSwitch(ModulePrefs.KEY_ENABLED, true) &&
                            ModulePrefs.setSwitch(ModulePrefs.KEY_SHOW_BADGE, true)) {
                            messages.forEach { SignalAnalyzer.retryFailure(it.key) }
                            MessageSniffer.refresh()
                        } else Diagnostics.showFailure(activity, "分析开关未确认保存", ModulePrefs.lastBridgeError ?: "BRIDGE_SAVE_FAILED")
                    }
                    1 -> if (hasTalker) showRelationshipDialog(relation)
                    2 -> if (hasTalker) showSkillDialog(currentSkillId)
                    3 -> openSettings()
                    4 -> Diagnostics.show(activity)
                }
            }
            .setNegativeButton("关闭", null).create().also { it.show() }
    }

    /**
     * 弹出技能选择对话框，让用户为当前聊天选择一个技能。
     * 通过 ContentProvider 跨进程从言外进程获取已开启的技能列表。
     */
    private fun showSkillDialog(currentSkillId: String?) {
        // 通过 ContentProvider 跨进程获取已开启的技能列表
        data class SkillInfo(val id: String, val name: String)
        val skills = mutableListOf<SkillInfo>()
        var featureEnabled = false

        runCatching {
            val result = activity.contentResolver.call(
                dev.jev.wechatmood.core.SettingsProvider.URI,
                "get_skills", null, null
            )
            if (result != null) {
                featureEnabled = result.getBoolean("feature_enabled")
                val json = result.getString("skills")
                if (!json.isNullOrBlank()) {
                    val arr = org.json.JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        skills.add(SkillInfo(obj.getString("id"), obj.getString("name")))
                    }
                }
            }
        }.onFailure {
            dev.jev.wechatmood.core.MoodLog.e("GET_SKILLS_FAILED", it)
        }

        if (!featureEnabled) {
            android.widget.Toast.makeText(activity,
                "回复技能功能未开启，请先在言外设置中开启「回复技能」总开关",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }

        if (skills.isEmpty()) {
            android.widget.Toast.makeText(activity,
                "还没有已开启的技能，请先在言外设置中开启技能",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }

        val skillNames = skills.map { it.name }.toTypedArray()
        val currentIndex = skills.indexOfFirst { it.id == currentSkillId }

        AlertDialog.Builder(activity)
            .setTitle("为当前聊天选择技能")
            .setSingleChoiceItems(skillNames, currentIndex) { dialog, which ->
                val selected = skills[which]
                ChatSkillStore.setSkillId(activity, currentTalker, selected.id)
                android.widget.Toast.makeText(activity,
                    "已为当前聊天加载技能：${selected.name}",
                    android.widget.Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNeutralButton("清除技能") { _, _ ->
                ChatSkillStore.clearSkill(activity, currentTalker)
                android.widget.Toast.makeText(activity, "已清除当前聊天的技能", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 弹出关系描述输入框，让用户设置当前聊天对象的关系描述。
     */
    private fun showRelationshipDialog(current: String) {
        val input = android.widget.EditText(activity).apply {
            hint = "例如：暧昧对象、刚认识的朋友、女朋友、同事、客户..."
            setText(current)
            setSelection(text.length)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val hint = TextView(activity).apply {
            text = "设置后，该聊天的情绪分析和回复建议会优先参考这段关系描述。"
            setTextColor(androidx.core.content.ContextCompat.getColor(activity, dev.jev.wechatmood.R.color.text_secondary))
            textSize = 12f
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
            addView(input)
            addView(hint)
        }
        AlertDialog.Builder(activity)
            .setTitle("设置关系描述")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().trim()
                ChatProfileStore.setRelationship(activity, currentTalker, value)
                MessageSniffer.refresh()
                android.widget.Toast.makeText(activity,
                    if (value.isNotBlank()) "关系描述已保存：$value" else "关系描述已清除",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showSettings() {
        removeControl()
        if (settingsWrapper != null) return
        val host = content.getChildAt(0) ?: return
        settingsHost = host
        settingsParams = host.layoutParams
        val wrapper = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        settingsWrapper = wrapper
        content.removeView(host)
        content.addView(wrapper, ViewGroup.LayoutParams(-1, -1))
        wrapper.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        wrapper.addView(TextView(activity).apply {
            text = "言外  ›\n已加载 · 长按导出运行日志"
            textSize = 14f
            minHeight = dp(52)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { openSettings() }
            setOnLongClickListener { Diagnostics.show(activity); true }
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                val bottom = if (android.os.Build.VERSION.SDK_INT >= 30)
                    insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                else minOf(insets.systemWindowInsetBottom, insets.stableInsetBottom)
                view.setPadding(dp(16), dp(10), dp(16), dp(10) + bottom)
                insets
            }
            requestApplyInsets()
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun openSettings() {
        runCatching {
            activity.startActivity(Intent().setComponent(ComponentName("dev.jev.wechatmood", "dev.jev.wechatmood.MainActivity")))
            MoodLog.i("SETTINGS_ACTIVITY_OPEN 请求已发送")
        }.onFailure {
            MoodLog.e("SETTINGS_ACTIVITY_OPEN_FAILED", it)
            Diagnostics.showFailure(activity, "无法从微信打开言外", "SETTINGS_ACTIVITY_OPEN_FAILED：${it.javaClass.simpleName} ${it.message}")
        }
    }
    fun hide() { removeControl(); restoreSettings(); dialog?.dismiss(); messages = emptyList() }
    fun dispose() = hide()
    private fun removeControl() {
        control?.let { (it.parent as? ViewGroup)?.removeView(it) }
        control = null
        title?.let { it.maxWidth = oldTitleWidth; it.ellipsize = oldEllipsize }
        title = null
    }
    private fun restoreSettings() {
        val wrapper = settingsWrapper ?: return
        settingsHost?.let { host ->
            wrapper.removeView(host)
            content.removeView(wrapper)
            content.addView(host, 0, settingsParams)
        }
        settingsWrapper = null
        settingsHost = null
    }
    private fun descendants(root: View): List<View> {
        val result = mutableListOf<View>()
        fun walk(view: View, depth: Int) {
            if (depth > 40 || result.size > 4000 || view.visibility != View.VISIBLE) return
            result += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
        }
        walk(root, 0)
        return result
    }
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
}
