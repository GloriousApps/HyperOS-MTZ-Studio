package dev.glorioustr.mtzstudio.tester

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * The unprivileged Xiaomi Themes wallpaper/icon service.
 *
 * Verified against `Themes_3.4.8.36-global.apk`, the build that no longer ships
 * `ApplyThemeForScreenshot`:
 *
 * ```
 * <service android:name="com.android.thememanager.service.ThemeService" android:exported="true">
 *   <intent-filter><action android:name="com.miui.service.THEMESERVICE" /></intent-filter>
 * </service>
 * ```
 *
 * It declares **no** `android:permission`, and its binder extends
 * `miui.content.res.IThemeService$Stub` with `saveWallpaper(String)`,
 * `saveLockWallpaper(String)`, `saveIcon(String)` and `saveCustomizedIcon(String, Bitmap)`.
 *
 * So this is a real channel for wallpaper and icon work that needs neither root nor Shizuku. It
 * is not a theme apply: fonts, framework/SystemUI overlays and the lockscreen style stay behind
 * the privileged paths, and a result from here must never be reported as an applied MTZ.
 */
object ThemeServiceContract {
    const val PACKAGE_NAME = "com.android.thememanager"
    const val SERVICE_CLASS = "com.android.thememanager.service.ThemeService"
    const val ACTION = "com.miui.service.THEMESERVICE"
    const val INTERFACE_CLASS = "miui.content.res.IThemeService"
    const val STUB_CLASS = "miui.content.res.IThemeService\$Stub"

    const val SAVE_WALLPAPER = "saveWallpaper"
    const val SAVE_LOCK_WALLPAPER = "saveLockWallpaper"
    const val SAVE_ICON = "saveIcon"
    const val SAVE_CUSTOMIZED_ICON = "saveCustomizedIcon"

    /** The explicit bind target. An implicit service bind is refused from Android 5 onward. */
    fun bindIntent(): Intent = Intent(ACTION).apply {
        component = ComponentName(PACKAGE_NAME, SERVICE_CLASS)
        `package` = PACKAGE_NAME
    }
}

/**
 * Why the service can or cannot be used, resolved from the installed package rather than from a
 * version table. [usable] is the only thing callers should gate on.
 */
data class ThemeServiceDeclaration(
    val declared: Boolean,
    val exported: Boolean,
    val enabled: Boolean,
    /** A service that declares a permission cannot be bound by an unprivileged caller. */
    val requiredPermission: String?,
    val actionResolves: Boolean,
    /** False on a device whose framework does not ship the hidden MIUI interface. */
    val interfaceAvailable: Boolean,
) {
    val usable: Boolean
        get() = declared && exported && enabled && requiredPermission == null && interfaceAvailable

    fun describe(): Map<String, Any?> = mapOf(
        "declared" to declared,
        "exported" to exported,
        "enabled" to enabled,
        "requiredPermission" to requiredPermission,
        "actionResolves" to actionResolves,
        "interfaceAvailable" to interfaceAvailable,
    )

    companion object {
        val UNAVAILABLE = ThemeServiceDeclaration(
            declared = false,
            exported = false,
            enabled = false,
            requiredPermission = null,
            actionResolves = false,
            interfaceAvailable = false,
        )
    }
}

object ThemeServiceAvailability {
    /**
     * Reads the installed manifest instead of trusting intent resolution alone: some HyperOS
     * PackageManager builds answer a non-null result for an explicit component the active APK
     * does not declare, and a service carrying a permission must not be reported as usable.
     */
    fun resolve(context: Context): ThemeServiceDeclaration {
        val service = runCatching {
            val flags = PackageManager.GET_SERVICES.toLong()
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    ThemeServiceContract.PACKAGE_NAME,
                    PackageManager.PackageInfoFlags.of(flags),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    ThemeServiceContract.PACKAGE_NAME,
                    PackageManager.GET_SERVICES,
                )
            }
            info.services.orEmpty().firstOrNull { it.name == ThemeServiceContract.SERVICE_CLASS }
        }.getOrNull()
        val actionResolves = runCatching {
            context.packageManager.queryIntentServices(ThemeServiceContract.bindIntent(), 0).any {
                it.serviceInfo?.packageName == ThemeServiceContract.PACKAGE_NAME
            }
        }.getOrDefault(false)
        return ThemeServiceDeclaration(
            declared = service != null,
            exported = service?.exported == true,
            enabled = service?.isEnabled ?: false,
            requiredPermission = service?.permission,
            actionResolves = actionResolves,
            interfaceAvailable = interfaceAvailable(),
        )
    }

    fun interfaceAvailable(): Boolean =
        runCatching { Class.forName(ThemeServiceContract.INTERFACE_CLASS) }.isSuccess
}
