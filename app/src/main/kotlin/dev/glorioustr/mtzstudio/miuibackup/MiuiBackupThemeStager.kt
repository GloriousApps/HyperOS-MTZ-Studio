package dev.glorioustr.mtzstudio.miuibackup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import dev.glorioustr.mtzstudio.LiveDiagnosticsRecorder
import dev.glorioustr.mtzstudio.MtzToBakConverter
import dev.glorioustr.mtzstudio.core.Hashing
import dev.glorioustr.mtzstudio.library.LibraryTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Rootless import into the Xiaomi Themes catalogue through MIUI's own Backup app.
 *
 * Why this exists: a third-party app cannot register a theme in the Themes catalogue itself — the
 * catalogue lives in Themes' private database, and the only writer with the right privilege is the
 * system **MIUI Backup** app (`com.miui.backup`, `android.uid.backup`, signature|privileged).
 * MIUI Backup restores from plain shared storage (`/sdcard/MIUI/backup/AllBackup/`), which an app
 * holding `MANAGE_EXTERNAL_STORAGE` can write. So MTZ Studio converts the theme to a MIUI Backup
 * package, drops it there, and sends the user into MIUI Backup to confirm the restore — the system
 * app does the privileged work. Device-verified on HyperOS OS3.0.301.0 (unrooted): the restore
 * created a real catalogue record.
 *
 * This is import only. Applying the restored theme is a separate step (the Themes local-resource
 * deep link), and is still subject to Xiaomi's theme DRM for protected content — see
 * `docs/rootless-capability-device-test.md` §12. This class never touches that; it only stages a
 * package the user themselves restores.
 */
object MiuiBackupThemeStager {

    const val BACKUP_PACKAGE = "com.miui.backup"
    const val LOCAL_HOME_ACTION = "miui.intent.backup.LOCAL_HOME_ACTIVITY"
    const val LOCAL_HOME_COMPONENT = "com.miui.backup.local.LocalHomeActivity"
    private const val THEME_PACKAGE = "com.android.thememanager"

    /** The exact file name MIUI Backup expects inside a theme-package folder. */
    private const val BAK_FILE_NAME = "Themes(com.android.thememanager).bak"
    private const val ALL_BACKUP_RELATIVE = "MIUI/backup/AllBackup"

    data class StagedBackup(
        val folder: File,
        val bakFile: File,
        val descriptFile: File,
        /** The localId Themes will assign the restored theme; use it for the apply deep link. */
        val localId: String,
        val themeTitle: String,
    )

    enum class Availability {
        READY,
        /** `com.miui.backup` is missing (not a HyperOS/MIUI device, or it was removed). */
        BACKUP_APP_MISSING,
        /** All-files access has not been granted yet; call [allFilesAccessSettingsIntent]. */
        NEEDS_ALL_FILES_ACCESS,
    }

    fun availability(context: Context): Availability = when {
        !isBackupAppPresent(context) -> Availability.BACKUP_APP_MISSING
        !hasAllFilesAccess() -> Availability.NEEDS_ALL_FILES_ACCESS
        else -> Availability.READY
    }

    fun isBackupAppPresent(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(BACKUP_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * Settings screen where the user grants all-files access to this app. Only shown when the user
     * has chosen the MIUI Backup import path; never requested at launch.
     */
    fun allFilesAccessSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    /**
     * Converts [theme] to a MIUI Backup package and writes it into `AllBackup/<timestamp>/`.
     * Requires [Availability.READY]. Returns the staged package and the localId the restore will
     * assign, for the follow-up apply deep link.
     */
    fun stage(context: Context, theme: LibraryTheme): StagedBackup {
        val diagnostics = LiveDiagnosticsRecorder.get(context.applicationContext)
        check(hasAllFilesAccess()) { "Tüm dosyalara erişim izni verilmemiş" }
        check(isBackupAppPresent(context)) { "MIUI Yedekleme uygulaması bulunamadı" }
        check(Hashing.sha256(theme.archive.source) == theme.archive.sha256) {
            "Tema kaynağı doğrulama sonrası değişmiş"
        }

        val source = theme.archive.source.toFile()
        val localId = MtzToBakConverter.restoredThemeLocalId(source)
        val title = theme.archive.metadata?.name ?: theme.displayName

        // Convert into private cache first, then move the finished package into shared storage, so a
        // half-written .bak is never visible to MIUI Backup.
        val staged = File(context.cacheDir, "miui-backup-stage-${UUID.randomUUID()}.bak")
        val backupRoot = File(Environment.getExternalStorageDirectory(), ALL_BACKUP_RELATIVE)
        val folder = File(backupRoot, timestampFolderName())
        try {
            MtzToBakConverter.convert(source, staged, MtzToBakConverter.deviceInfo(context))
            check(staged.isFile && staged.length() > 0L) { "MIUI Yedekleme paketi oluşturulamadı" }

            check(folder.mkdirs() || folder.isDirectory) {
                "MIUI Yedekleme klasörü oluşturulamadı: ${folder.absolutePath}"
            }
            val bakFile = File(folder, BAK_FILE_NAME)
            staged.copyTo(bakFile, overwrite = true)
            val size = bakFile.length()
            val descriptFile = File(folder, "descript.xml")
            descriptFile.writeText(buildDescript(size, deviceDescriptor()), Charsets.UTF_8)

            diagnostics.record(
                "miui_backup_staged",
                "Tema MIUI Yedekleme paketine dönüştürülüp AllBackup klasörüne yazıldı",
                mapOf(
                    "theme" to title,
                    "localId" to localId,
                    "folder" to folder.name,
                    "bytes" to size,
                ),
            )
            return StagedBackup(folder, bakFile, descriptFile, localId, title)
        } catch (error: Throwable) {
            runCatching { folder.deleteRecursively() }
            diagnostics.record("miui_backup_stage_failed", "MIUI Yedekleme paketi hazırlanamadı", error = error)
            throw error
        } finally {
            staged.delete()
        }
    }

    /** Opens MIUI Backup's local backup/restore home so the user can run the restore. */
    fun restoreIntent(): Intent = Intent(LOCAL_HOME_ACTION).apply {
        component = ComponentName(BACKUP_PACKAGE, LOCAL_HOME_COMPONENT)
        addCategory(Intent.CATEGORY_DEFAULT)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Resolves [restoreIntent] against the installed MIUI Backup, falling back to the action. */
    fun resolvableRestoreIntent(context: Context): Intent? {
        val explicit = restoreIntent()
        if (explicit.resolveActivity(context.packageManager) != null) return explicit
        val implicit = Intent(LOCAL_HOME_ACTION).apply {
            `package` = BACKUP_PACKAGE
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return implicit.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    /**
     * Removes packages this app previously staged under `AllBackup`, older than [keepMillis}. MIUI
     * Backup keeps restoring from these folders forever otherwise, cluttering the user's restore
     * list. Only folders whose descript.xml names the Themes package and carries our marker are
     * touched; the user's own backups are never removed.
     */
    fun pruneStaged(context: Context, keepMillis: Long = 24L * 60 * 60 * 1000) {
        if (!hasAllFilesAccess()) return
        val backupRoot = File(Environment.getExternalStorageDirectory(), ALL_BACKUP_RELATIVE)
        val now = System.currentTimeMillis()
        backupRoot.listFiles()?.forEach { folder ->
            if (!folder.isDirectory) return@forEach
            val descript = File(folder, "descript.xml")
            val marker = runCatching { descript.readText() }.getOrDefault("")
            if (!marker.contains(STAGER_MARKER)) return@forEach
            if (now - folder.lastModified() < keepMillis) return@forEach
            runCatching { folder.deleteRecursively() }
        }
    }

    private fun timestampFolderName(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** The device-identifying fields MIUI Backup records in descript.xml. */
    data class DeviceDescriptor(
        val device: String,
        val miuiVersion: String,
        val date: Long,
        val storageLeft: Long,
    )

    private fun deviceDescriptor(): DeviceDescriptor = DeviceDescriptor(
        device = Build.DEVICE,
        miuiVersion = systemProperty("ro.mi.os.version.incremental")
            ?: systemProperty("ro.build.version.incremental")
            ?: Build.VERSION.INCREMENTAL,
        date = System.currentTimeMillis(),
        storageLeft = runCatching { Environment.getExternalStorageDirectory().usableSpace }.getOrDefault(0L),
    )

    /**
     * The descript.xml MIUI Backup reads to list and restore a package. The size fields all carry
     * the real `.bak` length; [STAGER_MARKER] is an inert comment so [pruneStaged] can recognise
     * our own folders without disturbing the user's backups. Pure, so it is unit-tested directly.
     */
    internal fun buildDescript(size: Long, device: DeviceDescriptor): String {
        return buildString {
            append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>")
            append("<!-- $STAGER_MARKER -->")
            append("<MIUI-backup>")
            append("<jsonMsg></jsonMsg>")
            append("<bakVersion>2</bakVersion>")
            append("<brState>3</brState>")
            append("<autoBackup>false</autoBackup>")
            append("<device>").append(xml(device.device)).append("</device>")
            append("<miuiVersion>").append(xml(device.miuiVersion)).append("</miuiVersion>")
            append("<date>").append(device.date).append("</date>")
            append("<size>").append(size).append("</size>")
            append("<storageLeft>").append(device.storageLeft).append("</storageLeft>")
            append("<supportReconnect>true</supportReconnect>")
            append("<autoRetransferCnt>0</autoRetransferCnt>")
            append("<transRealCompletedSize>0</transRealCompletedSize>")
            append("<packages><package>")
            append("<packageName>").append(THEME_PACKAGE).append("</packageName>")
            append("<feature>-1</feature>")
            append("<bakFile>").append(xml(BAK_FILE_NAME)).append("</bakFile>")
            append("<bakType>1</bakType>")
            append("<pkgSize>").append(size).append("</pkgSize>")
            append("<sdSize>0</sdSize>")
            append("<state>1</state>")
            append("<completedSize>").append(size).append("</completedSize>")
            append("<error>0</error>")
            append("<progType>0</progType>")
            append("<bakFileSize>").append(size).append("</bakFileSize>")
            append("<transingCompletedSize>0</transingCompletedSize>")
            append("<transingTotalSize>").append(size).append("</transingTotalSize>")
            append("<transingSdCompletedSize>0</transingSdCompletedSize>")
            append("<sectionSize>0</sectionSize>")
            append("<sendingIndex>0</sendingIndex>")
            append("</package></packages>")
            append("<filesModifyTime />")
            append("</MIUI-backup>")
        }
    }

    private fun systemProperty(key: String): String? = runCatching {
        @Suppress("PrivateApi")
        val systemProperties = Class.forName("android.os.SystemProperties")
        val get = systemProperties.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private const val STAGER_MARKER = "mtzstudio-miui-backup-stage"
}
