# HyperOS MTZ Studio v5.6.1

## Root Theme Manager compatibility

- Fixed the compatibility card incorrectly showing the Root MTZ Import path as ready for unverified Xiaomi Themes builds such as `3.4.1.25`.
- The bundled Global root runtime is now treated as compatible only with the verified Xiaomi Themes `3.4.1.23` build.
- The bundled Non-Global runtime remains tied to the verified `11.5.3.1` build.
- Added an explicit warning showing the installed version and the exact Themes version required by the selected root target.
- Kept the verified APK selection and downgrade action visible when the installed Themes build does not match the root runtime.
- Root readiness now also requires a matching, verified, active runtime rather than only an installed module.

## Verification

- Added regression tests for exact root target matching and target-specific recommendations.
- `git diff --check` passes for the release changes.
- Android Gradle tests were attempted but could not start in the local environment because the configured Android SDK path is unavailable.
