package dev.jev.wechatmood.hook

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import dev.jev.wechatmood.core.MoodLog

/**
 * 微信主界面悬浮按钮入口。
 *
 * 参考 WeKit 的 AddMainScreenFab 实现方式：在微信主界面（LauncherUI）
 * 右下角添加一个悬浮按钮，点击跳转到言外设置页面。
 *
 * 这比设置页面底部条更可靠，不会因为页面结构变化而消失或透明。
 */
object MainScreenEntry {
    private const val TAG = "MainScreenEntry"
    private const val LAUNCHER_UI_CLASS = "com.tencent.mm.ui.LauncherUI"
    private const val FAB_TAG = "yanwai_main_fab"
    private const val FAB_SIZE_DP = 56
    private const val FAB_MARGIN_DP = 16
    private const val FAB_BOTTOM_MARGIN_DP = 80  // 避开微信底部标签栏

    private var installed = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true

        runCatching {
            val launcherClass = XposedHelpers.findClass(LAUNCHER_UI_CLASS, classLoader)
            // hook onCreate，在页面创建后添加悬浮按钮
            XposedBridge.hookAllMethods(launcherClass, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val activity = param.thisObject as Activity
                        addFab(activity)
                    }.onFailure { MoodLog.e("MAIN_FAB_ADD_FAILED", it) }
                }
            })
            // hook onResume，确保按钮存在（某些情况下可能被移除）
            XposedBridge.hookAllMethods(launcherClass, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val activity = param.thisObject as Activity
                        ensureFab(activity)
                    }.onFailure { MoodLog.e("MAIN_FAB_ENSURE_FAILED", it) }
                }
            })
            MoodLog.i("主界面悬浮按钮入口 Hook 已安装")
        }.onFailure {
            MoodLog.w("主界面类未找到: ${it.message}")
        }
    }

    /**
     * 添加悬浮按钮到主界面。
     */
    private fun addFab(activity: Activity) {
        val decorView = activity.window.decorView as ViewGroup
        // 检查是否已经存在
        if (decorView.findViewWithTag<View>(FAB_TAG) != null) return

        val density = activity.resources.displayMetrics.density
        val size = (FAB_SIZE_DP * density).toInt()
        val margin = (FAB_MARGIN_DP * density).toInt()
        val bottomMargin = (FAB_BOTTOM_MARGIN_DP * density).toInt()

        val fab = TextView(activity).apply {
            tag = FAB_TAG
            text = "言外"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(2f, 0f, 1f, Color.argb(128, 0, 0, 0))
            // 圆形绿色背景（微信风格）
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#07C160"))
                setStroke(1, Color.parseColor("#06AD56"))
            }
            elevation = 8f * density
            setOnClickListener {
                openSettings(activity)
            }
        }

        val params = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, margin, bottomMargin)
        }

        decorView.addView(fab, params)
        MoodLog.i("主界面悬浮按钮已添加")
    }

    /**
     * 确保悬浮按钮存在（onResume 时调用）。
     */
    private fun ensureFab(activity: Activity) {
        val decorView = activity.window.decorView as ViewGroup
        if (decorView.findViewWithTag<View>(FAB_TAG) == null) {
            addFab(activity)
        }
    }

    /**
     * 打开言外设置页面。
     */
    private fun openSettings(activity: Activity) {
        runCatching {
            activity.startActivity(
                Intent().setComponent(
                    ComponentName("dev.jev.wechatmood", "dev.jev.wechatmood.MainActivity")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            MoodLog.i("从主界面悬浮按钮打开设置页面")
        }.onFailure {
            MoodLog.e("MAIN_FAB_OPEN_FAILED", it)
        }
    }
}
