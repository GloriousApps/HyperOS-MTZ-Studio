package dev.glorioustr.mtzstudio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import dev.glorioustr.mtzstudio.shevery.SheveryAccess
import dev.glorioustr.mtzstudio.shevery.SheveryAuthorizationStatus
import dev.glorioustr.mtzstudio.tester.AggregateThemesBak
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Blocking user-0 ADB capture of shell-accessible external Themes data only; call on an IO thread.
 * A PARTIAL result describes a converter-compatible BAK, not a full private-data backup or an
 * assurance that Android/Xiaomi will accept an OS restore of the archive.
 */
internal class RootlessThemesBakCapture(context: Context) {
    data class Result(val status: String, val themeCount: Int = 0)

    private val app = context.applicationContext
    private val shell = SheveryAccess(app)

    fun capture(destination: File): Result {
        if (Build.VERSION.SDK_INT < 29 || Process.myUid() / 100000 != 0 ||
            shell.status() != SheveryAuthorizationStatus.ADB_READY ||
            runCatching { Shizuku.getUid() }.getOrNull() != 2000) return Result("UNAVAILABLE")

        // The binder identity and the active Android user must both be confirmed before accessing
        // the shared tree. A failed/indeterminate probe is never evidence of an empty library.
        val identity = runCatching { shell.executeShell("id -u && am get-current-user", 10) }.getOrNull()
        if (identity == null || identity.exitCode != 0 ||
            identity.output.trim().lines().map(String::trim) != listOf("2000", "0")) {
            return Result("UNAVAILABLE")
        }
        val available = runCatching { shell.executeShell(PROBE, 10) }.getOrNull()
        if (available == null || available.exitCode != 0) return Result("UNAVAILABLE")
        val names = when (available.output.trim()) {
            "000" -> return Result("EMPTY")
            else -> available.output.trim().takeIf { it.matches(Regex("[01]{3}")) } ?: return Result("UNAVAILABLE")
        }

        val parent = destination.absoluteFile.parentFile
        if (destination.exists() || parent?.isDirectory != true) return Result("FAILED")
        val id = UUID.randomUUID().toString()
        val filename = "mtzstudio-themes-$id.tar"
        // Keep RELATIVE_PATH slash-free. Several MIUI MediaProvider builds reject or
        // normalize a trailing slash even though the public backup path is otherwise valid.
        val relative = "Download/MTZ Studio/Backups"
        val shared = "/sdcard/$relative/$filename"
        val raw = File(app.cacheDir, filename)
        val part = File(parent, "${destination.name}.$id.part")
        var row: Uri? = null
        var result = Result("FAILED")
        var published = false
        var cleaned = true
        var stage = "MEDIASTORE_INSERT"
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/x-tar")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                // The shell UID cannot open an app-pending MediaStore download.
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            row = requireNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            // Force the provider to materialize its own file; shell must only write this newly
            // created, unique row, never an existing user's Download.
            // The default provider mode is more compatible with OEM MediaProvider than an
            // explicit "w" mode. The row is already unique and is only used as a shell bridge.
            stage = "MEDIASTORE_MATERIALIZE"
            requireNotNull(app.contentResolver.openOutputStream(requireNotNull(row))).use { }
            val subtrees = listOf("meta", "content", "preview").filterIndexed { index, _ -> names[index] == '1' }
                .joinToString(" ") { ".data/$it" }
            // -f is measured in 512-byte blocks by Android's shell. No recursion into private
            // locations, no dereference of symlinks, and no binary data returned over Binder.
            val command = "[ \"\$(id -u)\" = 2000 ] && [ \"\$(am get-current-user)\" = 0 ] && " +
                "ulimit -f 2097152 && /system/bin/tar -cf '$shared' -C '$THEME_ROOT' $subtrees && /system/bin/sha256sum '$shared'"
            stage = "SHELL_ARCHIVE"
            val tar = shell.executeShell(command, 60)
            check(tar.exitCode == 0) { "Shell archive did not finish successfully" }
            stage = "SHELL_HASH"
            val shellHash = parseThemesArchiveHash(tar.output, shared)
            check(shellHash != null) { "Shell archive checksum unavailable" }

            stage = "READBACK"
            val digest = MessageDigest.getInstance("SHA-256")
            val deadline = SystemClock.elapsedRealtime() + COPY_DEADLINE_MS
            var bytes = 0L
            requireNotNull(app.contentResolver.openInputStream(requireNotNull(row))).use { input ->
                raw.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check(SystemClock.elapsedRealtime() < deadline) { "Archive copy timed out" }
                        val count = input.read(buffer)
                        check(SystemClock.elapsedRealtime() < deadline) { "Archive copy timed out" }
                        if (count < 0) break
                        bytes += count
                        check(bytes <= MAX_BYTES) { "Archive exceeds capture limit" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(bytes > 0 && bytes == raw.length() &&
                digest.digest().joinToString("") { "%02x".format(it) }.equals(shellHash, ignoreCase = true)) {
                "Shell archive changed or was truncated"
            }

            stage = "TAR_VALIDATE"
            val entries = AggregateThemesBak.inspect(raw)
            val rootEntries = entries.filter { it.path.startsWith(".data/meta/theme/") && it.path.endsWith(".mrm") }
            val roots = rootEntries.size
            if (roots == 0) {
                result = Result("EMPTY")
            } else {
                stage = "THEME_VALIDATE"
                validateRoots(raw, rootEntries, entries)
                stage = "BAK_WRITE"
                val device = MtzToBakConverter.deviceInfo(app)
                check(device.signatures.isNotEmpty()) { "Themes signing information unavailable" }
                val summary = AggregateThemesBak.write(raw, part, device.versionCode, device.sdk,
                    device.installer, device.signatures)
                check(summary.themeCount == roots && summary.themeCount > 0 && part.isFile && part.length() > 0) {
                    "Aggregate BAK is incomplete"
                }
                stage = "BAK_PUBLISH"
                check(!destination.exists() && part.renameTo(destination)) { "Could not publish aggregate BAK" }
                published = true
                result = Result("PARTIAL", summary.themeCount)
            }
        } catch (failure: Exception) {
            runCatching {
                LiveDiagnosticsRecorder.get(app).record(
                    "rootless_themes_capture_failed",
                    "Rootsuz Temalar içeriği yakalanamadı",
                    mapOf(
                        "stage" to stage,
                        "status" to "FAILED",
                        "androidSdk" to Build.VERSION.SDK_INT,
                    ),
                    failure,
                )
            }
            result = Result("FAILED")
        } finally {
            // The URI is only the row inserted above. Never delete another Download or an old BAK.
            row?.let { cleaned = runCatching { app.contentResolver.delete(it, null, null) == 1 }.getOrDefault(false) }
            cleaned = (!raw.exists() || raw.delete()) && cleaned
            cleaned = (!part.exists() || part.delete()) && cleaned
        }
        if (!cleaned) {
            if (published) destination.delete()
            return Result("FAILED")
        }
        return result
    }

    private fun validateRoots(snapshot: File, roots: List<AggregateThemesBak.Entry>, all: List<AggregateThemesBak.Entry>) {
        val content = all.associateBy { it.path }
        RandomAccessFile(snapshot, "r").use { input ->
            roots.forEach { entry ->
                check(ROOT_META.matches(entry.path) && entry.size in 1L..MAX_JSON_BYTES) { "Invalid root theme metadata" }
                val stem = entry.path.removePrefix(".data/meta/theme/").removeSuffix(".mrm")
                check(SAFE_NAME.matches(stem) && stem != "." && stem != "..") { "Invalid root theme ID" }
                val bytes = ByteArray(entry.size.toInt())
                input.seek(entry.offset)
                input.readFully(bytes)
                val record = JSONObject(String(bytes, Charsets.UTF_8))
                check(record.getString("localId") == stem && record.getString("resourceCode") == "theme") {
                    "Theme metadata identity does not match its path"
                }
                val children = record.getJSONArray("subResources")
                check(children.length() > 0) { "Theme has no referenced content" }
                for (i in 0 until children.length()) {
                    val child = children.getJSONObject(i)
                    val id = child.getString("localId")
                    val code = child.getString("resourceCode")
                    check(SAFE_NAME.matches(id) && id != "." && id != ".." &&
                        SAFE_NAME.matches(code) && code != "." && code != "..") { "Unsafe theme component" }
                    check(content[".data/content/$code/$id.mrc"]?.size?.let { it > 0 } == true) {
                        "Referenced theme content is missing or empty"
                    }
                }
            }
        }
    }

    private companion object {
        const val THEME_ROOT = "/sdcard/Android/data/com.android.thememanager/files/MIUI/theme"
        const val MAX_BYTES = 1024L * 1024 * 1024
        const val COPY_DEADLINE_MS = 90_000L
        const val MAX_JSON_BYTES = 1024L * 1024
        val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,128}")
        val ROOT_META = Regex("\\.data/meta/theme/[^/]+\\.mrm")
        // One bounded read-only probe: absence, inaccessible path, and an indeterminate shell
        // response must not be conflated with a verified empty Themes library.
        val PROBE = """
            [ "${'$'}(id -u)" = 2000 ] && [ "${'$'}(am get-current-user)" = 0 ] || exit 2
            base='$THEME_ROOT/.data'
            [ -d "${'$'}base" ] && [ ! -L "${'$'}base" ] && [ -r "${'$'}base" ] && [ -x "${'$'}base" ] || exit 2
            for name in meta content preview; do
              path="${'$'}base/${'$'}name"
              if [ -e "${'$'}path" ] || [ -L "${'$'}path" ]; then
                [ -d "${'$'}path" ] && [ ! -L "${'$'}path" ] && [ -r "${'$'}path" ] && [ -x "${'$'}path" ] || exit 2
                case "${'$'}name" in meta) m=1;; content) c=1;; preview) p=1;; esac
              fi
            done
            printf '%s%s%s' "${'$'}{m:-0}" "${'$'}{c:-0}" "${'$'}{p:-0}"
        """.trimIndent()
    }
}

internal fun parseThemesArchiveHash(output: String, sharedPath: String): String? {
    val pattern = Regex("^([a-fA-F0-9]{64})\\s+\\*?${Regex.escape(sharedPath)}\\s*$")
    return output.lineSequence().mapNotNull { line ->
        pattern.matchEntire(line.trim())?.groupValues?.get(1)
    }.firstOrNull()
}
