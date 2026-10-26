# HyperOS MTZ Studio v5.6.4

## Theme Manager apply compatibility

- Detects the real `ApplyThemeForScreenshot` activity before selecting a rootless apply route.
- Shows a short compatibility warning and information panel when Shevery/Shizuku cannot apply themes on the installed Xiaomi Themes build.
- Blocks the unsupported rootless apply request instead of opening a misleading manual hand-off.
- When the immutable factory Xiaomi Themes version is `3.0.5.14` or older, users with an ADB shell channel can explicitly restore it without deleting Theme Manager data.
- Keeps the verified Xiaomi Themes Runtime + MTZ Support module as the rooted route.

## Update

- Version code: `96`
- Version name: `5.6.4`
