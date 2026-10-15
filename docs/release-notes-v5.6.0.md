# HyperOS MTZ Studio v5.6.0

## Panel and navigation

- Added a consistent neon and classic panel layout with MTZ Import, BAK Converter, and Backup actions kept side by side.
- Added real device/version and Root manager or Shevery/Shizuku details to the redesigned device-status card.
- Removed repeated Root/Shevery status badges from panel content; the status beside the app logo is now the single primary access indicator.
- Moved the manager-launch action into the device-status area for both classic and redesigned layouts.
- Replaced placeholder dashboard statistics with real gallery-theme count, last applied theme, and workspace status values.
- Excluded font-only and component-only packages from the dashboard theme count, matching the Library gallery count.

## Theme builder and visual design

- Restored the classic card structure in the redesigned Theme Builder while keeping the neon visual treatment.
- Fixed two-column component cards so titles and selected values stay readable instead of wrapping character by character.
- Added coordinated Light mode colors for navigation text, Root runtime cards, surfaces, borders, and status content in both layouts.
- Kept the redesigned and classic interfaces switchable from Tools.

## Root runtime reliability

- Recognized the post-boot `runtime-active` marker so an installed and verified Root module no longer incorrectly asks for another restart.
- Kept bridge readiness as a separate health signal while avoiding false reinstall or reboot prompts.
- Preserved the existing verified Global and Non-Global runtime paths and external Theme Manager V7 adoption behavior.

## Verification

- `:app:compileDebugKotlin`
- `:app:verifyTranslations`
- `git diff --check`
- GitHub Actions signed release build
- ADB smoke verification on Xiaomi 2511FPC34G
