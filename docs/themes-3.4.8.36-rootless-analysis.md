# Xiaomi Themes 3.4.8.36-global — static analysis and the rootless MTZ question

Source: `Themes_3.4.8.36-global.apk` supplied by the user (71,901,075 bytes).
`package=com.android.thememanager`, `versionCode=3040836`, `versionName=3.4.8.36-global`,
`minSdk=27`, `targetSdk=36`, `sharedUserId=android.uid.theme`, 13 DEX files, 5445 entries.

No JDK/aapt/apktool was available, so the binary `AndroidManifest.xml` and the DEX string /
type / method / field tables were parsed directly. Everything below is read out of the APK
unless it is marked as needing device verification.

## 1. The legacy tester contract is gone — not hidden, removed

| Probe | Result |
| --- | --- |
| `<activity>` named `ApplyThemeForScreenshot` | absent |
| `<activity-alias>` of any kind | **0 in the whole manifest** |
| action `com.android.thememanager.support3.0` | absent |
| DEX strings `ApplyThemeForScreenshot`, `support3.0`, `theme_file_path`, `api_called_from` | **no match in any of the 13 DEX files** |

So on this build there is nothing to detect and nothing to fall back to: the extras
`theme_file_path` / `api_called_from` are not referenced by any code path any more. The
runtime resolver (`ThemeManagerCapabilityProbe.resolveLegacyTester`) will correctly report
"no tester" here — this is the genuine case that message was written for.

## 2. Why no third-party app can apply an MTZ itself

The apply implementation is not reachable by design, for three independent reasons:

1. **UID.** Themes declares `sharedUserId="android.uid.theme"` and is platform-signed. Every
   privileged entry point it exposes is gated on `miui.permission.USE_INTERNAL_GENERAL_API`
   (signature|privileged) — that covers `ThemeRuntimeDataProvider`, `ThemeProvider`,
   `ThemeGlimProvider`, `ThemeOtaUpdateProvider`, `ExternalResourceDownloadService` and every
   other exported provider/service that touches theme data.
2. **Hidden framework APIs.** Apply goes through `miui.content.res.ThemeRuntimeManager`,
   `miui.content.res.ThemeNativeUtils.updateFilePermissionWithThemeContext`,
   `miui.content.res.IconCustomizer` and `ThemeResourcesSystem.resetIcons()`. These are
   boot-classpath MIUI classes whose effects land outside the calling app.
3. **Target directory.** The applied theme is written to `/data/system/theme/*`
   (`/data/system/theme/fonts`, `/large_icons/`, `/rights/`, `/lockscreen`, `/config.config`).
   That tree belongs to the theme UID. No permission a third-party app can hold reaches it.

There is also a caller allowlist pattern throughout the code — the only package names near
`getCallingPackage` / `getCallingUid` are first-party (`com.android.systemui`,
`com.miui.home`, `com.miui.miwallpaper`, `com.android.settings`, `com.miui.aod`,
`com.xiaomi.mipicks`, …). `restore_default_theme` is explicitly gated this way
(`isSupportRestoreDefaultTheme callingPackage is …`).

**Conclusion: a full MTZ apply performed *by MTZ Studio itself, silently*, is not possible
without root on this build, and that is not a gap we can close — it is the security boundary.**
What is possible is to make **Themes itself** do the apply, with the user tapping the button.
The rest of this document is about the three channels that remain for that.

> Device testing later ran that second path end to end: the deep link plus one tap on Themes' own
> Apply button wrote all 16 theme components. **The test phone was rooted** (KernelSU Next,
> Vector, LSPosed CorePatch), so this does not yet establish that stock Themes would accept a
> third-party local theme without root — a rights check is exactly what CorePatch could have been
> neutralising. See [rootless-capability-device-test.md](rootless-capability-device-test.md), its
> warning box and §9.

## 3. Channel A — bind `ThemeService` directly (no root, no Shizuku)

This is the one genuinely new finding, and it needs no privileges at all:

```
<service android:name="com.android.thememanager.service.ThemeService" android:exported="true">
  <intent-filter><action android:name="com.miui.service.THEMESERVICE" /></intent-filter>
</service>
```

**Exported, with no `android:permission`.** `ThemeService$1` extends
`miui.content.res.IThemeService$Stub` and overrides exactly four methods:

```
boolean saveWallpaper(String)
boolean saveLockWallpaper(String)
boolean saveIcon(String)
boolean saveCustomizedIcon(String, Bitmap)
```

So the **wallpaper, lock wallpaper and icon components of an MTZ can be handed to Themes and
applied with an ordinary `bindService`**, from an unprivileged app. That is a partial apply
(wallpaper + icons), not a theme apply — no fonts, no framework/SystemUI overlays, no
lockscreen style — but it is real, it is current, and MTZ Studio does not use it today.

Implementation note: `miui.content.res.IThemeService$Stub` is a framework class, so get the
interface by reflection on the device (`Class.forName("miui.content.res.IThemeService$Stub")`
→ `asInterface(binder)`) rather than hard-coding transaction codes — DEX method order is not
AIDL declaration order, so the codes cannot be derived from this APK.

**Needs device verification:** whether `ThemeService` checks the caller UID inside those
methods (`ThemeService#a/b/c/d/e` are boolean helpers taking a `String`, most likely path
validation, but the bytecode was not decompiled), and what path form it expects.

## 4. Channel B — get the MTZ registered as a local resource, user taps Apply

The import pipeline is still present and still broadcasts progress:

```
action_resource_import_start / _udpate [sic] / _complete / _fail
extra_import_current_bytes, extra_import_total_bytes
import_new_download_scanner_tag
"IdleUpdateThemeTask:import completion and apply"
```

Two staging directories exist:

* `theme/.download/` — watched by the "new download scanner"
* `theme/.data/import/theme/`

The storage base **moved**. `/sdcard/MIUI/` is legacy and actively deleted
(`"migrate MIUI/theme success, delete it."`, `"empty:  MIUI/theme"`,
`StorageMigrationTipActivity`); the live base is
`/sdcard/Android/data/com.android.thememanager/files/MIUI/` — confirmed by concrete runtime
paths in the APK such as
`/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/.ringtone`.

That has a hard consequence: **`MANAGE_EXTERNAL_STORAGE` is not enough.** On Android 11+ no
third-party app may write into another package's `Android/data`. The `shell` UID can, so this
channel is **Shizuku/Shevery-only**, not plain-rootless.

The MIUI backup channel is the other way in and is also still present:
`android:allowBackup="true"` with `android:backupAgent="com.android.thememanager.util.ThemeBackupAgent"`,
which extends `miui.app.backup.FullBackupAgent` (`addAttachedFile(String)`). This is what
MTZ Studio's existing `MtzToBakConverter` + `SheveryBackupRestorer` route uses, and nothing in
3.4.8.36 removes it. Related internals: `theme_restore`, `trial_backup/`, `backup.mtz`,
`ApplyThemeInfo.THEME_USING_TYPE_RESTORE` / `_FORCE_RESTORE` / `_TRIAL`.

## 5. Channel C — deep-link the user onto the Apply button

`com.android.thememanager.activity.ThemeDetailActivity` is **exported with no permission** and
keeps a local-resource deep link:

```
<data android:scheme="ViewLocalResource" android:host="view.local.resource" android:pathPattern=".*" />
```

DEX confirms the template `ViewLocalResource://view.local.resource#` plus the extras carrier
`?S.REQUEST_RESOURCE_CODE=theme`, and the keys `REQUEST_RESOURCE_CODE`, `REQUEST_APPLY_EVENT`,
`REQUEST_APPLY_PARAMS`, `EXTRA_TAB_ID`, `extra_outer_tab_id`, `REQUEST_RES_OBJECT` are all
still in the binary.

So once a theme is a local resource (channel B), this opens its detail page directly and the
apply is one user tap instead of "go find it in Themes yourself". `ThemeResourceTabActivity`
(exported, MAIN + `theme://zhuti.xiaomi.com/mainpage`) remains the safe fallback target.

Exact path/fragment encoding **needs device verification** — the string table gives the
template but not the parameter layout.

## 6. What this means for MTZ Studio

* The honest capability statement for 3.4.x is: **full MTZ apply = root.** Not a bug, not a
  missing fallback. Saying anything softer in the UI would be a false promise.
* Rootless should be presented as the two things that actually work: **Shizuku/Shevery →
  backup-restore registration → deep-link to Apply** (channel B + C), and the new
  **unprivileged wallpaper/icon apply** (channel A).
* Channel A is worth building: it is the only path on this build that needs neither root nor
  Shizuku, and MTZ Studio can already extract wallpaper and icon components from an MTZ.
* Version tables cannot carry this. 3.4.8.36 is not in any list, and the runtime resolver is
  what produces the right answer for it — keep detection at runtime.

## 7. What is implemented (channels A and C)

| Piece | Where |
| --- | --- |
| ThemeService contract + manifest-verified availability | `tester-adapter/.../tester/ThemeServiceAvailability.kt` |
| Bind + reflective call + honest outcome | `app/.../themeservice/ThemeServiceClient.kt` |
| MTZ wallpaper staging, icon sending, combined apply | `app/.../themeservice/RootlessPartialApply.kt` |
| Icon archive → package map (pure, tested) | `app/.../themeservice/IconPackIndex.kt` |
| Nested icons ZIP reading and bitmap decoding | `app/.../themeservice/MtzIconExtractor.kt` |
| Detail deep link contract + availability | `tester-adapter/.../tester/LocalResourceDeepLink.kt` |
| Deep link wired into the rootless BAK restore | `ThemeApplyCoordinator.prepareRootlessManualImport` |
| Capability signal | `ThemeManagerRuntimeProfile.unprivilegedWallpaperService` |
| UI | up to three actions in the apply dialog + a line on the compatibility card |

Decisions worth keeping:

* **Availability is manifest-verified, not resolution-verified.** `usable` requires the service
  to be declared, exported, enabled, to carry **no** `android:permission`, and the ROM to ship
  `miui.content.res.IThemeService`. Intent resolution alone is recorded but never sufficient,
  for the same reason the tester resolver does not trust it.
* **No hard-coded transaction codes.** The call goes through the framework's
  `IThemeService$Stub.asInterface` and methods are resolved by name and arity.
* **A `false` return is reported as a rejection.** `ThemeServiceOutcome` keeps every call's
  result separately; nothing is reported as applied unless Themes answered `true`.
* **The argument shape is tried, not guessed twice over.** The absolute path is sent first and
  the `file://` form only after an explicit rejection, because the APK does not state which the
  service expects. Both attempts are recorded in diagnostics.
* **The staged wallpaper is kept**, in `Pictures/MTZ Studio`, and media-scanned: some builds read
  the path asynchronously, and it is a file the user just chose to apply.
* The wording everywhere says *wallpaper applied*, never *theme applied*
  (`theme_service_partial_notice`).

### Icons

`saveCustomizedIcon(String, Bitmap)` takes a package name — `IconCustomizer.clearCustomizedIcons(String)`
is its counterpart and `/data/system/theme/large_icons/` is where the result lands, both present
in the APK. The MTZ side is a nested, extensionless `icons` ZIP whose internal layout is written
by designers and read by the framework, so the Themes APK says nothing about it and there is no
single authoritative shape. `IconPackIndex` therefore **discovers** the mapping instead of
assuming one:

1. an `icon_map` / `iconmap` (`.xml`) index, parsed as
   `<item name="com.pkg/com.pkg.Activity">drawable</item>`, with activity entries collapsed onto
   their package and the first entry per package winning;
2. otherwise, drawables named after the package they replace (`com.miui.home.png`).

Then: only packages actually installed on the device survive, a decorative drawable name is never
treated as a package (`isPackageLike` requires a dotted form), and the send is capped at 120
icons with 2 MiB / 1024 px per bitmap. Bitmaps are recycled after the call. Without those
filters a theme's few hundred drawables would become a few hundred binder calls.

`saveIcon(String)` is left unused: unlike the other three its parameter semantics are not pinned
down by anything in the APK.

### Deep link (channel C)

Rather than reconstruct MIUI's `?S.REQUEST_RESOURCE_CODE=theme` URI form — whose layout the APK
does not state — `LocalResourceDetailContract.detailIntent` targets `ThemeDetailActivity`
explicitly and passes `REQUEST_RESOURCE_CODE` and `REQUEST_APPLY_EVENT` as **real Intent extras**.
That `?S.` form only exists so MIUI can pack extras inside a URI string for web contexts; an app
can pass them directly, so the unknown disappears. The URI then only carries the resource id in
its path, which `pathPattern=".*"` accepts, and the id is validated against
`[A-Za-z0-9_.-]{1,128}` before anything is built.

It is wired where it pays off: after a successful rootless BAK restore, `MtzToBakConverter`
already knows the `localId` it wrote into the Themes metadata, so when no tester exists the user
lands on that theme's own detail page instead of the Themes home screen — one tap to apply. The
new protocol is `ROOTLESS_LOCAL_RESOURCE_DEEP_LINK`, and because `RootlessRestoreAssistant`
persists the prepared intent as a URI, the deep link survives a reboot with no extra work.

Opening a page is not an apply, so that protocol's return reports
`status_legacy_apply_unverified` and claims nothing.

> **Superseded in part by device testing.** See
> [rootless-capability-device-test.md](rootless-capability-device-test.md): the deep link's id
> goes in the URI **fragment**, and `saveWallpaper(String)` is unusable because Themes holds no
> storage read permission, so wallpapers go through `WallpaperManager` instead. The list below is
> what was open before that session.

### Still to verify on a device

* Whether `ThemeService` checks the caller UID inside `saveWallpaper`/`saveLockWallpaper` — the
  bytecode was not decompiled, only the method table. `ThemeService#a/b/c/d/e` are boolean
  helpers taking a `String` and are most likely path validation.
* Which argument form the service accepts, and whether it reads `Pictures/MTZ Studio` by path.
  The `theme_service_call` diagnostics record `argumentForm` and `accepted` for exactly this.
* Whether `saveCustomizedIcon` wants the package name or a `package/activity` string, and whether
  it needs `IconCustomizer.prepareCustomizedIcons` to run before the icons show up. The
  `theme_service_icons_extracted` record carries the discovered `source`, how many packages were
  found and how many were installed.
* Which icon-pack layout real MTZ files on the device actually use — `source` in that record
  answers it per theme (`ICON_MAP` or `PACKAGE_NAMED_DRAWABLES`).
* Whether `ThemeDetailActivity` accepts the local id from the path alone, or wants it in a
  `REQUEST_RES_OBJECT` extra instead. `rootless_local_apply_ready` logs the `localId` used.
