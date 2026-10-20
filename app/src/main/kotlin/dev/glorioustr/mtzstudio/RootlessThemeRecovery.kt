package dev.glorioustr.mtzstudio

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.provider.MediaStore
import dev.glorioustr.mtzstudio.library.StudioBackupManager
import dev.glorioustr.mtzstudio.shevery.SheveryAccess
import dev.glorioustr.mtzstudio.shevery.SheveryAuthorizationStatus
import dev.glorioustr.mtzstudio.tester.RecoveryFile
import dev.glorioustr.mtzstudio.tester.RecoveryIdentity
import dev.glorioustr.mtzstudio.tester.RecoveryJournal
import dev.glorioustr.mtzstudio.tester.PublicBackupReceipt
import dev.glorioustr.mtzstudio.tester.PublicRecoveryFile
import dev.glorioustr.mtzstudio.tester.RootlessRecoveryCommands
import dev.glorioustr.mtzstudio.tester.RootlessRecoveryGateway
import dev.glorioustr.mtzstudio.tester.RootlessRecoveryFailure
import dev.glorioustr.mtzstudio.tester.RootlessRecoveryPolicy
import dev.glorioustr.mtzstudio.tester.RootlessRecoveryStore
import dev.glorioustr.mtzstudio.tester.ThemeManagerCapabilityProbe
import dev.glorioustr.mtzstudio.tester.ThemeManagerContract
import dev.glorioustr.mtzstudio.tester.ThemeManagerInspector
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Blocking experimental user-0 ADB flow. UI must call from an IO dispatcher. */
internal class RootlessThemeRecovery(context: Context) {
    data class Snapshot(
        val phase: String,
        val message: String,
        val backupPath: String?,
        val canInstall: Boolean,
        val needsRecovery: Boolean,
        val publicBackupPath: String? = null,
        val themesBackupStatus: String = "UNAVAILABLE",
        val themeCount: Int = 0,
    )

    private val app = context.applicationContext
    private val store = JournalStore(app)
    private val policy = RootlessRecoveryPolicy(store, DeviceGateway(app))

    fun snapshot(): Snapshot = synchronized(lock) { policy.snapshot().toSnapshot() }
    fun prepare(): Snapshot = synchronized(lock) { record(policy.prepare()) }
    fun install(dataLossAccepted: Boolean): Snapshot = synchronized(lock) { record(policy.install(dataLossAccepted)) }
    fun recoverExisting(): Snapshot = synchronized(lock) { record(policy.recoverExisting()) }

    private fun record(journal: RecoveryJournal): Snapshot {
        // Only controlled status text and version metadata; never private backup content or dumps.
        runCatching {
            LiveDiagnosticsRecorder.get(app).record("rootless_recovery_state", journal.message,
                mapOf("phase" to journal.phase, "baseline" to journal.baseline?.version, "target" to journal.target?.version))
        }
        return journal.toSnapshot()
    }

    private fun RecoveryJournal.toSnapshot() = Snapshot(
        phase, message, backupPath,
        canInstall = phase == "PREPARED" && publicBackup?.isComplete() == true,
        needsRecovery = phase in setOf("UNINSTALL_PENDING", "ABSENT", "INSTALL_PENDING", "RECOVERY_PENDING", "RECOVERY_NEEDED"),
        publicBackupPath = publicBackup?.takeIf { it.isComplete() }?.path,
        themesBackupStatus = publicBackup?.themesBackupStatus ?: "UNAVAILABLE",
        themeCount = publicBackup?.themeCount ?: 0,
    )

    private class JournalStore(context: Context) : RootlessRecoveryStore {
        private val prefs = context.getSharedPreferences("rootless-theme-recovery", Context.MODE_PRIVATE)
        private val empty = RecoveryJournal("IDLE", "Prepare to verify the device and preserve backups. Themes private data cannot be backed up.", null, null, null, emptyList())

        override fun load(): RecoveryJournal {
            return try {
                val raw = prefs.getString("journal", null)
                if (raw == null) empty else {
                    val json = JSONObject(raw)
                    fun identity(name: String): RecoveryIdentity? = json.optJSONObject(name)?.let {
                        RecoveryIdentity(it.getString("version"), it.getLong("code"), it.getJSONArray("certs").strings().toSet())
                    }
                    RecoveryJournal(
                        json.getString("phase"), json.getString("message"), json.optString("backup").takeIf(String::isNotBlank),
                        identity("baseline"), identity("target"), json.getJSONArray("files").let { array ->
                            (0 until array.length()).map { i ->
                                val file = array.getJSONObject(i)
                                RecoveryFile(file.getString("path"), file.getString("sha"), file.getString("source"))
                            }
                        },
                        json.optJSONObject("publicBackup")?.let { published ->
                            PublicBackupReceipt(published.getString("path"),
                                published.getJSONArray("files").let { entries -> (0 until entries.length()).map { i ->
                                    entries.getJSONObject(i).let { file ->
                                        PublicRecoveryFile(file.getString("name"), file.getString("uri"), file.getString("sha"), file.getLong("size"))
                                    }
                                } }, published.optString("themesBackupStatus", "UNAVAILABLE"),
                                published.optInt("themeCount", 0), published.optInt("schemaVersion", 1))
                        },
                    )
                }
            } catch (_: Exception) {
                // A corrupt journal must never silently turn an interrupted uninstall into a new preparation.
                empty.copy(phase = "RECOVERY_NEEDED", message = "Recovery journal unreadable. Inspect Themes and use explicit recovery; no automatic uninstall is allowed.")
            }
        }

        override fun save(journal: RecoveryJournal) {
            fun identity(value: RecoveryIdentity?): JSONObject? = value?.let {
                JSONObject().put("version", it.version).put("code", it.code).put("certs", JSONArray(it.certificates.toList()))
            }
            val json = JSONObject().put("phase", journal.phase).put("message", journal.message)
                .put("backup", journal.backupPath ?: "").put("baseline", identity(journal.baseline))
                .put("target", identity(journal.target)).put("files", JSONArray().apply {
                    journal.files.forEach { put(JSONObject().put("path", it.path).put("sha", it.sha256).put("source", it.sourcePath)) }
                })
            journal.publicBackup?.let { receipt ->
                json.put("publicBackup", JSONObject().put("path", receipt.path)
                    .put("themesBackupStatus", receipt.themesBackupStatus)
                    .put("themeCount", receipt.themeCount).put("schemaVersion", receipt.schemaVersion)
                    .put("files", JSONArray().apply { receipt.files.forEach { file ->
                        put(JSONObject().put("name", file.name).put("uri", file.uri)
                            .put("sha", file.sha256).put("size", file.size))
                    } }))
            }
            check(prefs.edit().putString("journal", json.toString()).commit()) { "Could not persist recovery journal" }
        }

        private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
    }

    private class DeviceGateway(private val context: Context) : RootlessRecoveryGateway {
        private val shell = SheveryAccess(context)
        private val inspector = ThemeManagerInspector(context)
        private val root get() = File(context.filesDir, "rootless-recovery")
        private val target get() = File(root, APK_NAME)

        override fun requireReady() {
            need(BuildConfig.VERSION_NAME.contains("rootless-test"), "TEST_BUILD_REQUIRED")
            need(Build.VERSION.SDK_INT >= 29 && Process.myUid() / 100000 == 0, "ANDROID_10_USER_0_REQUIRED")
            need(shell.status() == SheveryAuthorizationStatus.ADB_READY && Shizuku.getUid() == 2000, "ADB_SERVICE_PERMISSION_REQUIRED")
            need(read("id -u") == "2000" && read("am get-current-user") == "0", "FOREGROUND_USER_0_REQUIRED")
            // APK code is device-global even when package availability is per user. Do not
            // change a Themes installation used by Second Space/work/clone profiles in this test.
            val users = Regex("UserInfo\\{([0-9]+):").findAll(read("pm list users"))
                .map { it.groupValues[1].toInt() }.toList()
            need(0 in users, "USER_LIST_UNAVAILABLE")
            users.filter { it != 0 }.forEach { user ->
                val packages = read("pm list packages --user $user ${RootlessRecoveryCommands.PACKAGE}")
                need(packages.lines().none { it.trim() == "package:${RootlessRecoveryCommands.PACKAGE}" }, "THEMES_USED_BY_ANOTHER_USER")
            }
        }

        override fun installed(): RecoveryIdentity? {
            val info = try { packageInfo(ThemeManagerContract.PACKAGE_NAME) }
            catch (_: PackageManager.NameNotFoundException) { return null }
            return info.identity()
        }

        override fun verifiedTarget(baseline: RecoveryIdentity): RecoveryIdentity {
            need(ThemeManagerContract.canonicalVersion(baseline.version) == "3.4.1.23", "THEMES_3_4_1_23_REQUIRED")
            check(ThemeManagerContract.familyOf(baseline.version) == dev.glorioustr.mtzstudio.tester.ThemeManagerFamily.GLOBAL)
            need(packageInfo(ThemeManagerContract.PACKAGE_NAME).applicationInfo?.let {
                it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
            } == true, "PREINSTALLED_SYSTEM_THEMES_REQUIRED")
            val current = requireNotNull(installed())
            check(current == baseline)
            download()
            return validateTarget(baseline)
        }

        private fun validateTarget(baseline: RecoveryIdentity): RecoveryIdentity {
            check(target.isFile && digest(target) == APK_SHA) { "Target checksum changed" }
            val info = archive(target)
            check(info.packageName == ThemeManagerContract.PACKAGE_NAME &&
                info.versionName == "3.0.5.6-global" && info.longVersionCode == 3000506L &&
                info.splitNames.isNullOrEmpty() &&
                info.applicationInfo != null && info.applicationInfo!!.minSdkVersion <= Build.VERSION.SDK_INT &&
                info.applicationInfo!!.minSdkVersion >= 27) { "Unexpected target APK" }
            val identity = info.identity()
            check(identity.certificates == baseline.certificates) { "APK signer differs from installed Themes" }
            return identity
        }

        override fun backup(baseline: RecoveryIdentity): Pair<String, List<RecoveryFile>> {
            val sources = livePaths()
            check(sources.isNotEmpty() && sources.all { it.isFile && it.canRead() }) { "Installed APK/splits cannot be read" }
            check(archive(sources.first()).identity() == baseline) { "Live APK baseline changed" }
            val bytes = sources.sumOf(File::length) + target.length()
            check(free(root) > bytes * 3 && free(context.cacheDir) > bytes * 2) { "Insufficient space for backup" }
            val folder = File(root, UUID.randomUUID().toString())
            check(folder.mkdirs())
            val library = File(folder, "studio-library.zip")
            library.outputStream().use { StudioBackupManager(context).create(it) }
            verifyZip(library)
            val copies = sources.mapIndexed { index, source ->
                val copy = File(folder, if (index == 0) "base.apk" else "split-$index.apk")
                source.inputStream().use { input -> copy.outputStream().use(input::copyTo) }
                val hash = digest(source)
                check(digest(copy) == hash && copy.length() == source.length()) { "APK backup verification failed" }
                RecoveryFile(copy.absolutePath, hash, source.absolutePath)
            }
            check(copies.size == sources.size)
            // One archive for every readable external theme, not one BAK per theme. A denied
            // shell read is reported as unavailable and is never replaced by a fake empty BAK.
            val themes = RootlessThemesBakCapture(context).capture(File(folder, "themes.bak"))
            val manifest = Properties().apply {
                setProperty("studioSha256", digest(library))
                setProperty("studioLibraryFile", "studio-library.zip")
                setProperty("originalApkArchive", "themes-apks.zip")
                setProperty("originalApkCount", copies.size.toString())
                setProperty("originalVersion", baseline.version)
                setProperty("originalVersionCode", baseline.code.toString())
                setProperty("targetVersion", "3.0.5.6-global")
                setProperty("themesPrivateDataBackedUp", "false")
                setProperty("themesContentStatus", themes.status)
                setProperty("themesContentCount", themes.themeCount.toString())
                if (themes.status == "PARTIAL") {
                    setProperty("themesContentFile", "themes.bak")
                    setProperty("themesContentSha256", digest(File(folder, "themes.bak")))
                }
                copies.forEachIndexed { i, copy ->
                    setProperty("apk.$i.name", File(copy.path).name)
                    setProperty("apk.$i.sha256", copy.sha256)
                    setProperty("apk.$i.size", File(copy.path).length().toString())
                }
            }
            File(folder, "backup.properties").outputStream().use { manifest.store(it, "Experimental recovery backup; NOT Themes private data") }
            return folder.absolutePath to copies
        }

        override fun publishPublic(backupPath: String, files: List<RecoveryFile>): PublicBackupReceipt {
            val folder = File(backupPath)
            val archive = File(folder, "themes-apks.zip")
            val readme = File(folder, "README.txt")
            val resolver = context.contentResolver
            val created = mutableListOf<Uri>()
            val manifest = Properties().apply { File(folder, "backup.properties").inputStream().use(::load) }
            val themesStatus = manifest.getProperty("themesContentStatus", "UNAVAILABLE")
            val themeCount = manifest.getProperty("themesContentCount", "0").toInt()
            var publicStage = "BACKUP_PUBLIC_ARCHIVE"
            // A fresh directory per operation, never a fixed filename that could replace an earlier backup.
            // Some MIUI MediaProvider builds reject a trailing slash in RELATIVE_PATH even though
            // AOSP normalizes it. Keep the provider value slash-free and retain a slash only in
            // the receipt path used to identify the backup directory.
            val relative = "Download/MTZ Studio/Backups/${System.currentTimeMillis()}-${UUID.randomUUID()}"
            val receiptPath = "$relative/"
            try {
                ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                    (files.map { File(it.path) } + File(folder, "backup.properties")).forEach { source ->
                        check(source.isFile && source.canRead()) { "Backup source missing" }
                        zip.putNextEntry(ZipEntry(source.name))
                        source.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                verifyZip(archive)
                publicStage = "BACKUP_PUBLIC_README"
                readme.writeText("""HyperOS MTZ Studio experimental recovery backup
studio-library.zip is the Studio library backup for StudioBackupManager restore.
themes-apks.zip contains the original installed base APK and every installed split APK,
plus backup.properties identifying hashes, sizes and original version.
These APKs are retained for manual recovery, not automatic restoration of Themes data.
Themes content capture status: $themesStatus. External theme records: $themeCount.
If present, themes.bak contains ALL validated readable external theme records and
their components in one MIUI Backup v2 archive, usable with the Studio BAK Converter.
It is a PARTIAL external-content backup, not a full application or private-data backup.
OS restore, cross-version restoration and restoration after uninstall are NOT verified.
Uninstalling Themes can erase themes, accounts, settings, previews and other private data.
Neither this archive nor the Studio library is a full Xiaomi Themes BAK or a
restorable snapshot of Themes private data.
                """)
                publicStage = "BACKUP_PUBLIC_MEDIASTORE"
                val sources = (listOf("studio-library.zip", "themes-apks.zip", "backup.properties", "README.txt") +
                    if (themesStatus == "PARTIAL") listOf("themes.bak") else emptyList())
                    .map { File(folder, it) }
                val published = sources.map { source ->
                    check(source.isFile && source.length() > 0) { "Public backup source empty" }
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, when (source.extension) {
                            "zip" -> "application/zip"
                            "bak" -> "application/octet-stream"
                            "properties" -> "text/plain"
                            else -> "text/plain"
                        })
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = try {
                        requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
                            "Public backup insert failed"
                        }
                    } catch (_: Exception) {
                        throw RootlessRecoveryFailure("BACKUP_PUBLIC_MEDIASTORE_INSERT")
                    }
                    created += uri
                    try {
                        // Use the provider's default write mode; it is more compatible with OEM
                        // MediaProvider implementations than explicitly passing "w".
                        requireNotNull(resolver.openOutputStream(uri)) { "Public backup output unavailable" }.use { output ->
                            source.inputStream().use { it.copyTo(output) }
                        }
                    } catch (_: Exception) {
                        throw RootlessRecoveryFailure("BACKUP_PUBLIC_MEDIASTORE_WRITE")
                    }
                    val receipt = PublicRecoveryFile(source.name, uri.toString(), digest(source), source.length())
                    // HyperOS 3's MediaProvider can accept writes to an IS_PENDING row but
                    // reject an immediate read from that same row. Do not weaken verification;
                    // defer the complete metadata/hash/size readback until after publication.
                    receipt
                }
                publicStage = "BACKUP_PUBLIC_PUBLISH"
                check(created.all { resolver.update(it, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1 }) {
                    "Public backup publication failed"
                }
                publicStage = "BACKUP_PUBLIC_RECHECK"
                val receipt = PublicBackupReceipt(receiptPath, published, themesStatus, themeCount)
                try {
                    recheckPublic(receipt)
                } catch (_: Exception) {
                    throw RootlessRecoveryFailure("BACKUP_PUBLIC_MEDIASTORE_VERIFY")
                }
                return receipt
            } catch (error: Exception) {
                created.forEach { runCatching { resolver.delete(it, null, null) } }
                if (error is RootlessRecoveryFailure) throw error
                throw RootlessRecoveryFailure(publicStage)
            } finally {
                archive.delete()
                readme.delete()
            }
        }

        override fun recheckPublic(receipt: PublicBackupReceipt) {
            check(receipt.isComplete() && receipt.path.startsWith("Download/MTZ Studio/Backups/") &&
                receipt.path.endsWith('/') && receipt.path.removeSuffix("/").removePrefix("Download/MTZ Studio/Backups/")
                    .matches(Regex("[0-9]{10,}-[a-f0-9-]{36}"))) { "Public backup receipt incomplete" }
            receipt.files.forEach { verifyPublicFile(it, receipt.path, expectedPending = false) }
        }

        override fun cleanupPublic(receipt: PublicBackupReceipt) {
            receipt.files.forEach { file ->
                runCatching { context.contentResolver.delete(Uri.parse(file.uri), null, null) }
            }
        }

        private fun verifyPublicFile(file: PublicRecoveryFile, relative: String, expectedPending: Boolean) {
            val uri = Uri.parse(file.uri)
            check(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY &&
                uri.path?.matches(Regex("/external(?:_primary)?/downloads/[0-9]+")) == true) { "Unexpected public backup URI" }
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING), null, null, null).use { cursor ->
                check(cursor != null && cursor.moveToFirst() && cursor.count == 1) { "Public backup row missing" }
                check(cursor.getString(0) == file.name &&
                    cursor.getString(1).trimEnd('/') == relative.trimEnd('/') &&
                    cursor.getInt(2) == (if (expectedPending) 1 else 0)) { "Public backup destination changed" }
            }
            val stream = requireNotNull(context.contentResolver.openInputStream(uri)) { "Public backup unreadable" }
            val (sha, size) = stream.use(::digestAndSize)
            check(sha == file.sha256 && size == file.size && size > 0) { "Public backup checksum changed" }
        }

        override fun factoryObservation(): String = runCatching {
            // PackageManager may report the immutable ROM source for an updated system app.
            // It is only a read-only hint, never a promise that install-existing restores it.
            val path = packageInfo(ThemeManagerContract.PACKAGE_NAME).applicationInfo?.sourceDir.orEmpty()
            val isRom = listOf("/system/", "/product/", "/system_ext/").any(path::startsWith)
            if (isRom && File(path).canRead()) {
                "ROM source metadata: ${archive(File(path)).versionName ?: "unknown"}; recovery version is not guaranteed."
            } else "Factory metadata unavailable; install-existing does not guarantee factory version."
        }.getOrDefault("Factory metadata unavailable; install-existing does not guarantee factory version.")

        override fun recheck(journal: RecoveryJournal) {
            check(installed() == journal.baseline && validateTarget(requireNotNull(journal.baseline)) == journal.target) { "Installed or target APK changed" }
            val sources = livePaths()
            check(sources.size == journal.files.size)
            journal.files.zip(sources).forEach { (saved, source) ->
                check(source.absolutePath == saved.sourcePath && digest(source) == saved.sha256 &&
                    digest(File(saved.path)) == saved.sha256) { "APK backup or baseline changed" }
            }
            val folder = File(requireNotNull(journal.backupPath))
            val manifest = Properties().apply { File(folder, "backup.properties").inputStream().use(::load) }
            val library = File(folder, "studio-library.zip")
            need(library.isFile && digest(library) == manifest.getProperty("studioSha256"), "STUDIO_BACKUP_CHANGED")
            verifyZip(library)
            check(free(root) > target.length() * 3) { "Insufficient installation space" }
        }

        override fun stage(): String {
            // MediaStore owns a shell-readable Downloads copy; never assume the ADB service can
            // access filesDir. Copy into a unique shell-owned /data/local/tmp path and hash again.
            val id = UUID.randomUUID().toString()
            val name = "mtzstudio-rootless-$id.apk"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MTZStudioRootless")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            try {
                resolver.openOutputStream(uri, "w")!!.use { output -> target.inputStream().use { it.copyTo(output) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                val shared = "/sdcard/Download/MTZStudioRootless/$name"
                val staged = "/data/local/tmp/$name"
                check(read("sha256sum '$shared'").substringBefore(' ').equals(APK_SHA, true)) { "Shared APK not readable by shell" }
                check(command("/system/bin/cp '$shared' '$staged' && /system/bin/chmod 0644 '$staged'"))
                check(read("sha256sum '$staged'").substringBefore(' ').equals(APK_SHA, true)) { "Staged APK checksum changed" }
                return staged
            } finally {
                resolver.delete(uri, null, null) // only after staging; never delete the installer before its copy exists
            }
        }

        override fun command(command: String): Boolean {
            requireReady()
            val result = shell.executeShell(command, 60)
            if (result.exitCode != 0) {
                val code = Regex("(?:INSTALL_FAILED_[A-Z0-9_]+|DELETE_FAILED_[A-Z0-9_]+|INSTALL_PARSE_FAILED_[A-Z0-9_]+)")
                    .find(result.output)?.value ?: "PACKAGE_COMMAND_FAILED"
                throw RootlessRecoveryFailure(code)
            }
            return true
        }

        override fun cleanupStaged(staged: String) {
            // Only after confirmed install; leave the APK in place on an uncertain timeout.
            RootlessRecoveryCommands.install(staged) // validate the fixed namespace again
            shell.executeShell("/system/bin/rm -f '$staged'", 10)
        }

        override fun legacyResolvable(): Boolean {
            val installed = inspector.inspect()
            return installed.installed && ThemeManagerCapabilityProbe(context).probe(installed).legacyTesterResolvable
        }

        private fun read(command: String): String {
            val result = shell.executeShell(command, 20)
            check(result.exitCode == 0) { "ADB query failed" }
            return result.output.trim()
        }

        private fun need(condition: Boolean, code: String) {
            if (!condition) throw RootlessRecoveryFailure(code)
        }

        private fun verifyZip(file: File) {
            ZipFile(file).use { zip ->
                check(zip.getEntry("backup.properties") != null) { "Studio backup manifest missing" }
                val buffer = ByteArray(64 * 1024)
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    val crc = java.util.zip.CRC32()
                    var bytes = 0L
                    zip.getInputStream(entry).use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            bytes += n
                            crc.update(buffer, 0, n)
                        }
                    }
                    check(bytes == entry.size && crc.value == entry.crc) { "Studio backup CRC mismatch" }
                }
            }
        }

        private fun livePaths(): List<File> {
            val lines = read("/system/bin/pm path --user 0 ${RootlessRecoveryCommands.PACKAGE}").lines()
            val paths = lines.map { line ->
                check(line.startsWith("package:")) { "Unexpected package paths" }
                line.removePrefix("package:").also {
                    check(it.matches(Regex("/[a-zA-Z0-9_./+=@~-]+")) && it.split('/').none { part -> part == ".." })
                }
            }
            check(paths.isNotEmpty() && paths.size == paths.distinct().size)
            return paths.map(::File)
        }

        private fun packageInfo(name: String): PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
        }

        private fun archive(file: File): PackageInfo = requireNotNull(if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }) { "APK archive unreadable" }

        private fun PackageInfo.identity(): RecoveryIdentity {
            val signatures = requireNotNull(signingInfo).let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
            val certs = signatures.mapTo(linkedSetOf()) { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).hex()
            }
            return RecoveryIdentity(requireNotNull(versionName), longVersionCode, certs)
        }

        private fun download() {
            root.mkdirs()
            if (target.isFile && digest(target) == APK_SHA) return
            val temporary = File(root, "download-${UUID.randomUUID()}.apk")
            val connection = (URL("https://raw.githubusercontent.com/GloriousApps/HyperOS-MTZ-Studio/main/requirements/$APK_NAME").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
            }
            try {
                check(connection.responseCode == 200) { "Download unavailable" }
                val deadline = System.currentTimeMillis() + 90_000
                var size = 0L
                connection.inputStream.use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        check(System.currentTimeMillis() < deadline) { "Download timed out" }
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        check(size <= 150L * 1024 * 1024) { "APK too large" }
                        output.write(buffer, 0, count)
                    }
                } }
                check(digest(temporary) == APK_SHA) { "APK checksum mismatch" }
                check(temporary.renameTo(target)) { "Could not stage verified target" }
            } finally { connection.disconnect(); temporary.delete() }
        }

        private fun free(file: File): Long = StatFs(file.absolutePath).availableBytes
        private fun digest(file: File): String {
            return file.inputStream().use { digestAndSize(it).first }
        }
        private fun digestAndSize(input: InputStream): Pair<String, Long> {
            val md = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n); size += n }
            return md.digest().hex() to size
        }
        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val lock = Any()
        const val APK_NAME = "Xiaomi_Themes_3.0.5.6-global.apk"
        const val APK_SHA = "24b99f995bf5f8509e591bdb1d36ce6f95260ec95648d13f9ddedc4e7d8edceb"
    }
}
