package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.jev.wechatmood.analysis.JevHttpClient
import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.ContextMessage
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 回复建议分析助手。
 * 长按消息后，根据消息内容、关系描述和已加载的 skill，调用 AI 生成 3 条回复建议。
 */
object ReplySuggestionHelper {

    private val client = JevHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * 分析单条消息，生成 3 条回复建议。
     *
     * @param activity 当前 Activity
     * @param messageText 对方消息文本
     * @param contextMessages 聊天上下文（最近几条）
     * @param talker 聊天对象
     * @param relationship 关系描述
     * @param skillPrompt 技能提示词（可为空）
     */
    fun analyzeAndShow(
        activity: Activity,
        messageText: String,
        contextMessages: List<ContextMessage> = emptyList(),
        talker: String = "",
        relationship: String = "",
        skillPrompt: String = ""
    ) {
        if (messageText.isBlank()) {
            Toast.makeText(activity, "消息内容为空，无法分析", Toast.LENGTH_SHORT).show()
            return
        }

        // 显示加载对话框
        val loadingDialog = AlertDialog.Builder(activity)
            .setTitle("正在分析回复建议…")
            .setMessage("消息：${messageText.take(50)}${if (messageText.length > 50) "…" else ""}")
            .setCancelable(false)
            .create()
        loadingDialog.show()

        scope.launch {
            try {
                val settings = ModulePrefs.apiSettings()
                if (!settings.isConfigured) {
                    withContext(Dispatchers.Main) {
                        loadingDialog.dismiss()
                        Toast.makeText(activity, "请先在言外设置中配置 API Key", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // 调用 AI 生成 3 条回复建议
                val replies = client.generateReplies(
                    message = messageText,
                    context = contextMessages,
                    relationship = relationship,
                    skillPrompt = skillPrompt,
                    settings = settings
                )

                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    if (replies.isEmpty()) {
                        Toast.makeText(activity, "生成回复建议失败，请稍后重试", Toast.LENGTH_LONG).show()
                    } else {
                        showRepliesDialog(activity, messageText, replies, talker)
                    }
                }
            } catch (e: Exception) {
                MoodLog.e("REPLY_SUGGESTION_FAILED", e)
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    Toast.makeText(activity, "分析失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 显示回复建议对话框。
     * 每条建议可点击复制到剪贴板。
     */
    private fun showRepliesDialog(
        activity: Activity,
        originalMessage: String,
        replies: List<String>,
        talker: String
    ) {
        // 构建自定义视图
        val scrollView = ScrollView(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 16))
        }

        // 原始消息
        container.addView(TextView(activity).apply {
            text = "对方消息：$originalMessage"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, dp(activity, 12))
        })

        // 3 条回复建议
        replies.forEachIndexed { index, reply ->
            val replyView = TextView(activity).apply {
                text = "${index + 1}. $reply"
                textSize = 15f
                setTextColor(0xFF333333.toInt())
                setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    copyToClipboard(activity, reply)
                    Toast.makeText(activity, "已复制回复建议 ${index + 1}", Toast.LENGTH_SHORT).show()
                }
            }
            container.addView(replyView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(activity, 8)
            })
        }

        // 提示
        container.addView(TextView(activity).apply {
            text = "点击任意一条回复建议即可复制到剪贴板"
            textSize = 11f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, dp(activity, 12), 0, 0)
        })

        scrollView.addView(container)

        AlertDialog.Builder(activity)
            .setTitle("回复建议${if (talker.isNotBlank()) " · $talker" else ""}")
            .setView(scrollView)
            .setNegativeButton("关闭", null)
            .create()
            .show()
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("回复建议", text))
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
