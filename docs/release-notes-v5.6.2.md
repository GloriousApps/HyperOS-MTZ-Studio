# HyperOS MTZ Studio v5.6.2

## Xiaomi Temalar compatibility recovery

- Replaced the Root compatibility APK file picker with an in-app download flow.
- The app now selects the verified Global or Non-Global Xiaomi Temalar target automatically.
- Downloaded APKs are checked for package name, target version, Xiaomi signing certificate, and pinned SHA-256 before installation.
- Temporary DownloadManager entries are cleaned up after the APK is staged.
- Existing root confirmation and installation checks remain in place.

## Update

- Version code: `79`
- Version name: `5.6.2`

## Verification

- Scoped `git diff --check` passes for the changed source files.
- Local Android Gradle compilation was attempted but could not start because the configured Android SDK path is unavailable in the local environment.
- The signed release APK is built and published by the GitHub Actions release workflow for this tag.
