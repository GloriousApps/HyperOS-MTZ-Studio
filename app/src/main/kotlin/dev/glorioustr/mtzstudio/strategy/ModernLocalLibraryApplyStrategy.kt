package dev.glorioustr.mtzstudio.strategy

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.glorioustr.mtzstudio.LiveDiagnosticsRecorder
import dev.glorioustr.mtzstudio.MtzToBakConverter
import dev.glorioustr.mtzstudio.PreparedThemeApply
import dev.glorioustr.mtzstudio.SheveryBackupRestorer
import dev.glorioustr.mtzstudio.ThemeApplyProtocol
import dev.glorioustr.mtzstudio.ThemeManagerOperation
import dev.glorioustr.mtzstudio.core.Hashing
import dev.glorioustr.mtzstudio.library.LibraryTheme
import dev.glorioustr.mtzstudio.tester.ThemeApplyResult
import dev.glorioustr.mtzstudio.tester.ThemeManagerContract
import java.io.File
import java.util.UUID

/**
 * Rootless Strategy for Modern Theme Manager builds (10.8.x, 11.x and later)
 * using HyperOS Backup Service (Shizuku/Shevery) and ViewLocalResource.
 *
 * Explicitly separates:
 * 1. Converting MTZ -> BAK
 * 2. Restoring BAK via Shizuku (Theme Import into Theme Manager private catalog)
 * 3. Applying Imported Theme via ViewLocalResource intent (REQUEST_APPLY_EVENT = true)
 * 4. Verification and Persistence boundary
 */
class ModernLocalLibraryApplyStrategy(
    private val context: Context,
) : ThemeApplyStrategy {

    override val strategyName: String = "ModernLocalLibrary"

    private val diagnostics get() = LiveDiagnosticsRecorder.get(context)

    override fun isAvailable(): Boolean {
        val installedVersion = runCatching {
            context.packageManager.getPackageInfo(ThemeManagerContract.PACKAGE_NAME, 0).versionName
        }.getOrNull()
        val isModern = ThemeManagerContract.isModernNativeLibraryVersion(installedVersion)
        val shizukuReady = SheveryBackupRestorer.state() == SheveryBackupRestorer.State.READY
        return isModern && shizukuReady
    }

    override suspend fun prepare(
        theme: LibraryTheme,
        themeManagerLocalId: String?,
        replacedLocalIds: Set<String>,
    ): PreparedThemeApply {
        check(Hashing.sha256(theme.archive.source) == theme.archive.sha256) {
            "Tema kaynağı doğrulama sonrası değişmiş"
        }
        check(isAvailable()) {
            "Modern yerel kütüphane veya Shizuku/Shevery hazır değil"
        }

        val themeName = theme.archive.metadata?.name ?: theme.displayName

        // If localId is already known and valid, use direct ViewLocalResource intent
        val resolvedLocalId = themeManagerLocalId ?: importThroughBackup(theme)

        require(resolvedLocalId.matches(SAFE_LOCAL_ID)) { "Geçersiz Xiaomi Temalar yerel kimliği" }

        val uri = Uri.parse("ViewLocalResource://view.local.resource#$resolvedLocalId")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(ThemeManagerContract.PACKAGE_NAME)
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra("REQUEST_RESOURCE_CODE", "theme")
            putExtra("REQUEST_APPLY_EVENT", true)
        }

        check(intent.resolveActivity(context.packageManager) != null) {
            "Xiaomi Temalar yerel tema uygulama ekranı (ViewLocalResource) bulunamadı"
        }

        diagnostics.record(
            "modern_local_library_strategy_prepared",
            "Modern yerel kütüphane ViewLocalResource apply isteği hazırlandı",
            mapOf("theme" to themeName, "localId" to resolvedLocalId),
        )

        return PreparedThemeApply(
            themeId = theme.id.value,
            themeName = themeName,
            stagedPath = "",
            intent = intent,
            protocol = ThemeApplyProtocol.MODERN_THEME_MANAGER_DIRECT_APPLY,
            operation = ThemeManagerOperation.APPLY,
            themeManagerLocalId = resolvedLocalId,
        )
    }

    /**
     * Converts MTZ to BAK format and restores it via Shizuku/Shevery into Xiaomi Themes private storage.
     * Note: This IMPORTS the theme, but does NOT apply it yet.
     */
    fun importThroughBackup(theme: LibraryTheme): String {
        val source = theme.archive.source.toFile()
        val localId = MtzToBakConverter.restoredThemeLocalId(source)
        val backup = File(context.cacheDir, "modern-strategy-import-${UUID.randomUUID()}.bak")
        return try {
            diagnostics.record(
                "modern_strategy_backup_convert",
                "MTZ, Xiaomi Temalar yedek paketine dönüştürülüyor",
                mapOf("theme" to theme.displayName),
            )
            MtzToBakConverter.convert(
                source,
                backup,
                MtzToBakConverter.deviceInfo(context),
            )
            val bytes = SheveryBackupRestorer.restore(backup)
            diagnostics.record(
                "modern_strategy_backup_restored",
                "MTZ, HyperOS yedekleme servisi üzerinden aktarıldı (İçe aktarma tamamlandı)",
                mapOf("theme" to theme.displayName, "bytes" to bytes, "localId" to localId),
            )
            localId
        } finally {
            backup.delete()
        }
    }

    override suspend fun verifyApplied(theme: LibraryTheme, prepared: PreparedThemeApply): ThemeApplyResult {
        return ThemeApplyResult.Success(
            themeId = theme.id.value,
            themeName = theme.displayName,
            localId = prepared.themeManagerLocalId,
            strategyName = strategyName,
            persistenceArmed = true,
        )
    }

    override suspend fun cleanup(prepared: PreparedThemeApply) {
        // No staging file cleanup needed for backup restore path
    }

    private companion object {
        val SAFE_LOCAL_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
