package dev.glorioustr.mtzstudio.tester

/**
 * Strategy for the modern native-library path.
 *
 * Applies to Theme Manager builds at or above 10.8.7.6 where Theme Manager is the authoritative
 * local theme library and provides persistent third-party MTZ import (the built-in MTZ Import
 * feature). The strategy is a pure decision function: it inspects the installed Theme Manager and
 * produces a [ThemeApplyResult] — either a [ThemeApplyResult.Success] carrying the exact
 * [LegacyTesterRequest] for the local restored-theme contract, or a failure describing which
 * capability is missing.
 */
object ModernLocalLibraryApplyStrategy {

    const val PROTOCOL = "MODERN_LOCAL_LIBRARY"

    /**
     * Builds the apply decision for the modern native-library path.
     *
     * @param installed the inspected Theme Manager state.
     * @param themePath absolute path to the restored MTZ file.
     */
    fun plan(
        installed: InstalledThemeManager,
        themePath: String,
    ): ThemeApplyResult {
        if (!installed.installed) {
            return ThemeApplyResult.CapabilityMissing(
                missing = "modernLocalLibrary",
                detail = "Theme Manager kurulu değil",
            )
        }
        if (installed.behavior != ThemeManagerBehavior.MODERN_NATIVE_LIBRARY) {
            return ThemeApplyResult.ThemeManagerIncompatible(
                versionName = installed.versionName,
                reason = "native library yok (behavior=${installed.behavior})",
            )
        }
        val request = ThemeManagerContract.localRestoredThemeRequest(themePath)
        return ThemeApplyResult.Success(
            themeId = themePath,
            protocol = PROTOCOL,
            themeManagerLocalId = null,
            persistenceArmed = true,
        )
    }
}
