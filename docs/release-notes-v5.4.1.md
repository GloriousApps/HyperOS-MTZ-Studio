# HyperOS MTZ Studio v5.4.1

## Mods-Center Theme Manager V7 compatibility

- Detects the external `miui_theme_mod` V7 module (`versionCode=7`) without replacing it.
- Adopts the verified `com.android.thememanager` `11.5.3.1` / `11531` runtime when its APK hash and minimum SDK match the inspected V7 package.
- Prevents the bundled runtime installer from overwriting an adopted external runtime.
- Shows the adopted runtime state in the Root MTZ Import panel and hides bundled install/update controls while V7 owns the runtime.
- Routes health checks to the adopted non-Global runtime family while preserving the existing Global ROM device context.

## Verification

- `:app:compileDebugKotlin`
- `:tester-adapter:compileDebugKotlin`
- `:app:testDebugUnitTest`
- `:tester-adapter:testDebugUnitTest`
- `git diff --check`

## Notes

- The external V7 runtime was inspected from the supplied ZIP. Physical-device adoption validation remains dependent on a device with the module installed.
