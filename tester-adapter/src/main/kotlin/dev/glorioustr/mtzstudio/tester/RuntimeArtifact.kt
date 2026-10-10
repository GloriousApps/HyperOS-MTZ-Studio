package dev.glorioustr.mtzstudio.tester

/**
 * Resolves the Root artifact view while retaining the pinned local metadata fields in the single
 * [RuntimeArtifact] declaration in ThemeRuntimeDomain.kt.
 */
fun ThemeManagerContract.runtimeArtifact(family: ThemeManagerFamily): RuntimeArtifact? =
    rootTargetApk(family)?.let { target ->
        RuntimeArtifact(
            family = family,
            version = target.version,
            apkName = target.apkName,
            sha256 = target.sha256,
            minSdk = target.minSdk,
        )
    }
