package dev.glorioustr.mtzstudio.tester

/**
 * Registry of official Xiaomi Theme Manager runtime artifacts.
 * Contains immutable metadata (version, checksum, signatures) for controlled runtimes.
 */
object ThemeRuntimeArtifacts {
    val RUNTIME_GLOBAL_3_4_1_23 = RuntimeArtifact(
        id = "xiaomi-themes-global-3.4.1.23",
        target = ThemeRuntimeTarget.GLOBAL,
        packageName = ThemeManagerContract.PACKAGE_NAME,
        versionName = ThemeManagerContract.CONTROLLED_GLOBAL_VERSION,
        versionCode = 3040123L,
        sha256 = "c2e1f4095493b827cf66190be9bf610a70f2f3fcfcf4b3e86c0c59e7987819c9",
        certificateDigest = "940656cf0db32e570f4cf55c82ccb646c0d8f34de54a32e128ef31d0ceec2685",
        supportedAndroidMin = 28,
        supportedAndroidMax = 35,
        assetFileName = "runtime_thememanager_3_4_1_23_global.apk",
    )

    val RUNTIME_NON_GLOBAL_11_5_3_1 = RuntimeArtifact(
        id = "xiaomi-themes-cn-11.5.3.1",
        target = ThemeRuntimeTarget.NON_GLOBAL,
        packageName = ThemeManagerContract.PACKAGE_NAME,
        versionName = ThemeManagerContract.CONTROLLED_NON_GLOBAL_VERSION,
        versionCode = 11050301L,
        sha256 = "8e3b5e406579899f1fa023a1059f143714ee560f769faeb3c042ca93be2780e2",
        certificateDigest = "940656cf0db32e570f4cf55c82ccb646c0d8f34de54a32e128ef31d0ceec2685",
        supportedAndroidMin = 28,
        supportedAndroidMax = 35,
        assetFileName = "runtime_thememanager_11_5_3_1.apk",
    )

    val ALL = listOf(
        RUNTIME_GLOBAL_3_4_1_23,
        RUNTIME_NON_GLOBAL_11_5_3_1,
    )

    fun forTarget(target: ThemeRuntimeTarget): RuntimeArtifact = when (target) {
        ThemeRuntimeTarget.GLOBAL -> RUNTIME_GLOBAL_3_4_1_23
        ThemeRuntimeTarget.NON_GLOBAL -> RUNTIME_NON_GLOBAL_11_5_3_1
    }
}
