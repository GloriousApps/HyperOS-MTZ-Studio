package dev.glorioustr.mtzstudio.tester

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * The local-resource detail deep link, the second surface current Global Themes builds still
 * leave open to an unprivileged caller.
 *
 * Verified in `Themes_3.4.8.36-global.apk`: `ThemeDetailActivity` is exported with **no**
 * `android:permission` and keeps
 *
 * ```
 * <data android:scheme="ViewLocalResource" android:host="view.local.resource" android:pathPattern=".*" />
 * ```
 *
 * **The local id goes in the URI fragment** — that is what the APK's `ViewLocalResource://view.local.resource#`
 * template is: the string ends in `#` because the id is concatenated onto it. Device-verified on
 * HyperOS OS3.0.308.0.WPJMIXM with Themes 3.4.1.23-global and 3.4.8.36-global: the fragment form
 * lands on the theme's own detail page with its Apply button, while the same id in the URI path,
 * in a query parameter or in an Intent extra falls through to the online detail screen and spins
 * forever on "just a second". No extras are needed; `REQUEST_RESOURCE_CODE=theme` is accepted but
 * changes nothing.
 *
 * `pathPattern=".*"` in the filter is satisfied by the empty path, so the URI carries nothing but
 * scheme, host and fragment.
 *
 * This opens the theme's own detail page so the user's apply is one tap. It performs no apply by
 * itself, and a page that merely opened is never evidence that anything was applied.
 */
object LocalResourceDetailContract {
    const val PACKAGE_NAME = "com.android.thememanager"
    const val DETAIL_COMPONENT = "com.android.thememanager.activity.ThemeDetailActivity"
    const val SCHEME = "ViewLocalResource"
    const val HOST = "view.local.resource"

    const val EXTRA_RESOURCE_CODE = "REQUEST_RESOURCE_CODE"
    const val EXTRA_APPLY_EVENT = "REQUEST_APPLY_EVENT"
    const val RESOURCE_CODE_THEME = "theme"

    /** Themes writes local ids as UUIDs; anything else is refused rather than sent. */
    private val SAFE_LOCAL_ID = Regex("[A-Za-z0-9_.-]{1,128}")

    /**
     * `ViewLocalResource://view.local.resource#<localId>`. The fragment is where Themes reads the
     * id from; a path or query carrying it is ignored and the online detail screen opens instead.
     */
    fun localResourceUri(localId: String): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(HOST)
        .encodedFragment(localId)
        .build()

    /**
     * An explicit intent onto the detail page of one local theme. Explicit component plus package
     * means filter matching is not relied upon, and no other app can answer for Themes.
     */
    fun detailIntent(localId: String): Intent? {
        if (!SAFE_LOCAL_ID.matches(localId)) return null
        return Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(PACKAGE_NAME, DETAIL_COMPONENT)
            `package` = PACKAGE_NAME
            addCategory(Intent.CATEGORY_DEFAULT)
            data = localResourceUri(localId)
            // Mirrors MIUI's own `?S.REQUEST_RESOURCE_CODE=theme`. Device-verified as accepted
            // and not required; the fragment alone is what selects the resource.
            putExtra(EXTRA_RESOURCE_CODE, RESOURCE_CODE_THEME)
            // REQUEST_APPLY_EVENT is deliberately NOT sent. Its meaning is not established, and
            // an extra that might start an apply on its own would take the decision away from
            // the user. Opening the page is the whole intent here.
        }
    }
}

/** Why the detail deep link can or cannot be used. [usable] is the only gate for callers. */
data class LocalResourceDetailDeclaration(
    val declared: Boolean,
    val exported: Boolean,
    val enabled: Boolean,
    val requiredPermission: String?,
    val schemeFilterPresent: Boolean,
) {
    val usable: Boolean
        get() = declared && exported && enabled && requiredPermission == null

    fun describe(): Map<String, Any?> = mapOf(
        "declared" to declared,
        "exported" to exported,
        "enabled" to enabled,
        "requiredPermission" to requiredPermission,
        "schemeFilterPresent" to schemeFilterPresent,
    )
}

object LocalResourceDetailAvailability {
    /**
     * Manifest-verified, like every other Themes surface in this module: some HyperOS
     * PackageManager builds resolve an explicit component the active APK never declared, so
     * resolution alone is recorded but never sufficient.
     */
    fun resolve(context: Context): LocalResourceDetailDeclaration {
        val activity = runCatching {
            val flags = PackageManager.GET_ACTIVITIES.toLong()
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    LocalResourceDetailContract.PACKAGE_NAME,
                    PackageManager.PackageInfoFlags.of(flags),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    LocalResourceDetailContract.PACKAGE_NAME,
                    PackageManager.GET_ACTIVITIES,
                )
            }
            info.activities.orEmpty().firstOrNull {
                it.name == LocalResourceDetailContract.DETAIL_COMPONENT
            }
        }.getOrNull()
        // A filter query confirms the scheme is still published; it is reported, not required,
        // because the explicit component does not need filter matching to start.
        val schemeFilterPresent = runCatching {
            val probe = Intent(Intent.ACTION_VIEW).apply {
                `package` = LocalResourceDetailContract.PACKAGE_NAME
                addCategory(Intent.CATEGORY_DEFAULT)
                data = LocalResourceDetailContract.localResourceUri("probe")
            }
            context.packageManager.queryIntentActivities(probe, 0).any {
                it.activityInfo?.name == LocalResourceDetailContract.DETAIL_COMPONENT
            }
        }.getOrDefault(false)
        return LocalResourceDetailDeclaration(
            declared = activity != null,
            exported = activity?.exported == true,
            enabled = activity?.isEnabled ?: false,
            requiredPermission = activity?.permission,
            schemeFilterPresent = schemeFilterPresent,
        )
    }
}
