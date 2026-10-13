# HyperOS MTZ Studio v5.5.0

## Redesigned navigation and theme workflow

- Added a reversible four-menu redesign for **Panel**, **Kitaplık**, **Temanı Oluştur**, and **Araçlar**.
- Kept the classic layout available from `Araçlar → Yeni menü tasarımı`.
- Panel quick actions now show **MTZ Import**, **BAK Converter**, and **Yedekleme** side by side.
- Added a compact Root MTZ Import runtime status card with technical details behind an info button.
- Preserved the visible Root runtime installation/update path while reducing diagnostic text in the main panel.

## Library redesign

- Replaced the superficial library banner with a real collection toolbar.
- Added source filters for **Tümü**, **Oluşturulan**, and **İçe aktarılan** themes.
- Increased theme preview size by switching the redesigned library to a two-column gallery.
- Kept apply, translate, delete, details, device import, and library management actions intact.

## Theme composition redesign

- Added a live **Kompozisyon akışı** progress card showing selected sections.
- Added a clear theme-section workflow header and selection progress indicator.
- Preserved base-theme selection, component selection, wallpaper selection, save, share, and compose behavior.

## Root runtime fixes

- Fixed live Themes APK verification for updated system apps where `PackageManager` and `pm path` report different APK paths.
- Root runtime status now correctly reports an up-to-date Global runtime instead of incorrectly showing a restart state.

## Verification

- `:app:testDebugUnitTest`
- `:tester-adapter:testDebugUnitTest`
- `git diff --check`
- GitHub Actions signed release build
- ADB smoke verification on Xiaomi 2511FPC34G
