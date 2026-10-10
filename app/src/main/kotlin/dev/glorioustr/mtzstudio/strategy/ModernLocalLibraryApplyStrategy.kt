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
 * Modern Theme Manager strategy for builds (10.8.x, 11.x and later).
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
        // MiuiBackup is a private-data operation.  ADB-mode Shizuku is deliberately
        // rejected even though it can run ordinary shell staging commands.
        val rootModeBackupReady = SheveryBackupRestorer.state() == SheveryBackupRestorer.State.READY
        return isModern && rootModeBackupReady
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

        // The native catalog is authoritative.  Importing a BAK is asynchronous, so this
        // strategy must receive the local ID after the caller has read the catalog back instead
        // of treating the ID embedded in the backup metadata as a confirmed record.
        val resolvedLocalId = themeManagerLocalId
            ?: error("Modern Theme Manager yerel kimliği katalogdan doğrulanmadan uygulanamaz")

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
    fun importThroughBackup(theme: LibraryTheme) {
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
        } finally {
            backup.delete()
        }
    }

    override suspend fun verifyApplied(theme: LibraryTheme, prepared: PreparedThemeApply): ThemeApplyResult {
        val localId = prepared.themeManagerLocalId
        if (localId.isNullOrBlank() || !localId.matches(SAFE_LOCAL_ID)) {
            return ThemeApplyResult.VerificationFailed(
                reason = "Theme Manager yerel kimliği doğrulanamadı",
            )
        }
        // ViewLocalResource does not return a structured result on the supported
        // 10.8/11 builds.  Do not report a successful apply merely because the
        // activity could be resolved; the persistence monitor is the only
        // authoritative post-apply check for this protocol.
        diagnostics.record(
            "modern_apply_unverified",
            "Modern Theme Manager uygulaması gönderildi ancak host sonucu doğrulanmadı",
            mapOf("theme" to theme.displayName, "localId" to localId),
        )
        return ThemeApplyResult.VerificationFailed(
            reason = "Xiaomi Temalar uygulama sonucu yapılandırılmış olarak dönmedi; kalıcılık izleyicisi sonucu bekleniyor",
        )
    }

    override suspend fun cleanup(prepared: PreparedThemeApply) {
        // No staging file cleanup needed for backup restore path
    }

    private companion object {
        val SAFE_LOCAL_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
