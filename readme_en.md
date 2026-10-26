# HyperOS MTZ Studio

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/logo_banner.png" alt="HyperOS MTZ Studio" width="640">
</p>

<p align="center">
  An open-source MTZ workspace for Xiaomi HyperOS.<br>
  Import, translate, convert, compose and apply themes on compatible devices.
</p>

<p align="center">
  <a href="README.md">🇹🇷 Türkçe</a> · <strong>🇬🇧 English</strong>
</p>

<p align="center">
  <a href="https://github.com/GloriousApps/HyperOS-MTZ-Studio/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/GloriousApps/HyperOS-MTZ-Studio?display_name=tag&style=for-the-badge&color=7357e6"></a>
  <a href="https://github.com/GloriousApps/HyperOS-MTZ-Studio/actions/workflows/release.yml"><img alt="Release build" src="https://img.shields.io/github/actions/workflow/status/GloriousApps/HyperOS-MTZ-Studio/release.yml?style=for-the-badge&label=Release"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white">
</p>

<p align="center">
  <a href="https://github.com/GloriousApps/HyperOS-MTZ-Studio/releases/tag/v5.6.0"><strong>Download v5.6.0 APK</strong></a>
  · <a href="docs/release-notes-v5.6.0.md">Release notes</a>
  · <a href="docs/theme-manager-compatibility.md">Compatibility</a>
  · <a href="https://github.com/GloriousApps/HyperOS-MTZ-Studio/issues">Report an issue</a>
</p>

## v5.6 interface

### New neon design
<p align="center">
  <img src="docs/screenshots/v6/neon-panel.png" alt="New neon Panel" width="24%">
  <img src="docs/screenshots/v6/neon-library.png" alt="New neon Library" width="24%">
  <img src="docs/screenshots/v6/neon-builder.png" alt="New neon Create Your Theme" width="24%">
  <img src="docs/screenshots/v6/neon-tools.png" alt="New neon Tools" width="24%">
</p>

### Classic design
<p align="center">
  <img src="docs/screenshots/v6/classic-panel.png" alt="Classic Panel" width="24%">
  <img src="docs/screenshots/v6/classic-library.png" alt="Classic Library" width="24%">
  <img src="docs/screenshots/v6/classic-builder.png" alt="Classic Create Your Theme" width="24%">
  <img src="docs/screenshots/v6/classic-tools.png" alt="Classic Tools" width="24%">
</p>

## What it does

- **Four-menu workspace:** Panel, Library, Create Your Theme and Tools use a redesigned navigation layout with a reversible classic-layout switch.
- **Panel quick actions:** MTZ Import, BAK Converter and Backup are available side by side. Root runtime details stay compact and open from an info action when needed.
- **Collection-focused Library:** Larger two-column previews and All, Created and Imported filters make themes easier to find.
- **Composition flow:** Create Your Theme shows selected sections, total progress and component-selection state live.
- **MTZ Import:** validates MTZ packages, adds them to the private library and produces preview data. Choose one file or batch-import up to five files. On supported modern Xiaomi Themes builds, the theme is also mirrored into Xiaomi's local library.
- **BAK Converter:** converts supported Xiaomi Themes `.bak` backups into MTZ packages and adds the result directly to the library.
- **Theme Language Tool:** translates visible theme text into the app language. It supports XML, JSON and safe MAML content with translation memory and optional API providers. When translation finishes, Studio invalidates the stale Xiaomi-library copy and re-syncs the updated MTZ through a supported Shizuku/Shevery flow.
- **Create Your Theme:** creates a new MTZ by combining selected lock screen, icon, status bar, `clock_2x4` widget, font, wallpaper and other components from different themes. Each picker shows only themes that actually contain the requested component, and cards retain a fixed height with or without previews. On supported modern Xiaomi Themes builds, the created theme is also prepared in Xiaomi's local library.
- **Library:** presents the currently applied theme plus imported and created themes separately from font-only packages. Manage Library supports multi-select removal and, on supported devices, importing local themes from Xiaomi Themes.
- **Backup and updates:** provides cloud/WebDAV backup and restore for the Studio library. Every foreground entry checks GitHub in the background, announces a new version and shows verified APK download progress as a percentage.
- **Live Diagnostics:** records import, conversion, translation and application stages to make Theme Manager issues easier to diagnose.

### Root MTZ Import Module 2.0.0 and Theme Manager V7

On rooted devices without Xiaomi Themes' local MTZ import route, Studio can install or update the **GloriousApps** Root MTZ Import Module `2.0.0` directly in the app. The module never replaces or resigns the APK; it enables the verified Xiaomi Themes local import and apply flow. A reboot may be required after installing the Global bridge. Supported runtime families are Global `3.4.1.23-global` and Non-Global `11.5.3.1`.

If a valid Mods-Center Theme Manager V7 module and Themes `11.5.3.1` runtime are already installed, Studio detects and adopts the existing setup without overwriting its Themes APK. Theme application continues through Xiaomi Themes' modern local-library path.

## Access modes

| Mode | Available workflow |
| --- | --- |
| **Root** | Advanced Theme Manager operations, compatible apply flows and diagnostics through the detected root manager. A valid V7 runtime is preserved when present. |
| **Shizuku / Shevery** | BAK Converter, local MTZ operations, theme protection and supported application flows without root. |
| **No authorization service** | The MTZ library, preview, translation, composer, export and backup remain available. Applying a theme requires Shizuku or Shevery. |

On non-rooted devices, install and start Shizuku or [Shevery](https://github.com/HmnDev-Tech/shevery/releases) with Wireless debugging, then grant MTZ Studio permission. The app automatically detects an installed compatible manager.

## Theme Manager compatibility

Studio does not rely only on a fixed Xiaomi Themes version allowlist. It first resolves the installed package's `ApplyThemeForScreenshot` activity at runtime; when that activity is absent, it uses root access and the installed Xiaomi Themes Runtime + MTZ Support module. Xiaomi Themes `3.4.1.23-global` and `11.5.3.1` runtime families are supported. A valid Theme Manager V7 setup is adopted without overwriting the existing runtime. Forced downgrade and APK-backup flows are not included.

Xiaomi can change the final application screen and theme-acceptance behaviour depending on the ROM. Studio verifies capabilities and outcomes at runtime whenever possible, but a Xiaomi confirmation screen may still appear.

## Install

1. Download `MTZ_Studio_v5.6.0.apk` from the [v5.6.0 release](https://github.com/GloriousApps/HyperOS-MTZ-Studio/releases/tag/v5.6.0).
2. Back up an important Studio library before upgrading from an older build.
3. Install the APK, open Studio and let it detect the available access mode.
4. On non-rooted devices, grant the requested Shizuku/Shevery authorization before applying themes.

Official releases from v2.1.0 onward use the same signing key and normally update an existing installation. Android always requires your final confirmation before installing an APK.

## Build

Requirements: JDK 17, Android SDK API 36 and Android 8.0 / API 26 or newer.

```powershell
.\gradlew.bat test assembleDebug
```

Release signing uses `MTZ_RELEASE_STORE_FILE`, `MTZ_RELEASE_STORE_PASSWORD`, `MTZ_RELEASE_KEY_ALIAS` and `MTZ_RELEASE_KEY_PASSWORD`.

## Documentation

- [Architecture](docs/architecture.md)
- [Theme Manager compatibility](docs/theme-manager-compatibility.md)
- [Localization](docs/localization.md)
- [Theme Language Tool](docs/theme-language-translation.md)
- [Privacy and threat model](docs/threat-model.md)

## Responsible use

Use themes, fonts, icons and artwork only when you own them or have permission from their creators. Xiaomi and HyperOS are trademarks of their respective owners. This independent project is not affiliated with or endorsed by Xiaomi.

<p align="center">
  Built for HyperOS users and theme makers by <a href="https://github.com/GloriousApps">GloriousApps</a>.
</p>
