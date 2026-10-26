# Optional Xiaomi Themes compatibility packages

These are the unmodified Xiaomi Themes packages used by the Root MTZ Import module flow. They are served from this repository (not from GitHub Releases) so the app's update check never sees them as release assets.

| File | Version | Family | minSdk | Size | APK SHA-256 |
|------|---------|--------|--------|------|-------------|
| `Xiaomi_Themes_3.4.1.23-global.apk` | `3.4.1.23-global` (`3040123`) | Global (Root) | 27 | 66,960,639 | `d405e78fac1eae48e105e57f5e3422ec0a0a6d53f779973d06e131e85b016037` |
| `Xiaomi_Themes_11.5.3.1.apk` | `11.5.3.1` (`11531`) | China (Root) | 34 | 77,737,318 | `3888058041439577aadbb933c9bed1fb3ef16acda20d15404fb620314a8edd0a` |

- Package: `com.android.thememanager`
- Signing certificate: Xiaomi/MIUI
- Certificate SHA-256: `c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025`

The app downloads these files only when the user starts the Root module install flow. Android or the root workflow still performs package and signature checks before installation.

Xiaomi, HyperOS and MIUI are trademarks of Xiaomi. This repository does not modify these APKs.
