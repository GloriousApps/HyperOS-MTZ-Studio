package dev.glorioustr.mtzstudio.tester

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Restores only verified external files. Existing different files are never overwritten. */
object ThemesContentRestore {
    const val ROOT = "/sdcard/Android/data/com.android.thememanager/files/MIUI/theme"

    fun checksums(backup: File, archive: AggregateThemesBak.RestoreArchive): String =
        RandomAccessFile(backup, "r").use { input ->
            buildString {
                // Publish metadata last, after components/previews are available.
                archive.files.sortedBy { it.path.startsWith(".data/meta/") }.forEach { entry ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    input.seek(entry.offset)
                    var remaining = entry.size
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        check(count > 0) { "Truncated backup content" }
                        digest.update(buffer, 0, count)
                        remaining -= count
                    }
                    append(digest.digest().joinToString("") { "%02x".format(it) })
                    append("  ").append(entry.path).append('\n')
                }
            }
        }

    fun command(id: String, tarOffset: Long, backupHash: String, checksHash: String): String {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        require(tarOffset in 1..4096)
        require(listOf(backupHash, checksHash).all { it.matches(Regex("[a-f0-9]{64}")) })
        val bridge = "/sdcard/Download/MTZStudioRootless/mtzstudio-restore-$id"
        return """
            set -eu
            stage=preflight
            trap 'rc=${'$'}?; if [ "${'$'}rc" -ne 0 ]; then echo "MTZ_RESTORE_FAILED stage=${'$'}stage"; fi' EXIT
            [ "${'$'}(id -u)" = 2000 ] && [ "${'$'}(am get-current-user)" = 0 ]
            root='$ROOT'
            work="${'$'}root/.mtzstudio-restore-$id"
            bak='$bridge.bak'
            checks='$bridge.txt'
            actual=${'$'}(sha256sum "${'$'}bak")
            [ "${'$'}{actual%% *}" = '$backupHash' ]
            actual=${'$'}(sha256sum "${'$'}checks")
            [ "${'$'}{actual%% *}" = '$checksHash' ]
            for dir in /sdcard/Android /sdcard/Android/data /sdcard/Android/data/com.android.thememanager /sdcard/Android/data/com.android.thememanager/files /sdcard/Android/data/com.android.thememanager/files/MIUI "${'$'}root"; do
                [ ! -L "${'$'}dir" ]
                if [ -e "${'$'}dir" ]; then [ -d "${'$'}dir" ]; fi
            done
            mkdir -p "${'$'}root"
            mkdir "${'$'}work"
            mkdir "${'$'}work/payload"
            cp "${'$'}checks" "${'$'}work/checks.sha256"
            actual=${'$'}(sha256sum "${'$'}work/checks.sha256")
            [ "${'$'}{actual%% *}" = '$checksHash' ]
            cp "${'$'}bak" "${'$'}work/source.bak"
            actual=${'$'}(sha256sum "${'$'}work/source.bak")
            [ "${'$'}{actual%% *}" = '$backupHash' ]
            stage=extract
            tail -c +${tarOffset + 1} "${'$'}work/source.bak" > "${'$'}work/payload.tar"
            tar -xf "${'$'}work/payload.tar" -C "${'$'}work/payload"
            payload="${'$'}work/payload/apps/com.android.thememanager/ef/MIUI/theme"
            cd "${'$'}payload"
            sha256sum -c "${'$'}work/checks.sha256" >/dev/null
            stage=conflict_check
            am force-stop --user 0 com.android.thememanager
            while IFS= read -r line; do
                hash="${'$'}{line%% *}"
                path="${'$'}{line#*  }"
                target="${'$'}root/${'$'}path"
                [ ! -L "${'$'}target" ]
                parent="${'$'}{target%/*}"
                while [ "${'$'}parent" != "${'$'}root" ]; do
                    [ ! -L "${'$'}parent" ]
                    if [ -e "${'$'}parent" ]; then [ -d "${'$'}parent" ]; fi
                    parent="${'$'}{parent%/*}"
                done
                if [ -e "${'$'}target" ]; then
                    [ -f "${'$'}target" ]
                    actual=${'$'}(sha256sum "${'$'}target")
                    [ "${'$'}{actual%% *}" = "${'$'}hash" ] || exit 43
                fi
            done < "${'$'}work/checks.sha256"
            stage=copy
            while IFS= read -r line; do
                path="${'$'}{line#*  }"
                target="${'$'}root/${'$'}path"
                mkdir -p "${'$'}{target%/*}"
                # Same filesystem: publish a fully extracted file, never a partial copy.
                # -n leaves an existing file untouched even if it appeared after preflight.
                mv -nT "${'$'}payload/${'$'}path" "${'$'}target"
            done < "${'$'}work/checks.sha256"
            stage=verify
            cd "${'$'}root"
            sha256sum -c "${'$'}work/checks.sha256" >/dev/null
            rm -r "${'$'}work"
            echo MTZ_RESTORE_FILES_VERIFIED
        """.trimIndent()
    }
}
