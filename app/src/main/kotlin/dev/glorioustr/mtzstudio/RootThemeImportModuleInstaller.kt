package dev.glorioustr.mtzstudio

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.glorioustr.mtzstudio.tester.PrivilegedCommandRunner
import dev.glorioustr.mtzstudio.tester.ThemeManagerInspector
import dev.glorioustr.mtzstudio.tester.ThemeManagerUpdateException
import dev.glorioustr.mtzstudio.tester.ThemeRuntimeArtifacts
import dev.glorioustr.mtzstudio.tester.ThemeRuntimeTarget
import dev.glorioustr.mtzstudio.tester.ThemeRuntimeTargetDetector
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Installs one of the two verified Xiaomi Themes root runtime variants. */
internal class RootThemeImportModuleInstaller(
    context: Context,
    private val commandRunner: PrivilegedCommandRunner,
) {
    private val appContext = context.applicationContext
    private val inspector = ThemeManagerInspector(appContext)

    data class State(
        /** Whether the root-manager module directory exists. This is not runtime activity. */
        val installed: Boolean,
        val version: String?,
        val active: Boolean,
        /** Target recorded by the module, for display only. */
        val target: ThemeRuntimeTarget? = null,
        val activeTarget: ThemeRuntimeTarget? = null,
        val runtimeVersion: String? = null,
        val runtimeVersionCode: Long? = null,
        val runtimeVerified: Boolean = false,
        val bridgeReady: Boolean = false,
        val disabled: Boolean = false,
        val removed: Boolean = false,
    )

    data class InstallResult(
        val target: ThemeRuntimeTarget,
        val version: String,
        val authorizationSource: String,
        val output: String,
    )

    fun inspect(): State {
        val packageInfo = installedPackageInfo()
        val sourceDir = packageInfo?.applicationInfo?.sourceDir
        val module = commandRunner.run(
            """
            if [ "${'$'}(id -u)" != "0" ]; then echo ROOT_REQUIRED; exit 0; fi
            target='$MODULE_DIRECTORY'
            if [ -f "${'$'}target/module.prop" ] && grep -qx 'id=$MODULE_ID' "${'$'}target/module.prop"; then
              version="${'$'}(sed -n 's/^version=//p' "${'$'}target/module.prop" | head -n 1)"
              echo "INSTALLED:${'$'}version"
              [ -f "${'$'}target/selected-target" ] && echo "TARGET:${'$'}(cat "${'$'}target/selected-target")"
            else
              echo NOT_INSTALLED
            fi
            [ -f "${'$'}target/disable" ] && echo DISABLED:true || echo DISABLED:false
            if [ -f "${'$'}target/remove" ] || [ -f "${'$'}target/.remove" ]; then echo REMOVED:true; else echo REMOVED:false; fi
            # The pinned bridge dex writes its bridge version, not the module version.
            # Only accept a marker written after this Themes process started this boot.
            bridge=false
            pid="${'$'}(pidof com.android.thememanager 2>/dev/null | awk '{print ${'$'}1}')"
            if [ -n "${'$'}pid" ] && [ -f '$READY_MARKER' ] && grep -qx 'ready=true' '$READY_MARKER' 2>/dev/null; then
              ticks="${'$'}(sed 's/^.*) //' "/proc/${'$'}pid/stat" 2>/dev/null | awk '{print ${'$'}20}')"
              hz="${'$'}(getconf CLK_TCK 2>/dev/null || echo 100)"
              now="${'$'}(date +%s)"
              uptime="${'$'}(awk '{print ${'$'}1}' /proc/uptime)"
              marker_time="${'$'}(stat -c %Y '$READY_MARKER' 2>/dev/null)"
              if awk -v ticks="${'$'}ticks" -v hz="${'$'}hz" -v now="${'$'}now" -v uptime="${'$'}uptime" -v marker="${'$'}marker_time" '
                BEGIN {
                  if (ticks !~ /^[0-9]+${'$'}/ || hz !~ /^[0-9]+${'$'}/ || hz <= 0 || marker !~ /^[0-9]+${'$'}/) exit 1;
                  process_start = int(now - uptime + ticks / hz);
                  exit !(marker >= process_start && marker <= now);
                }'; then bridge=true; fi
            fi
            echo "BRIDGE_READY:${'$'}bridge"
            source_dir=${shellQuote(sourceDir.orEmpty())}
            live_path="${'$'}(pm path '${ThemeManagerPackage.NAME}' 2>/dev/null | sed -n 's/^package:\(\/.*\)$/\1/p' | head -n 1)"
            if [ -n "${'$'}source_dir" ] && [ "${'$'}live_path" = "${'$'}source_dir" ] && [ -f "${'$'}source_dir" ]; then
              live_sha="${'$'}(sha256sum "${'$'}source_dir" 2>/dev/null | awk '{print ${'$'}1}' | tr '[:upper:]' '[:lower:]')"
              [ -n "${'$'}live_sha" ] || live_sha="${'$'}(toybox sha256sum "${'$'}source_dir" 2>/dev/null | awk '{print ${'$'}1}' | tr '[:upper:]' '[:lower:]')"
              echo "LIVE_SHA:${'$'}live_sha"
            else
              echo LIVE_SHA:
            fi
            """.trimIndent(),
            30,
        )
        check(module.exitCode == 0 && !module.output.lineSequence().any { it.trim() == "ROOT_REQUIRED" }) {
            module.output.ifBlank { "Root runtime inspection failed (${module.exitCode})" }
        }
        val lines = module.output.lineSequence().map(String::trim).toList()
        val installed = lines.firstOrNull { it.startsWith("INSTALLED:") }
        val target = parseTarget(lines.firstOrNull { it.startsWith("TARGET:") }?.substringAfter(':'))
        // Keep the raw marker separate until the installed APK target is known. bridgeReady is
        // deliberately Global-only; Non-Global must never inherit a bridge signal.
        val rawBridgeReady = lines.value("BRIDGE_READY").equals("true", ignoreCase = true)
        val disabled = lines.value("DISABLED").equals("true", ignoreCase = true)
        val removed = lines.value("REMOVED").equals("true", ignoreCase = true)

        val runtimeVersion = packageInfo?.versionName
        val runtimeCode = packageInfo?.longVersionCodeCompat()
        val liveSha = lines.value("LIVE_SHA")
        val runtimeTarget = ThemeRuntimeArtifacts.ALL.firstOrNull { artifact ->
            packageInfo?.packageName == artifact.packageName &&
                runtimeCode == artifact.versionCode &&
                runtimeVersion == artifact.versionName &&
                liveSha.equals(artifact.sha256, ignoreCase = true) &&
                packageInfo.signingCertificateDigests().any {
                    it.equals(artifact.certificateDigest, ignoreCase = true)
                }
        }?.target
        val runtimeVerified = runtimeTarget != null
        val bridgeReady = rawBridgeReady && runtimeTarget == ThemeRuntimeTarget.GLOBAL &&
            target == ThemeRuntimeTarget.GLOBAL && !disabled && !removed
        val moduleVersion = installed?.substringAfter(':')?.takeIf(String::isNotBlank)
        val moduleMatches = installed != null && moduleVersion == MODULE_VERSION && target != null
        val activeTarget = when {
            !moduleMatches || !runtimeVerified || disabled || removed -> null
            runtimeTarget == ThemeRuntimeTarget.GLOBAL && target == ThemeRuntimeTarget.GLOBAL && bridgeReady -> runtimeTarget
            runtimeTarget == ThemeRuntimeTarget.NON_GLOBAL && target == ThemeRuntimeTarget.NON_GLOBAL && !rawBridgeReady -> runtimeTarget
            else -> null
        }
        return State(
            installed = installed != null,
            version = installed?.substringAfter(':')?.takeIf(String::isNotBlank),
            active = activeTarget != null,
            target = target,
            activeTarget = activeTarget,
            runtimeVersion = runtimeVersion,
            runtimeVersionCode = runtimeCode,
            runtimeVerified = runtimeVerified,
            bridgeReady = bridgeReady,
            disabled = disabled,
            removed = removed,
        )
    }

    /** Compares both the selected runtime target and module version. */
    fun isBundledVersion(state: State?, target: ThemeRuntimeTarget = state?.target ?: ThemeRuntimeTarget.GLOBAL): Boolean =
        state?.installed == true && state.target == target && state.version == MODULE_VERSION

    /** Reads the same small set of properties used by the tester adapter's detector. */
    fun detectTarget(): ThemeRuntimeTarget {
        val properties = commandRunner.run(
            "printf 'REGION='; getprop ro.miui.region; printf 'MOD_DEVICE='; getprop ro.product.mod_device; " +
                "printf 'INCREMENTAL='; getprop ro.build.version.incremental; printf 'HWC='; getprop ro.boot.hwc",
            10,
        ).output.lineSequence().mapNotNull { line ->
            line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        return ThemeRuntimeTargetDetector.detect(
            getprop = { key -> when (key) {
                "ro.miui.region" -> properties["REGION"]
                "ro.product.mod_device" -> properties["MOD_DEVICE"]
                "ro.build.version.incremental" -> properties["INCREMENTAL"]
                "ro.boot.hwc" -> properties["HWC"]
                else -> null
            } },
        )
    }

    /** Stages and verifies one ZIP, runs its installer once, then swaps the module directory. */
    fun installOrUpdate(target: ThemeRuntimeTarget): InstallResult {
        val artifact = ThemeRuntimeArtifacts.forTarget(target)
        val stagedZip = File(
            appContext.externalCacheDir ?: error("Root-readable external cache is unavailable"),
            "theme-import-module/${UUID.randomUUID()}-${artifact.assetFileName}",
        )
        val verificationRoot = File(appContext.cacheDir, "theme-import-module/verify-${UUID.randomUUID()}")
        stagedZip.parentFile?.mkdirs()
        verificationRoot.mkdirs()
        try {
            appContext.assets.open(artifact.assetFileName ?: error("Runtime asset is not configured")).use { input ->
                stagedZip.outputStream().use(input::copyTo)
            }
            stagedZip.setReadable(true, false)
            val expectedZipSha = zipSha256(target)
            check(expectedZipSha.matches(Regex("[0-9a-fA-F]{64}"))) {
                "Bundled runtime ZIP checksum is not pinned for $target"
            }
            check(sha256(stagedZip).equals(expectedZipSha, ignoreCase = true)) {
                "Bundled $target runtime ZIP checksum does not match"
            }

            extractZipSafely(stagedZip, verificationRoot)
            val apk = File(verificationRoot, "payload/theme.apk")
            verifyModuleFiles(verificationRoot, target)
            verifyRuntimeApk(apk, artifact)
            val result = commandRunner.run(installCommand(stagedZip.absolutePath, target, expectedZipSha), 180)
            if (result.exitCode != 0 || !result.output.lineSequence().any { it.trim() == "MTZ_IMPORT_MODULE_INSTALLED" }) {
                throw ThemeManagerUpdateException(
                    result.output.ifBlank { "Root runtime installation was rejected (exit ${result.exitCode})" },
                )
            }
            return InstallResult(target, MODULE_VERSION, result.authorizationSource, result.output)
        } finally {
            stagedZip.delete()
            stagedZip.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
            verificationRoot.deleteRecursively()
        }
    }

    private fun verifyRuntimeApk(apk: File, artifact: dev.glorioustr.mtzstudio.tester.RuntimeArtifact) {
        check(apk.isFile) { "Bundled $artifact runtime is missing payload/theme.apk" }
        val archive = inspector.inspectArchive(apk.absolutePath)
        check(archive.packageName == artifact.packageName) { "Runtime APK package is not Xiaomi Themes" }
        check(archive.versionName == artifact.versionName) {
            "Runtime APK version ${archive.versionName} does not match ${artifact.versionName}"
        }
        check(archive.versionCode == artifact.versionCode) {
            "Runtime APK versionCode ${archive.versionCode} does not match ${artifact.versionCode}"
        }
        check(archive.signingCertificateSha256.any { it.equals(artifact.certificateDigest, ignoreCase = true) }) {
            "Runtime APK certificate does not match the pinned certificate"
        }
        check(sha256(apk).equals(artifact.sha256, ignoreCase = true)) {
            "Runtime APK checksum does not match the pinned artifact"
        }
        val archiveInfo = packageInfoFromArchive(apk)
        check(archiveInfo?.applicationInfo?.minSdkVersion == artifact.supportedAndroidMin) {
            "Runtime APK minSdkVersion is not ${artifact.supportedAndroidMin}"
        }
        check(hasArm64Library(apk)) { "Runtime APK does not contain an arm64-v8a library" }
         check(Build.VERSION.SDK_INT in artifact.supportedAndroidMin..artifact.supportedAndroidMax) {
             "Selected runtime supports API ${artifact.supportedAndroidMin}-${artifact.supportedAndroidMax}"
         }
        check(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "Selected runtime requires arm64-v8a" }
        val installed = installedPackageInfo() ?: throw ThemeManagerUpdateException(
            "Xiaomi Themes is not installed; the runtime cannot preserve its signing history",
        )
        val installedCerts = installed.signingCertificateDigests()
        check(installedCerts.any { it.equals(artifact.certificateDigest, ignoreCase = true) }) {
            "Installed Xiaomi Themes signing history does not match the pinned certificate"
        }
    }

    private fun verifyModuleFiles(root: File, target: ThemeRuntimeTarget) {
        val properties = File(root, "module.prop").readLines()
        check(properties.count { it == "id=$MODULE_ID" } == 1 &&
            properties.count { it.startsWith("id=") } == 1 &&
            properties.count { it == "version=$MODULE_VERSION" } == 1 &&
            properties.count { it.startsWith("version=") } == 1) { "Runtime module identity/version mismatch" }
        for (script in listOf("runtime-install.sh", "service.sh", "customize.sh")) {
            check(File(root, script).isFile) { "Runtime module is missing $script" }
        }
        check(File(root, "selected-target").readText().trim() == target.name) { "Runtime module target mismatch" }
        if (target == ThemeRuntimeTarget.GLOBAL) {
            check(File(root, "zygisk/arm64-v8a.so").isFile && File(root, "dex/classes.dex").isFile) {
                "Global runtime bridge is missing"
            }
        } else {
            check(!File(root, "zygisk").exists() && !File(root, "dex").exists()) {
                "Non-Global runtime contains a Global bridge"
            }
        }
    }

    private fun installCommand(sourcePath: String, target: ThemeRuntimeTarget, expectedZipSha: String): String {
        val source = shellQuote(sourcePath)
        val token = UUID.randomUUID().toString().replace("-", "")
        val staging = "/data/local/tmp/mtzstudio-theme-runtime-$token"
        val backup = "$MODULE_DIRECTORY.backup-$token"
        return """
            set -eu
            test "${'$'}(id -u)" = "0"
            source=$source
            staging=${shellQuote(staging)}
            target='$MODULE_DIRECTORY'
            backup=${shellQuote(backup)}
            expected_zip_sha=${shellQuote(expectedZipSha.lowercase())}
            old_context=""
            if [ -d "${'$'}target" ] && command -v ls >/dev/null 2>&1; then
              old_context="${'$'}(ls -Zd "${'$'}target" 2>/dev/null | awk '{print ${'$'}1}')"
            fi
            rm -rf "${'$'}staging"
            mkdir -p "${'$'}staging"
            if ! cp "${'$'}source" "${'$'}staging/runtime.zip"; then echo ROOT_STAGE_COPY_FAILED >&2; exit 1; fi
            copied_zip_sha="${'$'}(sha256sum "${'$'}staging/runtime.zip" 2>/dev/null | awk '{print ${'$'}1}' | tr '[:upper:]' '[:lower:]')"
            [ -n "${'$'}copied_zip_sha" ] || copied_zip_sha="${'$'}(toybox sha256sum "${'$'}staging/runtime.zip" 2>/dev/null | awk '{print ${'$'}1}' | tr '[:upper:]' '[:lower:]')"
            [ "${'$'}copied_zip_sha" = "${'$'}expected_zip_sha" ] || { echo ROOT_STAGE_ZIP_HASH_FAILED >&2; exit 1; }
            if command -v unzip >/dev/null 2>&1; then unzip -oq "${'$'}staging/runtime.zip" -d "${'$'}staging"; else toybox unzip -oq "${'$'}staging/runtime.zip" -d "${'$'}staging"; fi
            test -f "${'$'}staging/runtime-install.sh"
            test -f "${'$'}staging/payload/theme.apk"
            test -f "${'$'}staging/module.prop"
            grep -qx 'id=$MODULE_ID' "${'$'}staging/module.prop"
            grep -qx 'version=$MODULE_VERSION' "${'$'}staging/module.prop"
            if [ '$target' = 'GLOBAL' ]; then
              test -f "${'$'}staging/zygisk/arm64-v8a.so"
              test -f "${'$'}staging/dex/classes.dex"
            else
              if [ -e "${'$'}staging/zygisk" ] || [ -e "${'$'}staging/dex" ]; then echo NON_GLOBAL_GLOBAL_BRIDGE_PRESENT >&2; exit 1; fi
            fi
            # The bundled script owns Package Manager installation and must return 0 only after
            # a live package-manager verification. It is deliberately invoked exactly once.
            sh "${'$'}staging/runtime-install.sh" "${'$'}staging" '$target'
            if [ -e "${'$'}target" ]; then
              test -f "${'$'}target/module.prop"
              grep -qx 'id=$MODULE_ID' "${'$'}target/module.prop"
              rm -rf "${'$'}backup"
              mv "${'$'}target" "${'$'}backup"
            fi
            printf '%s\n' '$target' > "${'$'}staging/selected-target"
            if ! mv "${'$'}staging" "${'$'}target"; then
              echo APK_MAY_HAVE_BEEN_INSTALLED_MODULE_SWAP_FAILED >&2
              if [ -d "${'$'}backup" ]; then mv "${'$'}backup" "${'$'}target"; fi
              exit 1
            fi
            chown -R 0:0 "${'$'}target"
            chmod 0755 "${'$'}target"
            chmod 0644 "${'$'}target/module.prop" "${'$'}target/selected-target" "${'$'}target/payload/theme.apk" "${'$'}target/runtime-install.sh"
            [ ! -e "${'$'}target/service.sh" ] || chmod 0755 "${'$'}target/service.sh"
            [ ! -e "${'$'}target/customize.sh" ] || chmod 0755 "${'$'}target/customize.sh"
            if [ '$target' = 'GLOBAL' ]; then
              chmod 0755 "${'$'}target/zygisk" "${'$'}target/dex"
              chmod 0644 "${'$'}target/zygisk/arm64-v8a.so" "${'$'}target/dex/classes.dex"
            fi
            if command -v restorecon >/dev/null 2>&1; then
              if ! restorecon -RF "${'$'}target"; then
                case "${'$'}old_context" in
                  u:object_r:[A-Za-z0-9_]*_file:s0) command -v chcon >/dev/null 2>&1 && chcon -R "${'$'}old_context" "${'$'}target" || true ;;
                esac
              fi
            fi
            rm -f '$READY_MARKER'
            rm -rf "${'$'}backup" "${'$'}target/runtime.zip"
            echo MTZ_IMPORT_MODULE_INSTALLED
            """.trimIndent()
    }

    private fun installedPackageInfo(): PackageInfo? = runCatching {
        val pm = appContext.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(ThemeManagerPackage.NAME, PackageManager.PackageInfoFlags.of(signingFlags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ThemeManagerPackage.NAME, signingFlags())
        }
    }.getOrNull()

    private fun packageInfoFromArchive(apk: File): PackageInfo? {
        val pm = appContext.packageManager
        val flags = signingFlags()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, flags)
        }
    }

    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

    private fun PackageInfo.signingCertificateDigests(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            signatures
        }
        return signatures.orEmpty().mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    private fun PackageInfo.longVersionCodeCompat(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        @Suppress("DEPRECATION")
        versionCode.toLong()
    }

    private fun extractZipSafely(zip: File, destination: File) {
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val output = File(destination, entry.name)
                check(output.canonicalPath.startsWith(destination.canonicalPath + File.separator)) {
                    "Runtime ZIP contains an unsafe path"
                }
                if (entry.isDirectory) output.mkdirs() else {
                    output.parentFile?.mkdirs()
                    output.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    private fun hasArm64Library(apk: File): Boolean = ZipFile(apk).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory && entry.name.startsWith("lib/arm64-v8a/") && entry.name.endsWith(".so")) return@use true
        }
        false
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

    private fun zipSha256(target: ThemeRuntimeTarget): String = when (target) {
        ThemeRuntimeTarget.GLOBAL -> GLOBAL_ZIP_SHA256
        ThemeRuntimeTarget.NON_GLOBAL -> NON_GLOBAL_ZIP_SHA256
    }

    private fun parseTarget(value: String?): ThemeRuntimeTarget? = when (value?.trim()) {
        "GLOBAL" -> ThemeRuntimeTarget.GLOBAL
        "NON_GLOBAL" -> ThemeRuntimeTarget.NON_GLOBAL
        else -> null
    }

    private fun List<String>.value(prefix: String): String? =
        firstOrNull { it.startsWith("$prefix:") }?.substringAfter(':')

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private object ThemeManagerPackage { const val NAME = "com.android.thememanager" }

    private companion object {
        const val MODULE_ID = "xiaomi_themes_global_import"
        const val MODULE_VERSION = "2.0.0"
        const val MODULE_DIRECTORY = "/data/adb/modules/xiaomi_themes_global_import"
        const val READY_MARKER = "/data/user/0/com.android.thememanager/files/mtz_import_module_ready"
        const val GLOBAL_ZIP_SHA256 = "a69b07abce77afca8121844de19ff193d227e51922a42bcf2cf0ec12f5faa5bc"
        const val NON_GLOBAL_ZIP_SHA256 = "1b35797ea26b2ce667183137eccfa3abd92834d9d798f9c5ac3b662f27b6cd28"
    }
}
