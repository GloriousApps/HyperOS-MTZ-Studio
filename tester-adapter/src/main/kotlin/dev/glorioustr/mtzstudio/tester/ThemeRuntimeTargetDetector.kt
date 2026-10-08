package dev.glorioustr.mtzstudio.tester

/**
 * Detects which Theme Manager runtime family a device should run.
 *
 * The Root module installs a controlled Theme Manager runtime: Global devices get
 * 3.4.1.23-global, Non-Global (China) devices get 11.5.3.1. This detector resolves the
 * target from device properties first, then falls back to the currently installed Theme
 * Manager when the device properties are inconclusive.
 *
 * Resolution order (first match wins):
 *  1. `ro.miui.region` == "CN" -> CHINA
 *  2. `ro.miui.region` present and not "CN" -> GLOBAL
 *  3. `ro.product.mod_device` contains "cn" -> CHINA
 *  4. Installed Theme Manager family (from version name) -> GLOBAL / CHINA
 *  5. Otherwise -> UNKNOWN (caller decides; Root install is refused)
 */
object ThemeRuntimeTargetDetector {

    /** A resolved runtime target. */
    enum class Target {
        GLOBAL,
        CHINA,
        UNKNOWN,
    }

    /**
     * Resolves the target from raw device properties. Values are the raw `getprop` outputs
     * (may be null when the property is absent).
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
            // e.g. "venus_cn", "ishtar_cn" -> China builds; "venus_global" -> Global.
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
}
