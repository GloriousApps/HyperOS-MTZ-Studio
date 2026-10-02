# HyperOS MTZ Studio v5.2.3

## Fixes

- Root bridge readiness now follows the in-process Xiaomi Themes marker instead of treating an installed module as automatically active.
- When a Global Themes build does not expose an importer compatible with the installed root bridge, MTZ Studio uses the HyperOS BAK restore path instead of submitting a request that fails with a local-theme-directory error.

## Notes

- The BAK restore path adds the theme back to Xiaomi Themes' local catalog and opens Themes. Select the restored theme there to apply it.
