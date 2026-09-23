package dev.glorioustr.mtzstudio

import android.content.Context
import dev.glorioustr.mtzstudio.tester.PrivilegedCommandRunner
import dev.glorioustr.mtzstudio.tester.ThemeManagerUpdateException
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Installs only MTZ Studio's own, bundled Zygisk module into the standard root-manager module
 * directory. The Themes APK is never touched. Zygisk applies the module only after a reboot.
 */
internal class RootThemeImportModuleInstaller(
    context: Context,
    private val commandRunner: PrivilegedCommandRunner,
) {
    private val appContext = context.applicationContext

    data class State(
        val installed: Boolean,
        val version: String?,
        val active: Boolean,
    )

    data class InstallResult(
        val version: String,
        val authorizationSource: String,
        val output: String,
    )

    fun inspect(): State {
        val result = commandRunner.run(
            """
            if [ "${'$'}(id -u)" != "0" ]; then echo ROOT_REQUIRED; exit 0; fi
            target='$MODULE_DIRECTORY'
            marker='$READY_MARKER'
            if [ -f "${'$'}target/module.prop" ] && grep -qx 'id=$MODULE_ID' "${'$'}target/module.prop"; then
              version="${'$'}(sed -n 's/^version=//p' "${'$'}target/module.prop" | head -n 1)"
              echo "INSTALLED:${'$'}version"
            else
              echo NOT_INSTALLED
            fi
            if [ -f "${'$'}marker" ]; then echo "ACTIVE:${'$'}(head -n 1 "${'$'}marker")"; else echo NOT_ACTIVE; fi
            """.trimIndent(),
            10,
        )
        val lines = result.output.lineSequence().map(String::trim).toList()
        val installed = lines.firstOrNull { it.startsWith("INSTALLED:") }
        val active = lines.firstOrNull { it.startsWith("ACTIVE:") }
        return State(
            installed = installed != null,
            version = installed?.substringAfter(':')?.takeIf(String::isNotBlank),
            // Zygisk injects only when Xiaomi Themes starts.  Requiring Themes to have
            // already been opened after every reboot made a correctly installed module look
            // like it still needed a restart.  The signed module.prop is the authoritative
            // availability check; the runtime marker is retained only as extra diagnostics.
            active = active != null || installed != null,
        )
    }

    /** Returns true only when the installed module is the exact version bundled with this app. */
    fun isBundledVersion(state: State?): Boolean =
        state?.installed == true && state.version == MODULE_VERSION

    fun installOrUpdate(): InstallResult {
        val staged = File(appContext.cacheDir, "theme-import-module/${UUID.randomUUID()}.zip")
        staged.parentFile?.mkdirs()
        appContext.assets.open(ASSET_NAME).use { input -> staged.outputStream().use(input::copyTo) }
        try {
            check(sha256(staged).equals(ASSET_SHA256, ignoreCase = true)) {
                "Bundled MTZ Import module checksum does not match"
            }
            val result = commandRunner.run(installCommand(staged.absolutePath), 60)
            if (result.exitCode != 0 || !result.output.lineSequence().any { it.trim() == "MTZ_IMPORT_MODULE_INSTALLED" }) {
                throw ThemeManagerUpdateException(result.output.ifBlank { "Root module installation was rejected" })
            }
            return InstallResult(MODULE_VERSION, result.authorizationSource, result.output)
        } finally {
            staged.delete()
            staged.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
        }
    }

    private fun installCommand(sourcePath: String): String {
        val source = shellQuote(sourcePath)
        val token = UUID.randomUUID().toString().replace("-", "")
        val staging = "/data/local/tmp/mtzstudio-theme-import-$token"
        val backup = "$MODULE_DIRECTORY.backup-$token"
        return """
            set -eu
            test "${'$'}(id -u)" = "0"
            source=$source
            staging=${shellQuote(staging)}
            target='$MODULE_DIRECTORY'
            backup=${shellQuote(backup)}
            test -f "${'$'}source"
            rm -rf "${'$'}staging"
            mkdir -p "${'$'}staging/payload"
            if command -v unzip >/dev/null 2>&1; then
              unzip -oq "${'$'}source" -d "${'$'}staging/payload"
            else
              toybox unzip -oq "${'$'}source" -d "${'$'}staging/payload"
            fi
            test -f "${'$'}staging/payload/module.prop"
            grep -qx 'id=$MODULE_ID' "${'$'}staging/payload/module.prop"
            test -f "${'$'}staging/payload/zygisk/arm64-v8a.so"
            test -f "${'$'}staging/payload/dex/classes.dex"
            if [ -e "${'$'}target" ]; then
              test -f "${'$'}target/module.prop"
              grep -qx 'id=$MODULE_ID' "${'$'}target/module.prop"
              rm -rf "${'$'}backup"
              mv "${'$'}target" "${'$'}backup"
            fi
            if ! mv "${'$'}staging/payload" "${'$'}target"; then
              if [ -d "${'$'}backup" ]; then mv "${'$'}backup" "${'$'}target"; fi
              exit 1
            fi
            chown -R 0:0 "${'$'}target"
            chmod 0755 "${'$'}target" "${'$'}target/zygisk" "${'$'}target/dex"
            chmod 0644 "${'$'}target/module.prop" "${'$'}target/zygisk/arm64-v8a.so" "${'$'}target/dex/classes.dex"
            # Files copied from app storage retain shell_data_file labels. Zygisk only loads
            # modules from the normal /data/adb/modules SELinux context after the next boot.
            if command -v restorecon >/dev/null 2>&1; then
              restorecon -RF "${'$'}target" || true
            fi
            if command -v chcon >/dev/null 2>&1; then
              chcon u:object_r:system_file:s0 "${'$'}target" "${'$'}target/zygisk" "${'$'}target/dex" || true
              chcon u:object_r:system_file:s0 "${'$'}target/module.prop" "${'$'}target/zygisk/arm64-v8a.so" "${'$'}target/dex/classes.dex" || true
            fi
            rm -f '$READY_MARKER'
            rm -rf "${'$'}backup" "${'$'}staging"
            echo MTZ_IMPORT_MODULE_INSTALLED
            """.trimIndent()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\\"'\\\"'")}'"

    private companion object {
        const val MODULE_ID = "xiaomi_themes_global_import"
        const val MODULE_VERSION = "1.0.2"
        const val MODULE_DIRECTORY = "/data/adb/modules/xiaomi_themes_global_import"
        const val READY_MARKER = "/data/user/0/com.android.thememanager/files/mtz_import_module_ready"
        const val ASSET_NAME = "xiaomi_themes_global_mtz_import_v1_0_2.zip"
        const val ASSET_SHA256 = "ecb713b52031cca25dbee01780de52a6ab3934c9d3390b9cd147a79218553b4f"
    }
}
