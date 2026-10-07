package dev.glorioustr.mtzstudio.tester

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

class ThemeManagerInspector(private val context: Context) {
    fun inspect(): InstalledThemeManager {
        val packageManager = context.packageManager
        val packageName = ThemeManagerContract.PACKAGE_NAME
        val info = runCatching { installedPackageInfo(packageManager, packageName) }.getOrNull()
        if (info != null) return info.toInstalledThemeManager(packageName)
        return InstalledThemeManager(
            installed = false,
            packageName = packageName,
            versionName = null,
            versionCode = null,
            behavior = ThemeManagerBehavior.UNKNOWN,
        )
    }

    /**
     * Reads a locally staged APK without installing it.  This is also used by Live Diagnostics
     * after a user explicitly exports the installed Themes base APK for compatibility analysis.
     */
    fun inspectArchive(path: String): ArchivePackageInfo {
        val flags = signingFlags()
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(path, flags)
        } ?: throw ThemeManagerUpdateException("Selected file is not a readable Android APK")
        return ArchivePackageInfo(
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = info.longVersionCodeCompat(),
            signingCertificateSha256 = info.signingCertificateDigests(),
        )
    }

    private fun installedPackageInfo(packageManager: PackageManager, packageName: String): PackageInfo {
        val flags = signingFlags()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, flags)
        }
    }

    private fun PackageInfo.toInstalledThemeManager(packageName: String) = InstalledThemeManager(
        installed = true,
        packageName = packageName,
        versionName = versionName,
        versionCode = longVersionCodeCompat(),
        behavior = ThemeManagerContract.behavior(versionName),
        family = ThemeManagerContract.familyOf(versionName),
        signingCertificateSha256 = signingCertificateDigests(),
    )

    private fun PackageInfo.longVersionCodeCompat(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        @Suppress("DEPRECATION")
        versionCode.toLong()
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
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}

data class ArchivePackageInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val signingCertificateSha256: Set<String>,
)
