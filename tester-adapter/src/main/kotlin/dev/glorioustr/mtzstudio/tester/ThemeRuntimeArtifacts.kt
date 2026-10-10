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
        versionName = "3.4.1.23-global",
        versionCode = 3040123L,
        sha256 = "d405e78fac1eae48e105e57f5e3422ec0a0a6d53f779973d06e131e85b016037",
        certificateDigest = "c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025",
        supportedAndroidMin = 27,
        supportedAndroidMax = 36,
        assetFileName = "xiaomi_themes_runtime_global_v2_0_0.zip",
    )

    val RUNTIME_NON_GLOBAL_11_5_3_1 = RuntimeArtifact(
        id = "xiaomi-themes-cn-11.5.3.1",
        target = ThemeRuntimeTarget.NON_GLOBAL,
        packageName = ThemeManagerContract.PACKAGE_NAME,
        versionName = ThemeManagerContract.CONTROLLED_NON_GLOBAL_VERSION,
        versionCode = 11531L,
        sha256 = "3888058041439577aadbb933c9bed1fb3ef16acda20d15404fb620314a8edd0a",
        certificateDigest = "c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025",
        supportedAndroidMin = 34,
        supportedAndroidMax = 36,
        assetFileName = "xiaomi_themes_runtime_non_global_v2_0_0.zip",
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
