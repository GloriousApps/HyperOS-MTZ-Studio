package dev.glorioustr.mtzstudio.tester

/**
 * Strategy for the Shizuku/legacy Global tester path.
 *
 * Applies to the Shizuku-compatible Global builds (2.15.5.46, 3.0.4.32, 3.0.5.6) where the
 * exported `ApplyThemeForScreenshot` tester activity is present. The strategy is a pure decision
 * function: it inspects the installed Theme Manager and produces a [ThemeApplyResult] — either a
 * [ThemeApplyResult.Success] carrying the exact [LegacyTesterRequest], or a failure describing
 * which capability is missing.
 */
object LegacyGlobalPreviewApplyStrategy {

    const val PROTOCOL = "LEGACY_GLOBAL_PREVIEW"

    /**
     * Builds the apply decision for the legacy tester path.
     *
     * @param installed the inspected Theme Manager state.
     * @param themePath absolute path to the staged MTZ file.
     * @param callerPackage package that invokes the tester (MTZ Studio).
     */
    fun plan(
        installed: InstalledThemeManager,
        themePath: String,
        callerPackage: String,
    ): ThemeApplyResult {
        if (!installed.installed) {
            return ThemeApplyResult.CapabilityMissing(
                missing = "legacyTester",
                detail = "Theme Manager kurulu değil",
            )
        }
        if (installed.behavior != ThemeManagerBehavior.LOCAL_THEME_IMPORT) {
            return ThemeApplyResult.ThemeManagerIncompatible(
                versionName = installed.versionName,
                reason = "tester activity yok (behavior=${installed.behavior})",
            )
        }
        val request = ThemeManagerContract.legacyTesterRequest(themePath, callerPackage)
        return ThemeApplyResult.Success(
            themeId = themePath,
            protocol = PROTOCOL,
            themeManagerLocalId = null,
            persistenceArmed = false,
        )
    }
}
