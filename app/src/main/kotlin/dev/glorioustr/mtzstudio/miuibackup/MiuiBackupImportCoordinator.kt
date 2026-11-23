package dev.glorioustr.mtzstudio.miuibackup

import android.content.Context
import android.content.Intent
import dev.glorioustr.mtzstudio.LiveDiagnosticsRecorder
import dev.glorioustr.mtzstudio.library.LibraryTheme
import dev.glorioustr.mtzstudio.tester.LocalResourceDetailAvailability
import dev.glorioustr.mtzstudio.tester.LocalResourceDetailContract

/**
 * Drives the two-step, rootless MIUI Backup import: stage the theme as a MIUI Backup package and
 * open MIUI Backup, then — after the user runs the restore there and comes back — open the restored
 * theme's own page in Xiaomi Themes so they can apply it.
 *
 * The restore itself happens inside MIUI Backup (a system app), so this never needs root or Shizuku.
 * The apply is the user's tap on Themes' own Apply button; this class only opens that page. Protected
 * (DRM) content is still gated by Xiaomi at apply time — that is Themes' decision, not ours.
 */
class MiuiBackupImportCoordinator(private val context: Context) {

    private val diagnostics get() = LiveDiagnosticsRecorder.get(context.applicationContext)
    private val pending = PendingMiuiImportStore(context)

    sealed interface Readiness {
        data object Ready : Readiness
        data object BackupAppMissing : Readiness
        data class NeedsAllFilesAccess(val settingsIntent: Intent) : Readiness
    }

    fun readiness(): Readiness = when (MiuiBackupThemeStager.availability(context)) {
        MiuiBackupThemeStager.Availability.READY -> Readiness.Ready
        MiuiBackupThemeStager.Availability.BACKUP_APP_MISSING -> Readiness.BackupAppMissing
        MiuiBackupThemeStager.Availability.NEEDS_ALL_FILES_ACCESS ->
            Readiness.NeedsAllFilesAccess(MiuiBackupThemeStager.allFilesAccessSettingsIntent(context))
    }

    data class StartedImport(val restoreIntent: Intent, val localId: String, val themeTitle: String)

    /**
     * Stages [theme] into MIUI Backup's folder and returns the intent that opens MIUI Backup, plus
     * the localId to apply afterwards. The caller launches [StartedImport.restoreIntent]; the pending
     * apply is remembered so the UI can offer it when the user returns. Old staged packages are
     * pruned first so the restore list does not fill up.
     */
    fun start(theme: LibraryTheme): StartedImport {
        MiuiBackupThemeStager.pruneStaged(context)
        val staged = MiuiBackupThemeStager.stage(context, theme)
        val restoreIntent = MiuiBackupThemeStager.resolvableRestoreIntent(context)
            ?: error("MIUI Yedekleme açılamadı")
        pending.remember(staged.localId, staged.themeTitle, theme.id.value)
        diagnostics.record(
            "miui_backup_import_started",
            "MIUI Yedekleme içe aktarma başlatıldı; kullanıcı geri yüklemeyi tamamlayacak",
            mapOf("theme" to staged.themeTitle, "localId" to staged.localId),
        )
        return StartedImport(restoreIntent, staged.localId, staged.themeTitle)
    }

    /** A pending import the user started but may not have finished restoring yet. */
    data class PendingApply(val localId: String, val themeTitle: String, val themeId: String)

    fun pendingApply(): PendingApply? = pending.load()

    fun clearPending() = pending.clear()

    /**
     * Opens the restored theme's own detail page (with Themes' Apply button) via the local-resource
     * deep link. Returns null when the deep link is unusable on this build or the localId is
     * malformed; a non-null intent does not promise the theme is in the catalogue — if the user has
     * not restored it yet, Themes shows its online page instead.
     */
    fun applyIntent(localId: String): Intent? {
        if (LocalResourceDetailAvailability.resolve(context).usable != true) {
            diagnostics.record(
                "miui_backup_apply_deeplink_unusable",
                "Temalar yerel kaynak sayfası bu sürümde açılamıyor",
            )
            return null
        }
        return LocalResourceDetailContract.detailIntent(localId)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
