package dev.glorioustr.mtzstudio.tester

import android.os.Build

/**
 * Robust target detection evaluating props, device model, and fingerprint
 * rather than a single boolean or property.
 */
object ThemeRuntimeTargetDetector {

    /**
     * Determines whether the device target is GLOBAL or NON_GLOBAL.
     *
     * @param getprop Function to query system properties (mockable for unit tests).
     * @param fingerprint Build fingerprint string.
     * @param isInternational Optional Build.IS_INTERNATIONAL_BUILD flag if available.
     */
    fun detect(
        getprop: (String) -> String?,
        fingerprint: String = Build.FINGERPRINT ?: "",
        isInternational: Boolean? = null,
    ): ThemeRuntimeTarget {
        val region = getprop("ro.miui.region")?.trim()?.uppercase()
        val country = getprop("ro.boot.hwc")?.trim()?.uppercase()
        val modDevice = getprop("ro.product.mod_device")?.trim()?.lowercase() ?: ""
        val buildVersionIncremental = getprop("ro.build.version.incremental")?.trim()?.uppercase() ?: ""

        // Explicit China identifiers
        if (region == "CN" || country == "CN" || modDevice.endsWith("_cn") ||
            buildVersionIncremental.startsWith("V") && buildVersionIncremental.contains(".CN")
        ) {
            return ThemeRuntimeTarget.NON_GLOBAL
        }

        // Explicit Global / EEA / RU / IN / ID / TW identifiers
        val knownGlobalRegions = setOf("GLOBAL", "TR", "EEA", "EU", "RU", "IN", "ID", "TW", "MI")
        if (region in knownGlobalRegions || country in knownGlobalRegions ||
            modDevice.endsWith("_global") || modDevice.endsWith("_eea") ||
            modDevice.endsWith("_ru") || modDevice.endsWith("_in") || modDevice.endsWith("_id") ||
            modDevice.endsWith("_tr") || modDevice.endsWith("_tw")
        ) {
            return ThemeRuntimeTarget.GLOBAL
        }

        // Check Build Fingerprint keywords
        val lowerFingerprint = fingerprint.lowercase()
        if (lowerFingerprint.contains("release-keys") && (lowerFingerprint.contains("global") || lowerFingerprint.contains("eea"))) {
            return ThemeRuntimeTarget.GLOBAL
        }

        // Fallback to international flag if passed
        if (isInternational == true) {
            return ThemeRuntimeTarget.GLOBAL
        } else if (isInternational == false && region == "CN") {
            return ThemeRuntimeTarget.NON_GLOBAL
        }

        // Default to GLOBAL if not explicitly identifiable as China/non-global
        return ThemeRuntimeTarget.GLOBAL
    }
}
