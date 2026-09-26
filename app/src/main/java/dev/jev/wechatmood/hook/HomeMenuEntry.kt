package dev.jev.wechatmood.hook

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import dev.jev.wechatmood.core.MoodLog

/**
 * 微信首页右上角菜单入口。
 *
 * 参考 WeKit 的实现方式：hook LauncherUI.onCreateOptionsMenu，
 * 在微信主界面（聊天列表页）右上角菜单中添加「言外设置」选项。
 *
 * 这是最可靠的入口方式，不依赖设置页面的结构，也不会出现透明看不见的问题。
 */
object HomeMenuEntry {
    private const val TAG = "HomeMenuEntry"
    private const val LAUNCHER_UI_CLASS = "com.tencent.mm.ui.LauncherUI"
    private const val MENU_ITEM_ID = 0x7F0A0001  // 自定义菜单项 ID，避免与微信冲突

    private var installed = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true

        runCatching {
            val launcherClass = XposedHelpers.findClass(LAUNCHER_UI_CLASS, classLoader)

            // hook onCreateOptionsMenu，添加菜单项
            XposedBridge.hookAllMethods(launcherClass, "onCreateOptionsMenu", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val menu = param.args.getOrNull(0) as? Menu ?: return@runCatching
                        addMenuItem(menu, param.thisObject as Activity)
                    }.onFailure { MoodLog.e("HOME_MENU_ADD_FAILED", it) }
                }
            })

            // hook onOptionsItemSelected，处理菜单项点击
            XposedBridge.hookAllMethods(launcherClass, "onOptionsItemSelected", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val item = param.args.getOrNull(0) as? MenuItem ?: return@runCatching
                        if (item.itemId == MENU_ITEM_ID) {
                            val activity = param.thisObject as Activity
                            openSettings(activity)
                            param.result = true
                        }
                    }.onFailure { MoodLog.e("HOME_MENU_CLICK_FAILED", it) }
                }
            })

            MoodLog.i("首页右上角菜单入口 Hook 已安装")
        }.onFailure {
            MoodLog.w("首页类未找到: ${it.message}")
        }
    }

    /**
     * 向菜单中添加「言外设置」选项。
     * 如果已经存在则不重复添加。
     */
    private fun addMenuItem(menu: Menu, activity: Activity) {
        // 检查是否已经存在
        if (menu.findItem(MENU_ITEM_ID) != null) return

        // 添加菜单项，放在最后
        val item = menu.add(0, MENU_ITEM_ID, 100, "言外设置")
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)  // 放在溢出菜单中，不占标题栏空间

        MoodLog.i("首页右上角菜单已添加「言外设置」")
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
            MoodLog.i("从首页右上角菜单打开设置页面")
        }.onFailure {
            MoodLog.e("HOME_MENU_OPEN_FAILED", it)
        }
    }
}
