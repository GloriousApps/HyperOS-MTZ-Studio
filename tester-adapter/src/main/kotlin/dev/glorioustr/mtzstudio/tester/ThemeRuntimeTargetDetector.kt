package dev.glorioustr.mtzstudio.tester

import android.os.Build

/**
 * Detects the Theme Manager runtime family from device properties and installed package data.
 *
 * [detect] is the property-reader API used by the original adapter. [resolve] is the explicit
 * API used by the Root runtime manager; both intentionally remain available because they answer
 * slightly different questions (the former has access to all system properties, while the latter
 * can fall back to the installed Theme Manager family).
 */
object ThemeRuntimeTargetDetector {

    /** Runtime-manager target vocabulary. */
    enum class Target {
        GLOBAL,
        CHINA,
        UNKNOWN,
    }

    /**
     * Resolves a target from the small set of values available to the Root runtime manager.
     * Values may be null when the corresponding property/package is absent.
     */
    fun resolve(
        miuiRegion: String?,
        modDevice: String?,
        installedVersionName: String?,
    ): Target {
        val region = miuiRegion?.trim()?.takeIf { it.isNotEmpty() }
        if (region != null) {
            return if (region.equals("CN", ignoreCase = true)) Target.CHINA else Target.GLOBAL
        }

        val device = modDevice?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (device != null) {
            // Examples: "venus_cn", "ishtar_cn", and "venus_global".
            if (device.contains("_cn") || device.endsWith("cn")) return Target.CHINA
            if (device.contains("_global") || device.contains("_eea") || device.contains("_in")) {
                return Target.GLOBAL
            }
        }

        return when (ThemeManagerContract.familyOf(installedVersionName)) {
            ThemeManagerFamily.GLOBAL -> Target.GLOBAL
            ThemeManagerFamily.CHINA -> Target.CHINA
            ThemeManagerFamily.UNKNOWN -> Target.UNKNOWN
        }
    }

    /**
     * Determines whether the device is GLOBAL or NON_GLOBAL using observable system properties.
     * The fallback remains GLOBAL for backwards compatibility with the original adapter API.
     */
    fun detect(
        getprop: (String) -> String?,
        fingerprint: String = Build.FINGERPRINT ?: "",
        isInternational: Boolean? = null,
    ): ThemeRuntimeTarget {
        val region = getprop("ro.miui.region")?.trim()?.uppercase()
        val country = getprop("ro.boot.hwc")?.trim()?.uppercase()
        val modDevice = getprop("ro.product.mod_device")?.trim()?.lowercase() ?: ""
        val incremental = getprop("ro.build.version.incremental")?.trim()?.uppercase() ?: ""

        if (
            region == "CN" ||
            country == "CN" ||
            modDevice.endsWith("_cn") ||
            (incremental.startsWith("V") && incremental.contains(".CN"))
        ) {
            return ThemeRuntimeTarget.NON_GLOBAL
        }

        val knownGlobalRegions = setOf("GLOBAL", "TR", "EEA", "EU", "RU", "IN", "ID", "TW", "MI")
        if (
            region in knownGlobalRegions ||
            country in knownGlobalRegions ||
            modDevice.endsWith("_global") ||
            modDevice.endsWith("_eea") ||
            modDevice.endsWith("_ru") ||
            modDevice.endsWith("_in") ||
            modDevice.endsWith("_id") ||
            modDevice.endsWith("_tr") ||
            modDevice.endsWith("_tw")
        ) {
            return ThemeRuntimeTarget.GLOBAL
        }

        val lowerFingerprint = fingerprint.lowercase()
        if (
            lowerFingerprint.contains("release-keys") &&
            (lowerFingerprint.contains("global") || lowerFingerprint.contains("eea"))
        ) {
            return ThemeRuntimeTarget.GLOBAL
        }

        if (isInternational == true) return ThemeRuntimeTarget.GLOBAL
        if (isInternational == false && region == "CN") return ThemeRuntimeTarget.NON_GLOBAL

        return ThemeRuntimeTarget.GLOBAL
    }
}
