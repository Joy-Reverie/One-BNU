package io.github.joyreverie.onebnu.widget

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * MIUI 独有的「桌面快捷方式」权限：没开时 `requestPinAppWidget` 会被桌面静默吞掉，
 * 接口却照样返回 true。系统没有公开的申请接口，只能查一下状态、把用户带到 MIUI 的权限编辑页。
 */
object MiuiShortcutPermission {

    /** MIUI 里「桌面快捷方式」对应的 AppOps 编号。 */
    private const val OP_INSTALL_SHORTCUT = 10017

    /** debug 预览页用：true 表示强行当成「小米且未授权」，模拟器上也能看这条提示。 */
    var previewOverride: Boolean? = null

    val isMiui: Boolean by lazy {
        val brand = (Build.MANUFACTURER + Build.BRAND).lowercase()
        brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") || miuiVersion().isNotBlank()
    }

    /** 是否需要提醒用户去开权限：只在 MIUI 且能确认未授权时为 true。 */
    fun needsGrant(context: Context): Boolean {
        previewOverride?.let { return it }
        if (!isMiui) return false
        return isAllowed(context) == false
    }

    /** true / false 为查到的结果，null 为查不到（非 MIUI 或反射失败）。 */
    fun isAllowed(context: Context): Boolean? = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return null
        val method = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
        )
        val mode = method.invoke(ops, OP_INSTALL_SHORTCUT, android.os.Process.myUid(), context.packageName) as Int
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrNull()

    /** 打开 MIUI 的应用权限编辑页；机型不支持时退回系统的应用信息页。 */
    fun openSettings(context: Context) {
        val pkg = context.packageName
        val candidates = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname", pkg),
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                .putExtra("extra_pkgname", pkg),
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
        )
        for (intent in candidates) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
        }
    }

    private fun miuiVersion(): String = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java).invoke(null, "ro.miui.ui.version.name") as String
    }.getOrDefault("")
}
