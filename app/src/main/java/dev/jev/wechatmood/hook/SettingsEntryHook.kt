package dev.jev.wechatmood.hook

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import dev.jev.wechatmood.core.MoodLog

/**
 * 微信设置页面入口注入。
 *
 * 参考 WeKit 的实现方式：
 * 1. 旧版 SettingsUI：hook initView()，使用微信自己的 IconPreference 在设置列表中插入「yanwai」
 * 2. 新版 MainSettingsUI：hook onResume，在页面底部添加入口条
 *
 * 点击入口跳转到模块设置页面。
 */
object SettingsEntryHook {

    private const val TAG = "SettingsEntryHook"
    private const val PREF_KEY = "yanwai_settings_entry"
    private const val ENTRY_TITLE = "yanwai"
    private const val SETTINGS_UI_CLASS = "com.tencent.mm.plugin.setting.ui.setting.SettingsUI"
    private const val MAIN_SETTINGS_UI_CLASS = "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"
    private const val MM_PREFERENCE = "com.tencent.mm.ui.base.preference.Preference"
    private const val MM_ICON_PREFERENCE = "com.tencent.mm.ui.base.preference.IconPreference"

    private var installed = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true

        // 旧版设置页：hook initView()，注入 Preference
        runCatching {
            val settingsUiClass = XposedHelpers.findClass(SETTINGS_UI_CLASS, classLoader)
            XposedBridge.hookAllMethods(settingsUiClass, "initView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        injectIntoLegacySettings(param.thisObject as Activity)
                    }.onFailure {
                        MoodLog.e("SETTINGS_INJECT_LEGACY_FAILED", it)
                    }
                }
            })
            MoodLog.i("旧版设置页入口 Hook 已安装")
        }.onFailure {
            MoodLog.w("旧版设置页类未找到: ${it.message}")
        }

        // 旧版设置页：hook onPreferenceTreeClick，处理点击
        runCatching {
            val settingsUiClass = XposedHelpers.findClass(SETTINGS_UI_CLASS, classLoader)
            XposedBridge.hookAllMethods(settingsUiClass, "onPreferenceTreeClick", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val preference = param.args.getOrNull(1) ?: return
                        val key = XposedHelpers.callMethod(preference, "getKey") as? String
                        if (key == PREF_KEY) {
                            openSettings(param.thisObject as Activity)
                            param.result = true
                        }
                    }.onFailure {
                        MoodLog.e("SETTINGS_CLICK_FAILED", it)
                    }
                }
            })
        }.onFailure {
            MoodLog.w("旧版设置页点击 Hook 失败: ${it.message}")
        }

        // 新版设置页：hook onResume，在底部添加入口条
        runCatching {
            val mainSettingsClass = XposedHelpers.findClass(MAIN_SETTINGS_UI_CLASS, classLoader)
            XposedBridge.hookAllMethods(mainSettingsClass, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        injectBottomBar(param.thisObject as Activity)
                    }.onFailure {
                        MoodLog.e("SETTINGS_INJECT_MODERN_FAILED", it)
                    }
                }
            })
            MoodLog.i("新版设置页入口 Hook 已安装")
        }.onFailure {
            MoodLog.w("新版设置页类未找到: ${it.message}")
        }

        // 额外：hook CommonSettingsUI（某些微信版本的设置页）
        runCatching {
            val commonSettingsClass = XposedHelpers.findClass(
                "com.tencent.mm.plugin.setting.ui.setting_new.CommonSettingsUI", classLoader
            )
            XposedBridge.hookAllMethods(commonSettingsClass, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        injectBottomBar(param.thisObject as Activity)
                    }.onFailure { MoodLog.e("SETTINGS_INJECT_COMMON_FAILED", it) }
                }
            })
            MoodLog.i("CommonSettingsUI 入口 Hook 已安装")
        }.onFailure {
            MoodLog.i("CommonSettingsUI 类未找到（可选）")
        }
    }

    /**
     * 旧版设置页：在 PreferenceScreen 中添加一个 Preference 项。
     * 使用微信自己的 IconPreference 类（参考 WeKit）。
     */
    private fun injectIntoLegacySettings(activity: Activity) {
        val prefScreen = runCatching {
            XposedHelpers.callMethod(activity, "getPreferenceScreen")
        }.getOrNull() ?: run {
            MoodLog.w("无法获取 PreferenceScreen，使用底部条 fallback")
            injectBottomBar(activity)
            return
        }

        // 检查是否已经添加过
        val existing = runCatching {
            XposedHelpers.callMethod(prefScreen, "findPreference", PREF_KEY)
        }.getOrNull()
        if (existing != null) {
            MoodLog.i("设置入口已存在，跳过")
            return
        }

        // 优先使用微信的 IconPreference，回退到微信的 Preference，最后回退到 AndroidX Preference
        val preference = runCatching {
            val iconPrefClass = XposedHelpers.findClass(MM_ICON_PREFERENCE, activity.classLoader)
            XposedHelpers.newInstance(iconPrefClass, activity)
        }.getOrNull() ?: runCatching {
            val mmPrefClass = XposedHelpers.findClass(MM_PREFERENCE, activity.classLoader)
            XposedHelpers.newInstance(mmPrefClass, activity)
        }.getOrNull() ?: runCatching {
            val androidxPrefClass = XposedHelpers.findClass("androidx.preference.Preference", activity.classLoader)
            XposedHelpers.newInstance(androidxPrefClass, activity)
        }.getOrNull() ?: run {
            MoodLog.w("无法创建任何 Preference 实例，使用底部条 fallback")
            injectBottomBar(activity)
            return
        }

        // 设置 key 和 title
        XposedHelpers.callMethod(preference, "setKey", PREF_KEY)
        XposedHelpers.callMethod(preference, "setTitle", ENTRY_TITLE)
        runCatching {
            XposedHelpers.callMethod(preference, "setSummary", "言外 · 微信聊天情绪分析")
        }

        // 添加到 PreferenceScreen（微信的 addPreference 可能有两个参数）
        var added = false
        runCatching {
            XposedHelpers.callMethod(prefScreen, "addPreference", preference, 0)
            added = true
        }.onFailure {
            runCatching {
                XposedHelpers.callMethod(prefScreen, "addPreference", preference)
                added = true
            }.onFailure { e ->
                MoodLog.w("addPreference 失败: ${e.message}")
            }
        }

        if (added) {
            MoodLog.i("设置入口已注入（Preference 方式）: $ENTRY_TITLE")
        } else {
            MoodLog.w("Preference 注入失败，使用底部条 fallback")
            injectBottomBar(activity)
        }
    }

    /**
     * 底部条 fallback：在设置页面底部添加一个可点击的入口条。
     * 明确设置背景色和文字颜色，确保在深色/浅色主题下都可见。
     */
    private fun injectBottomBar(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        // 检查是否已经添加
        if (content.findViewWithTag<View>("yanwai_settings_entry") != null) return

        val entryBar = TextView(activity).apply {
            tag = "yanwai_settings_entry"
            text = "$ENTRY_TITLE  ›\n言外 · 微信聊天情绪分析"
            textSize = 14f
            setTextColor(android.graphics.Color.BLACK)
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14))
            setOnClickListener { openSettings(activity) }
            setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
            // 添加上边框，和设置页面其他项区分
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#F5F5F5"))
                setStroke(1, android.graphics.Color.parseColor("#E0E0E0"))
            })
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        // 包装在 LinearLayout 中，添加到底部
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addView(entryBar, LinearLayout.LayoutParams(-1, -2))
        }

        val host = content.getChildAt(0) ?: return
        content.removeView(host)
        content.addView(wrapper, ViewGroup.LayoutParams(-1, -1))
        wrapper.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        MoodLog.i("设置入口已注入（底部条方式）: $ENTRY_TITLE")
    }

    private fun openSettings(activity: Activity) {
        runCatching {
            activity.startActivity(
                Intent().setComponent(
                    ComponentName("dev.jev.wechatmood", "dev.jev.wechatmood.MainActivity")
                )
            )
            MoodLog.i("设置页面已打开")
        }.onFailure {
            MoodLog.e("OPEN_SETTINGS_FAILED", it)
        }
    }

    private fun dp(context: Context, n: Int): Int =
        (n * context.resources.displayMetrics.density).toInt()
}
