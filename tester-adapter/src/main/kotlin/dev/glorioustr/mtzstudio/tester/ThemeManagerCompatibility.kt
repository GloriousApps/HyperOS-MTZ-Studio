package dev.glorioustr.mtzstudio.tester

object ThemeManagerContract {
    const val RECOMMENDED_VERSION = "3.0.5.6"
    // The Global build the bundled Root MTZ Import module is verified against.
    // This is NOT Shizuku-compatible (tester activity removed); it is applied
    // exclusively through the Root module's Zygisk bridge.
    const val ROOT_GLOBAL_RECOMMENDED_VERSION = "3.4.1.23"
    // The China-family build the bundled Root MTZ Import module is verified against
    // (Mods-Center V7 base). Applied exclusively through the Root module's Zygisk bridge.
    const val ROOT_CHINA_RECOMMENDED_VERSION = "11.5.3.1"
    const val PACKAGE_NAME = "com.android.thememanager"
    const val LEGACY_TESTER_ACTION = "com.android.thememanager.support3.0"
    const val LEGACY_TESTER_COMPONENT = "com.android.thememanager.ApplyThemeForScreenshot"
    const val MODERN_LOCAL_LIBRARY_COMPONENT =
        "com.android.thememanager.mine.remote.view.activity.MineResourceTabActivity"

    // The exported legacy alias and support3.0 contract were inspected in the 3.0.2.34
    // Global branch and device-verified in 3.0.5.6. 3.0.4.32 stays on that same branch.
    // These are the Shizuku-compatible Global builds (tester activity present).
    val SUPPORTED_GLOBAL_VERSIONS = setOf("2.15.5.46", "3.0.4.32", "3.0.5.6")
    const val MODERN_NATIVE_LIBRARY_MIN_VERSION = "10.8.7.6"

    // Root module target APKs, published to the project's GitHub releases. The user supplies
    // these APKs; the app downloads and verifies them before installing. SHA-256 values are
    // filled in once the APKs are published — an empty value blocks the install flow.
    const val ROOT_GLOBAL_APK_NAME = "Xiaomi_Themes_3.4.1.23-global.apk"
    const val ROOT_CHINA_APK_NAME = "Xiaomi_Themes_11.5.3.1.apk"
    const val ROOT_GLOBAL_APK_SHA256 = "d405e78fac1ea48e105e57f5e3422ec0a0a6d53f779973d06e131e85b016037"
    const val ROOT_CHINA_APK_SHA256 = "3888058041439577aadbb933c9bed1fb3ef16acda20d15404fb620314a8edd0a"

    fun canonicalVersion(versionName: String?): String? = versionName
        ?.trim()
        ?.substringBefore('-')
        ?.takeIf(String::isNotBlank)

    /**
     * The Theme Manager family the Root module must pair with. The bundled Zygisk module is
     * verified against a specific Global build (3.4.1.23) and a specific China build (11.5.3.1);
     * installing the wrong family leaves the bridge unable to hook the active importer.
     */
    fun rootTargetApk(family: ThemeManagerFamily): RootTargetApk? = when (family) {
        ThemeManagerFamily.GLOBAL -> RootTargetApk(
            version = ROOT_GLOBAL_RECOMMENDED_VERSION,
            apkName = ROOT_GLOBAL_APK_NAME,
            sha256 = ROOT_GLOBAL_APK_SHA256,
            minSdk = 27,
        )
        ThemeManagerFamily.CHINA -> RootTargetApk(
            version = ROOT_CHINA_RECOMMENDED_VERSION,
            apkName = ROOT_CHINA_APK_NAME,
            sha256 = ROOT_CHINA_APK_SHA256,
            minSdk = 34,
        )
        ThemeManagerFamily.UNKNOWN -> null
    }

    /**
     * Resolves the active Theme Manager family from its version name. Only known builds are
     * classified; anything else stays UNKNOWN so the Root module flow can ask the user instead
     * of guessing.
     */
    fun familyOf(versionName: String?): ThemeManagerFamily {
        val canonical = canonicalVersion(versionName) ?: return ThemeManagerFamily.UNKNOWN
        return when {
            canonical == ROOT_GLOBAL_RECOMMENDED_VERSION -> ThemeManagerFamily.GLOBAL
            canonical == ROOT_CHINA_RECOMMENDED_VERSION -> ThemeManagerFamily.CHINA
            canonical in SUPPORTED_GLOBAL_VERSIONS -> ThemeManagerFamily.GLOBAL
            else -> ThemeManagerFamily.UNKNOWN
        }
    }

    fun behavior(versionName: String?): ThemeManagerBehavior {
        val canonical = canonicalVersion(versionName) ?: return ThemeManagerBehavior.UNKNOWN
        return when {
            canonical in SUPPORTED_GLOBAL_VERSIONS -> ThemeManagerBehavior.LOCAL_THEME_IMPORT
            isModernNativeLibraryVersion(canonical) -> ThemeManagerBehavior.MODERN_NATIVE_LIBRARY
            canonical == "3.0.5.14" -> ThemeManagerBehavior.TEMPORARY_DEFAULT_COMPOSITE
            canonical == "3.0.6.8" -> ThemeManagerBehavior.TESTER_ACTIVITY_REMOVED
            else -> ThemeManagerBehavior.UNKNOWN
        }
    }

    fun isModernNativeLibraryVersion(versionName: String?): Boolean {
        val canonical = canonicalVersion(versionName) ?: return false
        val parts = canonical.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.firstOrNull() !in 10..99) return false
        return compareVersions(parts, MODERN_NATIVE_LIBRARY_MIN_VERSION.split('.').map(String::toInt)) >= 0
    }

    private fun compareVersions(left: List<Int>, right: List<Int>): Int {
        repeat(maxOf(left.size, right.size)) { index ->
            val difference = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    /** The exact tester request used by the verified legacy Global contract. */
    fun legacyTesterRequest(themePath: String, callerPackage: String): LegacyTesterRequest =
        LegacyTesterRequest(
            action = LEGACY_TESTER_ACTION,
            componentClassName = LEGACY_TESTER_COMPONENT,
            stringExtras = linkedMapOf(
                "theme_file_path" to themePath,
                "api_called_from" to callerPackage,
            ),
            longExtras = linkedMapOf(
                "theme_apply_flags" to -1L,
                "theme_remove_flags" to -1L,
            ),
        )

    /** Exact local-apply contract used by Zyper after Themes can read the restored MTZ. */
    fun localRestoredThemeRequest(themePath: String): LegacyTesterRequest =
        LegacyTesterRequest(
            action = "",
            componentClassName = LEGACY_TESTER_COMPONENT,
            stringExtras = linkedMapOf(
                "theme_file_path" to themePath,
                "api_called_from" to PACKAGE_NAME,
            ),
            longExtras = emptyMap(),
        )
}

data class RootTargetApk(
    val version: String,
    val apkName: String,
    val sha256: String,
    /** Minimum Android SDK the target build requires (11.5.3.1 needs API 34+). */
    val minSdk: Int = 27,
)

/**
 * Which Theme Manager family is active on the device. The Global and China families expose different
 * importers; the Root module must pair with the matching build.
 */
enum class ThemeManagerFamily {
    GLOBAL,
    CHINA,
    UNKNOWN,
}

data class LegacyTesterRequest(
    val action: String,
    val componentClassName: String,
    val stringExtras: Map<String, String>,
    val longExtras: Map<String, Long>,
)

enum class ThemeManagerBehavior(val explanation: String) {
    LOCAL_THEME_IMPORT("Imports the MTZ as an independent local theme"),
    MODERN_NATIVE_LIBRARY("Theme Manager is the authoritative local theme library and provides persistent third-party MTZ import"),
    TEMPORARY_DEFAULT_COMPOSITE("Interprets the tester call as a temporary/composite application over Default"),
    TESTER_ACTIVITY_REMOVED("The tester activity is absent"),
    UNKNOWN("Tester behavior is not verified for this version"),
}

data class InstalledThemeManager(
    val installed: Boolean,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long?,
    val behavior: ThemeManagerBehavior,
    val family: ThemeManagerFamily = ThemeManagerFamily.UNKNOWN,
    internal val signingCertificateSha256: Set<String> = emptySet(),
) {
    val isRecommended: Boolean
        get() = installed && (
            ThemeManagerContract.canonicalVersion(versionName) in ThemeManagerContract.SUPPORTED_GLOBAL_VERSIONS ||
            ThemeManagerContract.canonicalVersion(versionName) == ThemeManagerContract.ROOT_GLOBAL_RECOMMENDED_VERSION ||
            ThemeManagerContract.canonicalVersion(versionName) == ThemeManagerContract.ROOT_CHINA_RECOMMENDED_VERSION ||
            behavior == ThemeManagerBehavior.LOCAL_THEME_IMPORT ||
            behavior == ThemeManagerBehavior.MODERN_NATIVE_LIBRARY
        )

    val requiresGlobalThemeProtection: Boolean
        get() = behavior != ThemeManagerBehavior.MODERN_NATIVE_LIBRARY

    val usesModernNativeLibrary: Boolean
        get() = behavior == ThemeManagerBehavior.MODERN_NATIVE_LIBRARY
}
