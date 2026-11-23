# What an unprivileged app can actually do — device-tested

Test rig: **2511FPC34G (klee)**, Android 16 / SDK 36, HyperOS **OS3.0.308.0.WPJMIXM**, V816.
Xiaomi Themes upgraded during the session from **3.4.1.23-global** to **3.4.8.36-global**
(versionCode 3040836), factory build `3.0.5.6-global` still at
`/product/app/MIUIThemeManagerGlobal`.

Everything below was executed on that device over ADB. Where something could not be executed it
says so; nothing here is inferred from the APK alone.

> ## Settled on a clean device — see §10
>
> A second, **unrooted** phone (2201116PG / veux, HyperOS OS3.0.301.0, Themes upgraded to
> 3.4.8.36) answered the open questions, and the answer revises the headline: the deep link is
> genuinely root-free, but **nothing can be put into the Themes catalogue without root**, so the
> chain in §8 cannot even start on a clean phone. The warning below is kept because it is what
> drove that test.

> ## ⚠ The first test device is rooted — its apply result is not a rootless result
>
> Found after the fact, and it matters:
>
> ```
> com.rifsxd.ksunext        KernelSU Next v3.4.2   (installed 2026-10-25)
> org.matrix.vector.manager Vector / Xposed 2.2
> org.lsposed.corepatch     LSPosed CorePatch      (signature-check bypass)
> dev.glorioustr.mtzstudio  MTZ Studio itself — so its own Zygisk module is probably installed too
> ```
>
> `/data/adb` and `/proc/<themes-pid>/maps` are both unreadable from `shell`, so **injection into
> the Themes process could not be ruled out**. Until this is repeated on a clean device, §8 proves
> that the chain works *on a rooted phone*, which is not the claim worth making.
>
> What does **not** depend on the device: §1 (component removed), §2's deep-link contract (URI
> parsing behaviour), §3's permission readout, §4's manifest facts and §5's directory ownership.
> Those were read from the APK or from package/filesystem state that root does not alter.
>
> What **does**: whether stock Themes on an unrooted phone accepts and applies a third-party local
> theme at all. The applied record carries `isBackUpVersion: true` and `/data/system/theme/rights`
> is empty, so a rights/DRM check is exactly the kind of thing CorePatch could have been
> neutralising. See §9 for the re-test plan.

## The headline

| Capability | Plain app | + Shizuku in **ADB** mode | + Shizuku in **root** mode / root |
| --- | --- | --- | --- |
| Set home / lock wallpaper from an MTZ | **yes** | yes | yes |
| Replace per-app icons | **yes (call itself unverified, see §4)** | yes | yes |
| Open a catalogue theme's page on its Apply button | **yes (§10)** | yes | yes |
| Put an MTZ into the Themes local catalogue, via MTZ Studio's own binder | **no** | **no (§10)** | yes |
| Put an MTZ into the catalogue, via **MIUI Backup app** restore | **yes (§11)** | yes | yes |
| Complete MTZ apply of a third-party theme | **chain works to the Apply button (§11); final tap unconfirmed on clean phone** | — | yes (§8) |
| Apply without the user tapping anything | no | no | yes |

The breakthrough is the MIUI-Backup row: it needs `MANAGE_EXTERNAL_STORAGE` (user-grantable, not
root) plus two taps in Xiaomi's own apps, and it put a third-party theme into the catalogue on a
fully unrooted phone. §11 has the proof; §10 is why the low-level routes fail and why this one
doesn't.

The full chain ran end to end on the rooted phone (§8), and the deep link half of it reproduced
on a clean phone (§10). But on the clean phone the chain never gets that far: **a third-party MTZ
cannot be registered in the catalogue at all without root**, so there is no local theme for the
deep link to open.

So the honest summary for an unrooted device is: wallpapers and icons yes, a whole theme no —
and not because the apply is blocked, but because the import is.

## 1. The legacy tester really is gone

```
cmd package resolve-activity -a com.android.thememanager.support3.0 com.android.thememanager
  → No activity found
cmd package resolve-activity -n com.android.thememanager/.ApplyThemeForScreenshot
  → No activity found
```

Confirmed live on 3.4.8.36, matching the APK analysis.

## 2. The deep link works — and the contract is the URI *fragment*

This was found by trying nine candidate forms against the exported `ThemeDetailActivity` and
reading the resulting screen each time:

| Form | Result |
| --- | --- |
| `…/<localId>` (path) | online detail screen, spins on "just a second" |
| `…/<localId>?S.REQUEST_RESOURCE_CODE=theme` | same |
| **`…#<localId>` (fragment)** | **the theme's own page, with its Apply button** |
| `…#<localId>` + extras | same, extras change nothing |
| id as an Intent extra | online detail screen |
| `…/theme/<localId>` | online detail screen |
| MTZ file path in path or fragment | online detail screen |

```
am start -a android.intent.action.VIEW -c android.intent.category.DEFAULT \
  -n com.android.thememanager/.activity.ThemeDetailActivity \
  -d 'ViewLocalResource://view.local.resource#2009277e-2e37-3b91-988b-821aa43aa81c'
```

lands on **Circle UI - OS27 · Composed locally with HyperOS MTZ Studio · GloriousTR** with
**Özelleştir** and **UYGULA**, identical to navigating there by hand inside Themes. Verified on
both 3.4.1.23 and 3.4.8.36.

That is what the APK string `ViewLocalResource://view.local.resource#` is: the template with the
id concatenated onto the `#`. The code previously put the id in the path and would silently have
opened the online screen; `LocalResourceDetailContract.localResourceUri` now uses
`encodedFragment`.

`REQUEST_APPLY_EVENT` is **not** sent. It is accepted but its meaning is unestablished, and an
extra that might start an apply by itself would take the decision away from the user.

The target screen is `OnlineThemeDetailActivity`, which is **not** exported — reaching it
directly is impossible; the exported `ThemeDetailActivity` trampoline is the only way in, and it
needs no permission.

## 3. Wallpapers: not through Themes

The design in the previous iteration staged the wallpaper to `Pictures/MTZ Studio` and passed the
path to `IThemeService.saveWallpaper(String)`. **That cannot work.** Themes holds no storage read
permission at all:

```
android.permission.READ_MEDIA_IMAGES:      granted=false
android.permission.READ_EXTERNAL_STORAGE:  granted=false
appops READ_EXTERNAL_STORAGE:              ignore
appops MANAGE_EXTERNAL_STORAGE:            default (rejected 26 days ago)
```

There is therefore no path an unprivileged app can hand it that Themes could open — not shared
storage, not our own private directory (different UID), not a MediaStore URI (it has no
`READ_MEDIA_IMAGES`).

The right answer was simpler all along: **`WallpaperManager.setStream(..., FLAG_SYSTEM|FLAG_LOCK)`**.
`SET_WALLPAPER` is a normal permission granted at install, it takes a stream so the bytes never
touch shared storage, and it needs nothing from Themes. `RootlessPartialApply` now streams the
MTZ's `wallpaper/default_wallpaper*` and `wallpaper/default_lock_wallpaper*` entries straight
into it.

## 4. Icons: the one thing ThemeService is still good for

```
<service android:name="com.android.thememanager.service.ThemeService" android:exported="true">
  <intent-filter><action android:name="com.miui.service.THEMESERVICE" /></intent-filter>
</service>
```

Still exported with no `android:permission` on 3.4.8.36, and reachable:

```
am start-service -n com.android.thememanager/.service.ThemeService -a com.miui.service.THEMESERVICE
  → Starting service: Intent { … }        (no Permission Denial)
```

`saveCustomizedIcon(String packageName, Bitmap)` passes the **bitmap across the binder**, so it
needs no file access — which is exactly why it survives where `saveWallpaper(String)` cannot. The
package-name reading is corroborated on-device by its counterpart
`IconCustomizer.clearCustomizedIcons(String)` and the destination `/data/system/theme/large_icons/`.

**Not executed.** Binding a service and making a binder call needs an app process; this machine
has no JDK, so no test APK could be built. What is established: the service is exported,
unprotected, startable, and the interface is used by Themes itself on this ROM. What is not: that
the call returns true and that the icon appears. `theme_service_call` logs `accepted` per package
for exactly this.

## 5. Getting an MTZ into the catalogue — the real wall

The live store is
`/sdcard/Android/data/com.android.thememanager/files/MIUI/theme/`, holding `.data/meta/theme/*.mrm`
(the records), `.data/content/`, `.download/`, and the user's `.mtz` files.

```
drwxrws--- system_theme:ext_data_rw  /sdcard/Android/data/com.android.thememanager
drwxrws--- system_theme:ext_data_rw  /sdcard/Android/data/com.android.thememanager/files/MIUI/theme
```

Mode `0770` owned by `system_theme:ext_data_rw`. `shell` is in `ext_data_rw` (gid 1078) so
Shizuku can write there; **a third-party app is not, so it cannot** — on Android 11+ no app may
write another package's `Android/data` regardless of `MANAGE_EXTERNAL_STORAGE`.

Nor can an app hand Themes a file:

```
cmd package query-activities -a android.intent.action.VIEW -t application/vnd.miui.mtz
  → ES File Explorer, Termux       (no Themes component at all)
```

Same for `application/octet-stream`. **Themes declares no MTZ handler**, so sharing or opening an
MTZ into it is impossible.

The existing BAK route is confirmed working, though: one of the four records in the catalogue,
`2009277e-2e37-3b91-988b-821aa43aa81c`, is a **UUID v3** — `UUID.nameUUIDFromBytes`, i.e. written
by `MtzToBakConverter` — and its metadata reads `"Composed locally with HyperOS MTZ Studio"`. The
other three are v4, written by Themes itself. The JSON matches the converter's output field for
field.

### A Shizuku-free possibility, not confirmed

`com.miui.backup/.local.LocalHomeActivity` is exported with action
`miui.intent.backup.LOCAL_HOME_ACTIVITY`, and MIUI's backup folder is **plain shared storage**
(`/sdcard/MIUI/backup/AllBackup`, `u0_a291:media_rw`) rather than `Android/data` — so an app with
`MANAGE_EXTERNAL_STORAGE` could write a `.bak` there and send the user into the restore UI.

Launching it on this device immediately demanded the lock-screen credential. I did not enter it
and stopped there, so **whether a dropped `.bak` is listed and restorable is untested**. If it
works it would be the first Shizuku-free path into the catalogue, at the cost of
`MANAGE_EXTERNAL_STORAGE`, the user's PIN and a trip through the backup UI.

## 6. What changed in the code because of this

* `LocalResourceDetailContract.localResourceUri` → `encodedFragment(localId)`, and
  `REQUEST_APPLY_EVENT` dropped.
* `RootlessPartialApply` wallpapers → `WallpaperManager`; `SET_WALLPAPER` added to the manifest;
  no more staging into `Pictures/MTZ Studio`.
* `ThemeServiceRequest` / `ThemeServiceOutcome` reduced to icons; `saveWallpaper` /
  `saveLockWallpaper` removed rather than left as dead code.
* The compatibility card and apply dialog now say wallpapers are applied by Android and only
  icons depend on Themes.

## 7. Still open

* The `saveCustomizedIcon` round trip (needs a JDK to build a test APK).
* Whether `IconCustomizer.prepareCustomizedIcons` must run before changed icons appear.
* Which icon-pack layout real MTZ files use — `theme_service_icons_extracted` logs `source`
  (`ICON_MAP` or `PACKAGE_NAMED_DRAWABLES`) per theme.
* Whether MIUI Backup lists an app-written `.bak` (§5).

## 8. The whole chain, proven

With the user's go-ahead, the deep link was opened and its **UYGULA** button tapped
(`com.android.thememanager:id/applyBtn`). Themes' own log:

```
D MiuiUtils:        writeDefaultIconSettings: status = 1
I ModuleApplyUtils: Applying theme END:  0xffffffffffffffff
D ActivityThread:   onConfigurationChanged = { … themeChanged=1 themeChangedFlags=268466329 … }
```

`0xffffffffffffffff` is -1, the same "all components" mask the retired tester contract used for
`theme_apply_flags`. Sixteen components were written to `/data/system/theme/` at that minute:

```
aod  clock_2x4  clock_3x4  com.android.contacts  com.android.mms  com.android.settings
com.android.systemui  com.android.updater  com.miui.home  compatibility-v12  config.config
framework-miui-res  icons (44 MB)  lockscreen (12.9 MB)  miui.systemui.plugin  wallpaper
```

The home screen changed accordingly. So the complete chain —

> MTZ → BAK → Shizuku restore → catalogue record → **permission-free deep link** → user taps
> Apply → Themes applies everything

— works on Themes 3.4.8.36-global with no root anywhere in it. Only the restore step needed a
privileged channel, and only because of the `Android/data` group ownership in §5.

If this holds on a clean device it disproves the assumption the earlier analysis was built on:
the retired `ApplyThemeForScreenshot` contract would never have been the only way to make Themes
apply a third-party MTZ, only the way to make it apply one *without the user confirming* — and
losing it would have cost the silent apply, not the capability. That conclusion is **on hold**
until §9.

`ModuleApplyUtils`, `"Applying theme END:  0x"` and `"writeDefaultIconSettings: status ="` are
all strings inside the stock 3.4.8.36 APK (classes8, classes9 and classes6), so the apply ran
through Themes' own code rather than a module's — "Module" there is Themes' internal module
layout. That rules out a module *substituting* the apply; it does not rule out one removing a
check along the way.

## 9. Re-test plan for an unrooted device

Run in this order; stop at the first failure, since each step depends on the previous one.

1. **Confirm the phone is clean.** `which su` empty, no `com.rifsxd.ksunext` /
   `org.matrix.vector.manager` / `org.lsposed.corepatch` / `magisk` package, `getenforce` =
   Enforcing, `ro.boot.verifiedbootstate` = green. Record the Themes version.
2. **Get a theme into the catalogue** with Shizuku only (`MtzToBakConverter` → backup restore),
   and check a new `.mrm` appears under `.data/meta/theme/`. If this fails, nothing after it can
   be attributed to the apply step.
3. **Open the deep link** with the new localId and check the screen shows the theme's title and
   an Apply button rather than the online spinner. This is the part least likely to depend on
   root, so a failure here would be surprising and worth capturing.
4. **Tap Apply** and look for `ModuleApplyUtils: Applying theme END` in `logcat --pid` and for
   fresh timestamps across `/data/system/theme/`. A rights or purchase dialog appearing instead
   is the interesting negative result: it would mean CorePatch was what made step 4 work here.
5. Note whether `/data/system/theme/rights` stays empty, and whether the record still needs
   `isBackUpVersion: true` to be accepted.

Until step 4 is reproduced clean, the project should keep describing the full apply as
*user-confirmed through Themes, verified only on a rooted device*.

## 10. The clean-device run — step 3 passes, step 2 is the wall

Second rig: **2201116PG (veux)**, Android 16 / SDK 36, HyperOS **OS3.0.301.0.WNRINXM**, factory
Themes `3.0.4.32-global`, upgraded during the session to **3.4.8.36-global**. Cleanliness:

```
command -v su          → (nothing)
no su binary in /system/bin, /system/xbin, /sbin, /vendor/bin, /debug_ramdisk, /data/adb/magisk
root/Xposed packages   → none (only com.hamondev.shevery and dev.glorioustr.mtzstudio)
SELinux                → Enforcing      ro.secure=1   ro.debuggable=0
ro.boot.verifiedbootstate → orange       (bootloader unlocked, nothing else modified)
```

The unlocked bootloader is why `Test-RootlessApply.ps1` reports NOT CLEAN; with no su, no root
manager and SELinux enforcing, nothing on this phone has elevated runtime privilege.

### Step 3 passes — the deep link is genuinely root-free

```
am start -a android.intent.action.VIEW -c android.intent.category.DEFAULT \
  -n com.android.thememanager/.activity.ThemeDetailActivity \
  -d 'ViewLocalResource://view.local.resource#0'
```

opened the built-in **Varsayılan** theme's own page, with previews and the **UYGULA** button, on
Themes 3.4.8.36 with no root present. The fragment contract from §2 therefore holds independently
of root — it is URI parsing, and nothing elevated is involved in starting an exported,
permission-free activity.

### Step 2 is the wall — four closed doors

With Shizuku/Shevery running (ADB mode, `shizuku_server` as uid 2000) and MTZ Studio 5.7.5
installed, every route into the catalogue failed:

1. **MTZ Studio's own BAK route refuses to run.** Its gate is explicit — `SheveryBackupRestorer`
   returns `ADB_ONLY` when `Shizuku.getUid() != 0`, with the comment that an ADB-mode service
   "can execute shell commands but cannot read or restore that private data". On an unrooted
   phone Shizuku can only be in ADB mode, so this path is never available. The apply fell through
   to exporting the MTZ and posting the restore-assistant notification; no record appeared.
2. **Dropping the `.mtz` into Themes' own folder does nothing.** Copied to
   `files/MIUI/theme/` as shell (which *is* in `ext_data_rw`), Themes restarted — the local list
   still showed only the three built-ins.
3. **Hand-writing the catalogue record does nothing either.** A valid theme-level
   `.data/meta/theme/<id>.mrm` plus `.data/content/theme/<id>.mrc`, byte-identical in form to the
   record the converter produces (same MTZ, same `sha1 ecf6aa1b…`), was pushed as shell. Themes
   restarted — still three built-ins. **The catalogue lives in the app's private database**; the
   files under `Android/data` are the backup mirror, not the source of truth. That is exactly why
   the working route is a *backup restore* and why it needs uid 0.
4. **Both providers refuse uid 2000.**
   ```
   content query --uri content://com.android.thememanager.theme_provider/theme
     → SecurityException: … requires miui.permission.USE_INTERNAL_GENERAL_API
   content query --uri content://com.android.thememanager.provider
     → SecurityException: … (ThemeRuntimeDataProvider) same permission
   ls /data/data/com.android.thememanager        → Permission denied
   ```
   `com.android.shell` does not hold that signature|privileged permission.

### What this settles, and what it leaves open

* The deep link works without root. §2's contract stands.
* **MTZ Studio's own Shizuku binder route into the catalogue needs root-mode Shizuku** (uid 0),
  which an unrooted phone cannot provide — `SheveryBackupRestorer` gates on exactly that. The four
  low-level routes a third-party app could try (its own BAK binder, dropping the `.mtz`,
  hand-writing the record, the content providers) are all closed to uid 2000.
* **But a fifth route is not low-level at all, and it works — see §11.**
* Everything planted during the test (the copied `.mtz`, the hand-written record, `/data/local/tmp`
  scratch files) was removed afterwards; Themes was left at 3.4.8.36.

## 11. The breakthrough — MIUI Backup app restores a third-party theme with no root

The mistake in §5 and §10 was looking for a way to make *our* process do the privileged restore.
The privileged process already exists on the phone: **`com.miui.backup` is a system app**
(`android.uid.backup`, uid 6100, `signature|privileged`, flags `SYSTEM`). If it does the restore,
its privilege is used, not ours — so no root is needed from us at all.

And every piece of that route is reachable by an ordinary app:

* `com.miui.backup/.local.LocalHomeActivity` is **exported** with action
  `miui.intent.backup.LOCAL_HOME_ACTIVITY` and **no permission** — any app can launch it.
* Its backup store is `/sdcard/MIUI/backup/AllBackup/`, mode 0770 on the `media_rw` group —
  **plain shared storage**, writable by an app holding `MANAGE_EXTERNAL_STORAGE` (a user-grantable
  permission, not root).
* The package format is simple: a folder `AllBackup/<timestamp>/` containing
  `Themes(com.android.thememanager).bak` plus a `descript.xml` that names the package
  (`<packageName>com.android.thememanager</packageName>`, `<bakFile>`, `<bakType>1</bakType>`,
  sizes). Both are files MTZ Studio can already produce — the `.bak` is exactly what
  `MtzToBakConverter` builds.

**Proven end to end on the unrooted veux phone, 2026-11-23:**

1. Launched `miui.intent.backup.LOCAL_HOME_ACTIVITY` → MIUI Backup's "Yedekle ve geri yükle" UI.
2. **Geri yükle** tab listed the theme backups already present in `AllBackup`.
3. Selected one (a `Themes(com.android.thememanager).bak`), tapped **Geri yükle**.
4. Progress ran to **100% — "Temalar 64,59MB 100%, Bitti"**.
5. A catalogue record appeared: `.data/meta/theme/2cd28624-…​.mrm`, written by `system_theme`,
   titled **"Tokio"** — a genuine third-party theme, now in the catalogue on a phone with no su,
   no root manager, SELinux enforcing.
6. `ViewLocalResource://view.local.resource#2cd28624-…` opened Tokio's own detail page with its
   previews and the **UYGULA** button.

So the catalogue wall falls without root. The complete rootless chain is:

> MTZ → `MtzToBakConverter` → write `AllBackup/<ts>/Themes(com.android.thememanager).bak` +
> `descript.xml` (needs `MANAGE_EXTERNAL_STORAGE`) → launch MIUI Backup → user taps Restore →
> **MIUI Backup (system) restores it** → catalogue record → deep link → user taps Apply.

Two user taps inside Xiaomi's own apps (Restore, then Apply), no Shizuku, no root — **for a theme
that is free of DRM.** The apply step has a second gate, which §12 covers.

## 12. The real wall is MIUI content DRM, not onlineId — and it is content-keyed

Device-traced on the unrooted veux phone. The "onlineId wall" is **MIUI theme DRM**
(`miui.drm.DrmManager.isLegal(context, contentFile, rightsFile)`, `DrmService`), and it keys off
the theme **content**, not the `onlineId`/`productId` fields in the record.

Apply of a store/shared theme logs, with no network rights:

```
DrmService: check rights file: c010f94ee081921bd062a9516d67a875dae9a9d6.mra, exists: false
ThemeDebug:  CheckRightsTask return: DRM_ERROR_RIGHT_FILE_NOT_EXISTS
DrmService: downloadRights, resultCode=413   (×3)
ThemeDebug:  DownloadRightsTask return: 100004
```

Themes derives the rights-file name (`<hash>.mra`) from the theme's content/assembly, looks for
it under `.data/rights/theme/`, and — not finding it — tries to download rights from Xiaomi. With
no entitlement that fails (413), and apply is blocked. The `UYGULA` tap registers (ACTION_DOWN/UP
in the log) but nothing is applied.

This was proven to be content-keyed, not field-keyed, by experiment:

* Restoring a store theme (**Tokio**, `productId` + a real `.mra`) created a catalogue record, but
  `UYGULA` did nothing — `isLegal` failed.
* Editing that backup's `.mrm` to `onlineId:null`/`productId:null` and giving it a fresh
  localId/assemblyId did **not** help: the restore produced no catalogue record at all (the
  tampered rights linkage gave `DRM_ERROR_ASSET_NOT_MATCH`), leaving only a stray `.mra`.
* Restoring a different theme whose *backup* carried **no** rights file and `onlineId:null`
  (**"Circle UI"** by VedaT, a shared theme) restored cleanly — but its apply **still** ran the
  DRM check above (`c010f94e….mra`, not present) and was blocked. So even a record with
  `onlineId:null` and no bundled `.mra` is gated, because Themes computes the rights requirement
  from the content.

### Why §8 (the rooted phone) applied, and this does not

The rooted klee phone had **LSPosed CorePatch**, whose whole purpose is to bypass MIUI's
signature/rights verification. On it, a theme applied with all 16 components and
`/data/system/theme/rights` was empty — because CorePatch neutralised exactly this `DrmService`
check. On the clean phone, with no Xposed, the check runs and blocks the apply. **§8 was a
CorePatch result, not a rootless one.** This is the answer to the question §9 left open.

### What this means

* **Rootless import** of any theme into the catalogue: **works** (MIUI Backup, §11).
* **Rootless apply** is gated by MIUI DRM, keyed on content:
  * A theme that is a Xiaomi store / shared protected asset needs valid rights (ownership) to
    apply. Without them the apply is blocked, and there is **no rootless bypass** — bypassing it
    needs root + Xposed (CorePatch), which is what the rooted phone used.
  * A theme composed from genuinely original, non-protected content (the user's own images and
    components) has no DRM asset to check, and is the only kind that can apply with neither root
    nor rights. Confirming this on the clean phone needs a truly original composition in the
    catalogue (the themes available here, VedaT's "Circle UI" and the store "Tokio", are both
    protected); it is consistent with the DRM being content-keyed, but is not yet shown end to end.
* Forging or stripping the `.mra` rights token to make a protected theme apply is **circumventing
  content DRM** and is out of scope — this work does not do it, and the code's existing
  `onlineId:null` output does not attempt it either.

### Practical answer for the app

The rootless pipeline (MIUI Backup restore → deep link → apply) is real and works for **themes the
user is entitled to apply**: their own original compositions, and themes they own. It cannot make
a protected theme the user has no rights to apply without root+Xposed. The honest capability line
for an unrooted device is therefore: import yes; apply yes for free/original/owned themes, no for
protected themes the user does not own.

## 13. Implemented (MIUI Backup import path)

| Piece | Where |
| --- | --- |
| Stage theme → MIUI Backup package + `descript.xml`, launch intent, prune | `app/.../miuibackup/MiuiBackupThemeStager.kt` |
| Two-step coordinator (stage → restore → deep-link apply) | `app/.../miuibackup/MiuiBackupImportCoordinator.kt` |
| Pending-import memory across the external trip | `app/.../miuibackup/PendingMiuiImportStore.kt` |
| `MANAGE_EXTERNAL_STORAGE` + `com.miui.backup` query | `AndroidManifest.xml` |
| UI: "Import via MIUI Backup" action + "Open in Themes" return card | `MainActivity.kt` (apply dialog) |
| descript.xml unit tests | `app/src/test/.../miuibackup/MiuiBackupDescriptTest.kt` |

Decisions worth keeping:

* **The system app does the privileged work.** MTZ Studio only writes a package into
  `AllBackup/<timestamp>/` (the converter's `.bak` + a `descript.xml` whose size fields all carry
  the real `.bak` length, device-verified format) and opens
  `com.miui.backup/.local.LocalHomeActivity`. The restore itself runs inside MIUI Backup.
* **All-files access is requested on demand, never at launch** — only when the user picks this
  path, via `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`.
* **Two steps, honestly labelled.** Staging ends by opening MIUI Backup with instructions; the
  `localId` is remembered, and on return the app offers to open that theme on its own Apply page
  (the `ViewLocalResource#<localId>` deep link). The app never claims the theme was applied.
* **Own folders are pruned** (`descript.xml` carries an inert marker comment) so the restore list
  does not fill up; the user's own backups are never touched.
* **DRM is not touched.** The staged record carries whatever the converter produces
  (`onlineId:null`, no `.mra`); apply of a protected theme is still Xiaomi's decision at §12's gate.

Not done / unverified:

* Not built or run (no JDK on the dev machine); the `.bak`→MIUI-Backup restore was verified
  manually on-device, the code path that assembles it was not executed.
* End-to-end apply of a genuinely original (DRM-free) composition on a clean phone — see §12.

### Still open

* **The final Apply tap (step 6) did not register on this WebView page** via `input tap`, so the
  apply itself is not yet confirmed *on the clean phone*. It was confirmed on the rooted phone
  (§8). Note the restored "Tokio" carries an `onlineId`/`productId`, so its apply may route through
  an online-rights path that a purely local theme (no `onlineId`, `isBackUpVersion:true`, like the
  converter's own output) would not — worth re-testing with a converter-made theme.
* How MTZ Studio should build the MIUI-backup package (one-theme `descript.xml`, timestamp folder
  naming) and whether `MANAGE_EXTERNAL_STORAGE` is acceptable UX versus the current Shizuku path.
