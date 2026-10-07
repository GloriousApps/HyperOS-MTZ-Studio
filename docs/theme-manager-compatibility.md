# Theme Manager compatibility

HyperOS MTZ Studio is an independent project. Its compatibility policy is defined locally and has no dependency on another application or repository.

## Global-version matrix

| Canonical version | Observed tester interpretation |
| --- | --- |
| `2.15.5.46` | Imports the MTZ as an independent local theme; recommended |
| `3.0.4.32` | Uses the legacy exported `support3.0` local-import contract |
| `3.0.5.6` | Imports the MTZ through the device-verified legacy tester contract |
| `3.0.5.14` | Applies a temporary/composite result over “Default” |
| `3.0.6.8` | Tester activity is removed |
| `3.4.1.23` | Root module target (Global). Tester activity removed; applied exclusively through the bundled Root MTZ Import module's Zygisk bridge |

Suffixes such as `-global` are ignored only for matrix matching. Unknown versions are shown as unverified rather than guessed.

## Root module target builds

The bundled Root MTZ Import module is verified against two Theme Manager builds. When the module is installed from inside MTZ Studio, the app first brings Theme Manager to the matching build:

| Family | Target build | APK | minSdk | SHA-256 |
| --- | --- | --- | --- | --- |
| Global | `3.4.1.23-global` | `Xiaomi_Themes_3.4.1.23-global.apk` | 27 | `d405e78fac1ea48e105e57f5e3422ec0a0a6d53f779973d06e131e85b016037` |
| China | `11.5.3.1` | `Xiaomi_Themes_11.5.3.1.apk` | 34 | `3888058041439577aadbb933c9bed1fb3ef16acda20d15404fb620314a8edd0a` |

Both APKs are signed with the same Xiaomi certificate (`c9009d01...`) as the existing `3.0.5.6-global` build. The China build requires Android 14+ (minSdk 34); on older devices the Root module flow falls back to the Global build only when the installed family is Global.

## Modern Theme Manager matrix

| Canonical version | v4.0.2 behavior |
| --- | --- |
| `10.8.7.6` and later | Uses Xiaomi Theme Manager's local catalog as the source of truth when the verified native import surface is available. Global Theme Protection and the manual Theme Manager import card are hidden. |

The native surface was inspected in `10.8.7.6`, `10.9.2.0`, `10.9.4.0`, `10.9.5.2`, `11.0.8.0`, and `11.1.5.0`. The bridge still checks the required host classes and only runs inside `com.android.thememanager`; a future numeric version is not trusted if the runtime surface is absent.

## Startup behavior

The app queries `com.android.thememanager` through Android `PackageManager`. Versions `2.15.5.46`, `3.0.4.32` and `3.0.5.6` use the exported legacy tester contract. The `3.0.2.34` Global APK was statically inspected and exposes the same `support3.0` action and exported `ApplyThemeForScreenshot` alias used by the `3.0.4.32` branch. The request is intentionally frozen to that action, alias, and the original four extras verified on a `3.0.5.6-global` device. Additional path keys, apply booleans, or activity flags can make that version return without applying the MTZ.

When Theme Manager `10.8.7.6` or a later modern build is detected, v4.0.2 verifies the native local-library activity at runtime before marking the profile compatible and switching to the native-library provider. MTZ Studio mirrors verified resources into a private editor cache so personalization can read MTZ components, while Xiaomi Theme Manager remains the visible and authoritative catalog. The “import from Theme Manager” step is removed. A theme added or composed in MTZ Studio is retained in `Downloads/MTZ Studio`, imported through Theme Manager's native importer, associated with the returned local resource ID, and then shown in the shared catalog.

Apply and remove actions use the corresponding native Theme Manager resource. Removing an item deletes the Theme Manager record and its private editor mirror; the public MTZ backup is deliberately retained. Requests are accepted only when the activity was started for result by the MTZ Studio package, and imported files are authenticated by canonical path and SHA-256.

The modern Themes screen opens directly onto the theme grid, without a catalog information card or refresh button. It automatically synchronizes on entry and when the app resumes on that screen. Concurrent refresh requests are coalesced; existing previews remain visible with a thin progress indicator while synchronization runs. A failed scan is reported instead of silently implying the displayed catalog is current. The Global provider retains its manual import card.

Native deletion uses `DeleteResourceTask` with a runtime `Void[]` argument to `AsyncTask.execute`; its erased reflective signature accepts `Object[]`, but sending an actual `Object[]` causes a worker-thread `ClassCastException` in Theme Manager. The fix was device-tested on `10.8.7.6` by composing/importing a disposable theme, deleting it through Studio, and verifying its native metadata disappeared while Studio remained open. The public MTZ backup was retained.

## Live Diagnostics

Recording starts with the app process, not when the diagnostics screen is opened. Startup/provider selection, catalog synchronization, composition checkpoints, privileged preparation stages, import, apply and delete results share a persistent private journal. The screen shows the last 200 events with details; export includes the retained journals (two bounded 2 MiB generations). URI values in general diagnostic messages and exceptions are redacted.

Modern bridge requests carry a `ResultReceiver` capability to return intermediate steps without adding an exported broadcast receiver. The activity result also carries a bounded trace as fallback; repeated steps are deduplicated. Steps retain their host timestamp if delivery is deferred. If the host fails to return success, Studio attempts to read recent crash entries for Studio/Theme Manager only, within that request's time window. Missing crash evidence is explicitly inconclusive. Global legacy tester returns do not prove successful application and are labeled accordingly.

Device validation covered startup, composing/importing/deleting a disposable theme, trace display, exporting the diagnostic file, and retaining those events after process restart. No active-theme change was needed for this diagnostic test.

This path was initially validated on a physical device with Theme Manager `10.8.7.6` and Vector API 102. It requires root plus an enabled modern Xposed environment. MTZ Studio advertises `android`, `system` and `com.android.thememanager` as editable recommended targets so users can recognize the required entries across Global and modern ROM families. It deliberately does not ship libxposed's fixed `scope.list`, avoiding Vector's “module declared a static scope” lock. Vector scope preparation can be completed by the app through its privileged runner; compatible LSPosed forks can use their normal scope approval flow.

The legacy 2.15/3.0 Global protocol remains frozen to its v1.2.0 behavior and is not routed through the modern bridge. The MTZ library and composer remain usable when the installed Theme Manager version is unknown.

### Module installation and automatic updates

Provider selection follows the **active installed APK**, not the presence of a root-module directory. A module can be enabled while the device still runs Global Theme Manager.

On the test device (2026-08-30), the v6 Theme Manager module had captured a temporary `/data/app/.../base.apk` update path during installation and placed its replacement under the module's `system/data/app/...` tree. That did not replace the factory APK at `/product/app/MIUIThemeManagerGlobal/MIUIThemeManagerGlobal.apk`. A direct installation temporarily enabled `10.8.7.6`, but Package Manager later recorded GetApps (`com.xiaomi.mipicks`) installing `3.0.5.6-global` at 08:14. These are device installation/update issues, not evidence that the app selected the wrong provider.

Before modern-provider testing, verify the active package version and path, the module's actual mount target, and Theme Manager's automatic-update setting. Do not uninstall Theme Manager or clear its data to repair a mount. MTZ Studio does not silently disable app-store updates or change root-module mounts.

With the user's approval, the test device's module was backed up and relocated to `system/product/app/MIUIThemeManagerGlobal/`, retaining the supplied native libraries. After an explicit APK replacement and reboot, Package Manager reported `10.8.7.6` for both the active update and factory-system entry. The mounted system APK's SHA-256 matched the module APK, and Hybrid Mount reported `is_mounted=true` with no mount error. No Theme Manager data was cleared. This verifies reboot persistence on this device, not compatibility with every ROM or protection against future store updates.

## Privileged access channels

Shevery is not mandatory. The app uses the standard Shizuku API for a compatible root-mode service (including original Shizuku), or direct superuser access through the device root manager when that service is unavailable. ADB-mode Shizuku reports UID 2000 and is not sufficient for the private catalog operations. Xposed scope approval is separate from this command access: on modern Theme Manager versions, only the Themes scope is required; System Framework remains disabled intentionally.

The command runner selects a channel using read-only UID probes and never retries a dispatched operation through another channel after an uncertain failure. Permission/binder changes are tracked beyond startup; an unavailable channel produces an authorization dialog with a retry action. Modern file imports check access before opening the picker and again before copying the selected document. A new private import record is removed if native preparation fails before launch; user source files and public backups are preserved. This does not roll back an already dispatched native import or discard a composed theme after an unconfirmed host result.

## Rootless workspace

When UID 0 cannot be verified, the private Theme Manager provider is not selected. This is a capability change, not an application failure: MTZ parsing, the private Studio library, previews, personalization, composition, public export, backup, restore and ordinary diagnostics remain available. Modern Theme Manager detection no longer hides these local themes or blocks the document picker.

For an apply request, Studio verifies the immutable private source again and retains a public copy in `Downloads/MTZ Studio`. On verified legacy `2.15.5.46`/`3.0.5.6` builds, the exported tester contract is attempted with that public path and its return remains explicitly unverified. Other builds first try an exported file-open activity from `com.android.thememanager`; if none exists, Studio opens the best accessible Xiaomi Themes local/main screen. A manual return is never treated as verified application success.

Private catalog synchronization, extraction of installed themes or the active font, automatic native import/apply/delete, package downgrade, Xposed preparation and Global persistence protection stay hidden or inactive without root. Shizuku running as UID 2000 is classified as rootless and is not presented as sufficient access.

## Root downgrade boundary

HyperOS MTZ Studio does not download or bundle Xiaomi APKs. The user must select an APK they are entitled to use. Before enabling the root action, the app checks:

1. The installed Theme Manager package exists.
2. The APK package exactly matches `com.android.thememanager`.
3. The canonical APK version is exactly `2.15.5.46`.
4. The APK signing certificate matches the installed Theme Manager signing history.
5. The staged APK remains unchanged according to SHA-256.
6. The user accepts the risk and confirms the exact target a second time.

The fixed root command is `pm install -r -d --user 0 -S <verified-size> -`; verified APK bytes are streamed over stdin. No file path or user-controlled text enters the command. The app does not uninstall Theme Manager, write system partitions, disable package verification, clear app data, or attempt an invasive fallback. Some production ROMs can still reject downgrades; this is reported as a normal failure.
