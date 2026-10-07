package dev.glorioustr.mtzstudio.tester

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeManagerCompatibilityTest {
    @Test
    fun `recommended version is recognized with global suffix`() {
        assertEquals("3.0.5.6", ThemeManagerContract.RECOMMENDED_VERSION)
        assertEquals("3.0.5.6", ThemeManagerContract.canonicalVersion("3.0.5.6-global"))
        assertEquals(ThemeManagerBehavior.LOCAL_THEME_IMPORT, ThemeManagerContract.behavior("3.0.5.6-global"))
    }

    @Test
    fun `known global versions map to observed tester behavior`() {
        assertEquals(
            ThemeManagerBehavior.LOCAL_THEME_IMPORT,
            ThemeManagerContract.behavior("2.15.5.46-global"),
        )
        assertEquals(
            ThemeManagerBehavior.LOCAL_THEME_IMPORT,
            ThemeManagerContract.behavior("3.0.4.32-global"),
        )
        assertEquals(
            ThemeManagerBehavior.MODERN_NATIVE_LIBRARY,
            ThemeManagerContract.behavior("10.8.7.6"),
        )
        assertEquals(
            ThemeManagerBehavior.TEMPORARY_DEFAULT_COMPOSITE,
            ThemeManagerContract.behavior("3.0.5.14"),
        )
        assertEquals(
            ThemeManagerBehavior.TESTER_ACTIVITY_REMOVED,
            ThemeManagerContract.behavior("3.0.6.8-global"),
        )
        assertEquals(ThemeManagerBehavior.UNKNOWN, ThemeManagerContract.behavior("4.0.0.0"))
    }

    @Test
    fun `root global recommended version is the bundled root module build`() {
        assertEquals("3.4.1.23", ThemeManagerContract.ROOT_GLOBAL_RECOMMENDED_VERSION)
        // Tester activity is removed in 3.4.1.23 -> not Shizuku-applicable, but the
        // Root module bridge covers it.
        assertEquals(ThemeManagerBehavior.UNKNOWN, ThemeManagerContract.behavior("3.4.1.23-global"))
        val installed = InstalledThemeManager(
            installed = true,
            packageName = ThemeManagerContract.PACKAGE_NAME,
            versionName = "3.4.1.23-global",
            versionCode = 304123,
            behavior = ThemeManagerContract.behavior("3.4.1.23-global"),
        )
        assertTrue(installed.isRecommended)
    }

    @Test
    fun `modern native library covers verified and later China Theme Manager family`() {
        listOf("10.8.7.6", "10.9.2.0", "10.9.4.0", "10.9.5.2", "11.0.8.0", "11.1.5.0")
            .forEach { version ->
                assertEquals(ThemeManagerBehavior.MODERN_NATIVE_LIBRARY, ThemeManagerContract.behavior(version))
            }
        assertEquals(ThemeManagerBehavior.UNKNOWN, ThemeManagerContract.behavior("10.8.7.5"))
        assertEquals(ThemeManagerBehavior.TESTER_ACTIVITY_REMOVED, ThemeManagerContract.behavior("3.0.6.8"))
    }

    @Test
    fun `modern profile is compatible only when native local library resolves`() {
        val available = ThemeManagerRuntimeProfile(
            packageInstalled = true,
            versionName = "11.1.5.0",
            knownBehavior = ThemeManagerBehavior.MODERN_NATIVE_LIBRARY,
            legacyTesterResolvable = false,
            modernLocalLibraryResolvable = true,
            publicMtzImportResolvable = false,
            splitApkCount = 0,
            exportedThemeActivityCandidates = emptyList(),
        )
        val missing = available.copy(modernLocalLibraryResolvable = false)

        assertTrue(available.compatibleLocalMtzPath)
        assertFalse(missing.compatibleLocalMtzPath)
    }

    @Test
    fun `modded persistent import disables redundant global protection`() {
        val installed = InstalledThemeManager(
            installed = true,
            packageName = ThemeManagerContract.PACKAGE_NAME,
            versionName = "10.8.7.6",
            versionCode = 10876,
            behavior = ThemeManagerContract.behavior("10.8.7.6"),
        )

        assertTrue(installed.isRecommended)
        assertFalse(installed.requiresGlobalThemeProtection)
    }

    @Test
    fun `verified global path continues to require protection`() {
        val installed = InstalledThemeManager(
            installed = true,
            packageName = ThemeManagerContract.PACKAGE_NAME,
            versionName = "3.0.5.6",
            versionCode = 30506,
            behavior = ThemeManagerContract.behavior("3.0.5.6"),
        )

        assertTrue(installed.isRecommended)
        assertTrue(installed.requiresGlobalThemeProtection)
    }

    @Test
    fun `global tester request stays on the device verified contract`() {
        val request = ThemeManagerContract.legacyTesterRequest(
            themePath = "/staging/Circle-UI-Black-Icons.mtz",
            callerPackage = "dev.glorioustr.mtzstudio",
        )

        assertEquals("com.android.thememanager.support3.0", request.action)
        assertEquals("com.android.thememanager.ApplyThemeForScreenshot", request.componentClassName)
        assertEquals(
            linkedMapOf(
                "theme_file_path" to "/staging/Circle-UI-Black-Icons.mtz",
                "api_called_from" to "dev.glorioustr.mtzstudio",
            ),
            request.stringExtras,
        )
        assertEquals(
            linkedMapOf(
                "theme_apply_flags" to -1L,
                "theme_remove_flags" to -1L,
            ),
            request.longExtras,
        )
        assertEquals(4, request.stringExtras.size + request.longExtras.size)
    }

    @Test
    fun `restored local request matches zyper contract`() {
        val path = "/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/theme/Test.mtz"
        val request = ThemeManagerContract.localRestoredThemeRequest(path)

        assertEquals("", request.action)
        assertEquals("com.android.thememanager.ApplyThemeForScreenshot", request.componentClassName)
        assertEquals(path, request.stringExtras["theme_file_path"])
        assertEquals("com.android.thememanager", request.stringExtras["api_called_from"])
        assertTrue(request.longExtras.isEmpty())
    }

    @Test
    fun `root command stages verified apk in a fixed temporary namespace and cleans it`() {
        val command = RootInstallCommand.forStagedApk(
            "/data/user/0/dev.glorioustr.mtzstudio/cache/theme-manager-update/themes-id.apk",
            "/data/local/tmp/mtzstudio-theme-manager-id.apk",
        )
        assertTrue(command.contains("/system/bin/cp '/data/user/0/dev.glorioustr.mtzstudio/cache/theme-manager-update/themes-id.apk'"))
        assertTrue(command.contains("/system/bin/pm install -r -d --user 0 '/data/local/tmp/mtzstudio-theme-manager-id.apk'"))
        assertTrue(command.contains("/system/bin/rm -f '/data/local/tmp/mtzstudio-theme-manager-id.apk'"))
        assertFalse(" -S " in command)
        assertFalse("skip-verification" in command)
        assertFalse("uninstall" in command)
    }

    @Test
    fun `shizuku command moves shared download to shell staging before install`() {
        val command = ShellInstallCommand.forDownloadedApk(
            "/sdcard/Download/Xiaomi_Themes_3.0.5.6-global.apk",
        )

        assertTrue(command.contains("/system/bin/cp '/sdcard/Download/Xiaomi_Themes_3.0.5.6-global.apk' '/data/local/tmp/mtzstudio-theme-manager-"))
        assertTrue(command.contains("/system/bin/chmod 0644 '/data/local/tmp/mtzstudio-theme-manager-"))
        assertTrue(command.contains("/system/bin/pm install -r -d --user 0 '/data/local/tmp/mtzstudio-theme-manager-"))
        assertTrue(command.contains("/system/bin/rm -f '/data/local/tmp/mtzstudio-theme-manager-"))
        assertFalse("pm install -r -d '/sdcard/Download/" in command)
    }
}
