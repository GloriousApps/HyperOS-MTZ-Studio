package dev.glorioustr.mtzstudio.strategy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.glorioustr.mtzstudio.LiveDiagnosticsRecorder
import dev.glorioustr.mtzstudio.MtzPublicExporter
import dev.glorioustr.mtzstudio.PreparedThemeApply
import dev.glorioustr.mtzstudio.ThemeApplyProtocol
import dev.glorioustr.mtzstudio.ThemeManagerOperation
import dev.glorioustr.mtzstudio.core.Hashing
import dev.glorioustr.mtzstudio.library.LibraryTheme
import dev.glorioustr.mtzstudio.tester.ThemeApplyResult
import dev.glorioustr.mtzstudio.tester.ThemeManagerContract
import java.io.File

/**
 * Rootless Strategy for Legacy Global Theme Manager builds (2.15.5.46, 3.0.4.32, 3.0.5.6)
 * using the exported ApplyThemeForScreenshot contract.
 *
 * Explicitly separates:
 * 1. Staging MTZ to public Downloads
 * 2. Publishing Preview / resolving ApplyThemeForScreenshot
 * 3. Building Verified Request Intent
 * 4. Verification and Persistence boundary
 */
class LegacyGlobalPreviewApplyStrategy(
    private val context: Context,
) : ThemeApplyStrategy {

    override val strategyName: String = "LegacyGlobalPreview"

    private val diagnostics get() = LiveDiagnosticsRecorder.get(context)

    override fun isAvailable(): Boolean {
        val installedVersion = runCatching {
            context.packageManager.getPackageInfo(ThemeManagerContract.PACKAGE_NAME, 0).versionName
        }.getOrNull()
        val canonical = ThemeManagerContract.canonicalVersion(installedVersion)
        if (canonical !in ThemeManagerContract.SUPPORTED_GLOBAL_VERSIONS &&
            canonical != ThemeManagerContract.CONTROLLED_GLOBAL_VERSION
        ) {
            return false
        }
        val request = ThemeManagerContract.legacyTesterRequest("/dev/null", context.packageName)
        val intent = Intent(request.action).apply {
            component = ComponentName(ThemeManagerContract.PACKAGE_NAME, request.componentClassName)
        }
        return intent.resolveActivity(context.packageManager) != null
    }

    override suspend fun prepare(
        theme: LibraryTheme,
        themeManagerLocalId: String?,
        replacedLocalIds: Set<String>,
    ): PreparedThemeApply {
        check(Hashing.sha256(theme.archive.source) == theme.archive.sha256) {
            "Tema kaynağı doğrulama sonrası değişmiş"
        }
        val themeName = theme.archive.metadata?.name ?: theme.displayName
        val publicFile = checkNotNull(
            MtzPublicExporter.exportToPublicDownloads(context, theme.archive.source, themeName),
        ) { "MTZ, İndirilenler/MTZ Studio klasörüne kaydedilemedi" }

        check(isAvailable()) {
            "Bu Temalar sürümünde ApplyThemeForScreenshot aktivitesi bulunamadı; otomatik önizleme uygulanamaz."
        }

        val request = ThemeManagerContract.legacyTesterRequest(publicFile.absolutePath, context.packageName)
        val intent = Intent(request.action).apply {
            component = ComponentName(ThemeManagerContract.PACKAGE_NAME, request.componentClassName)
            request.stringExtras.forEach(::putExtra)
            request.longExtras.forEach(::putExtra)
        }

        diagnostics.record(
            "legacy_preview_strategy_prepared",
            "Legacy Global ApplyThemeForScreenshot isteği başarıyla hazırlandı",
            mapOf("theme" to themeName, "stagedPath" to publicFile.absolutePath),
        )

        return PreparedThemeApply(
            themeId = theme.id.value,
            themeName = themeName,
            stagedPath = publicFile.absolutePath,
            intent = intent,
            protocol = ThemeApplyProtocol.ROOTLESS_LEGACY_TESTER,
            manualImportPath = publicFile.absolutePath,
            operation = ThemeManagerOperation.APPLY,
        )
    }

    override suspend fun verifyApplied(theme: LibraryTheme, prepared: PreparedThemeApply): ThemeApplyResult {
        // Legacy Global returns asynchronously without guarantee. Verification is handled
        // via component fingerprinting in PersistenceGuard.
        return ThemeApplyResult.Success(
            themeId = theme.id.value,
            themeName = theme.displayName,
            strategyName = strategyName,
            persistenceArmed = true,
        )
    }

    override suspend fun cleanup(prepared: PreparedThemeApply) {
        // Do not immediately delete public file in Downloads so Themes can read it
    }
}
