# HyperOS MTZ Studio v5.4.0

## Fixes

- Stabilized Xiaomi Themes Global root runtime verification on Android `mksh`.
- Fixed Package Manager runtime version parsing and live APK verification.
- Fixed Global Zygisk bridge readiness detection for the actual `1.0.0` bridge marker.
- Applied the required SELinux labels to the Global bridge payload during root module installation.
- Added regression tests for bridge marker validation.

## Theme application

- Verified applying the library theme **Circle UI - OS27** through the Global root path.
- Verified the existing library theme is visible as the active theme on the rootless Global path.
- Library application continues to avoid the file picker when the supported local route is available.

## Notes

- The Non-Global runtime package and application path are included, but a real Non-Global device validation is still pending.
- This release does not include the removed local test MTZ file.
