package dev.glorioustr.mtzstudio.tester

/**
 * A concrete, verifiable Theme Manager runtime artifact for the Root module flow.
 *
 * Extends [RootTargetApk] with the family it belongs to and the in-repo download URL. The
 * artifact is published inside the repository (not GitHub Releases) so the in-app update
 * mechanism never touches it — only the signed MTZ Studio APK lives in Releases.
 */
data class RuntimeArtifact(
    val family: ThemeManagerFamily,
    val version: String,
    val apkName: String,
    val sha256: String,
    val minSdk: Int,
) {
    /** In-repo download URL (raw.githubusercontent.com on the default branch). */
    val downloadUrl: String
        get() = "https://raw.githubusercontent.com/GloriousApps/HyperOS-MTZ-Studio/main/requirements/$apkName"

    /** The [RootTargetApk] view of this artifact, for the updater/installer. */
    fun asRootTargetApk(): RootTargetApk = RootTargetApk(
        version = version,
        apkName = apkName,
        sha256 = sha256,
        minSdk = minSdk,
    )
}

/** Resolves the [RuntimeArtifact] the Root module must install for a resolved family. */
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
