# MTZ Studio — Tema Runtime Mimarisi Analiz Raporu ve Değişiklik Planı

> Kapsam: Xiaomi Theme Manager bağımlılığının kontrollü hale getirilmesi, Root ve Rootless yollarının mimari olarak ayrılması.
> Bu rapor, kod değişikliği **yapılmadan önce** onaylanmak üzere hazırlanmıştır. Onay sonrası 10 fazlı küçük commit stratejisiyle uygulanacaktır.

---

## 1. Özet

MTZ Studio, temaları Xiaomi Theme Manager (paket: `com.android.thememanager`) üzerinden uygular. Theme Manager'ın sürüm ailesine göre davranışı kökten değişir; bu yüzden uygulama, kurulu sürümü algılayıp farklı "runtime" yollarına dallanır. Bu rapor:

1. Mevcut sürüm algılama ve davranış eşlemesini analiz eder,
2. Kullanıcının doğruladığı **4 hedef runtime yolunu** tanımlar,
3. Mevcut kodun bu yollarla uyumunu ve boşluklarını listeler,
4. Onay sonrası uygulanacak **dosya değişiklik planını** tablo halinde verir.

---

## 2. Mevcut Durum Analizi

### 2.1 Sürüm → Davranış eşlemesi (kodda mevcut)

`tester-adapter/.../ThemeManagerCompatibility.kt` içindeki `ThemeManagerContract`:

| Sabit | Değer | Açıklama |
|---|---|---|
| `RECOMMENDED_VERSION` | `3.0.5.6` | Shizuku/legacy tester için önerilen Global sürüm |
| `SUPPORTED_GLOBAL_VERSIONS` | `{2.15.5.46, 3.0.4.32, 3.0.5.6}` | `LOCAL_THEME_IMPORT` davranışına eşlenir |
| `MODERN_NATIVE_LIBRARY_MIN_VERSION` | `10.8.7.6` | Bu ve sonrası `MODERN_NATIVE_LIBRARY` |
| `MODERN_LOCAL_LIBRARY_COMPONENT` | `...MineResourceTabActivity` | 10.8+ yerel kütüphane ekranı |

`ThemeManagerBehavior` enum'u: `LOCAL_THEME_IMPORT`, `MODERN_NATIVE_LIBRARY`, `TEMPORARY_DEFAULT_COMPOSITE` (3.0.5.14), `TESTER_ACTIVITY_REMOVED` (3.0.6.8), `UNKNOWN`.

`InstalledThemeManager`:
- `isRecommended` → kurulu sürüm `SUPPORTED_GLOBAL_VERSIONS` içinde **veya** `MODERN_NATIVE_LIBRARY`.
- `requiresGlobalThemeProtection` → davranış `MODERN_NATIVE_LIBRARY` değilse `true`.
- `usesModernNativeLibrary` → davranış `MODERN_NATIVE_LIBRARY` ise `true`.

### 2.2 Mevcut uygulama yolları (ThemeApplyCoordinator)

`ThemeApplyProtocol` enum'u 9 iletim protokolü tanımlar:

| Protokol | Kullanım koşulu | Açıklama |
|---|---|---|
| `LEGACY_TESTER` | Global 2.15/3.0.4/3.0.5.6 | `ApplyThemeForScreenshot` tester aktivitesi |
| `ROOT_GLOBAL_THEME_MANAGER_BRIDGE` | Root modülü aktif + Global | Root MTZ Import modülü üzerinden native import/apply |
| `MODERN_THEME_MANAGER_BRIDGE` | 10.8+ + Xposed köprü kapsamı | Köprü marker'ı ile native import/apply |
| `MODERN_THEME_MANAGER_DIRECT_APPLY` | 10.8+ köprüsüz | `ViewLocalResource://...#localId` + `REQUEST_APPLY_EVENT` |
| `MODERN_THEME_MANAGER_MANUAL_IMPORT` | 10.8+ köprüsüz | `MineResourceTabActivity` + `REQUEST_RESOURCE_CODE=theme` |
| `ROOTLESS_MANUAL_IMPORT` | Rootless | İndirilenler/MTZ Studio + elle içe aktarma |
| `ROOTLESS_LEGACY_TESTER` | Rootless + Global | BAK restore sonrası Zyper local-apply |
| `ROOTLESS_BACKUP_RESTORE` | Rootless + Shizuku | HyperOS backup servisi ile BAK restore |
| `ROOTLESS_FILE_MANAGER_HANDOFF` | Rootless + sürüm MTZ ilan etmiyor | Dosya Yöneticisi aktarımı |

### 2.3 Root modülü (mevcut)

- Asset: `app/src/main/assets/` altında `v1_0_0.zip` (id: `xiaomi_themes_global_import`, versionCode 100).
- `customize.sh`: arm64-v8a + API 27+ + `com.android.thememanager` kurulu olmalı; "Supports Global 3.0.5.6, 3.0.6.8 and 3.4.x" yazar (3.4.x zaten kapsanıyor).
- `action.sh`: `am force-stop` + `ThemeResourceTabActivity` başlatır.
- Dex: Zygisk hook (`HookEntry`), `MtzStudioRootBridge`, `importTheme/applyImportedTheme/applyExistingTheme/applyThemeResource/loadExistingThemeResource/removeReplacedThemes/validateStudioTheme`, `mtz_import_module_ready` marker'ı, `theme/.data/meta/theme` + `theme/.download` yolları, `dev.glorioustr.mtzstudio.action.{APPLY_EXISTING_THEME, APPLY_MODERN_THEME, IMPORT_MODERN_THEME}`.

---

## 3. Hedef Runtime Matrisi (kullanıcı onaylı)

Kullanıcının verdiği bilgilerle 4 korunacak yol:

| # | Yol | Theme Manager sürümü | Aile | MTZ Studio tarafı | Uygulama mekanizması |
|---|---|---|---|---|---|
| 1 | **Shizuku / legacy tester** | `3.0.5.6-global` | Global | `LEGACY_TESTER` | `ApplyThemeForScreenshot` tester aktivitesi |
| 2 | **Root modülü (Global)** | `3.4.1.23-global` | Global | `ROOT_GLOBAL_THEME_MANAGER_BRIDGE` | Root MTZ Import modülü (Zygisk hook) → native import/apply |
| 3 | **Çin sürümü (Mods-Center)** | `11.5.3.1` | China | `MODERN_NATIVE_LIBRARY` | Yerleşik MTZ import + native apply |
| 4 | **Modern native library** | `10.8.7.6` ve sonrası | China | `MODERN_THEME_MANAGER_BRIDGE` / `DIRECT_APPLY` | MTZ Studio'dan import → direkt Temalar uygulamasına gider → oradan uygulanır |

**Kritik kullanıcı notu:** "10.8.7.6 ve sonrasını kullanan uygulamalar için MTZ Studio'dan Import edilen temalar direkt Temalar uygulamasına gidip MTZ Studio'dan uygula dediğimizde oradan uyguluyor. Bu yolları tutmalıyız." → Yol 4'te import, Theme Manager'ın kendi içine yapılır; uygulama da Theme Manager üzerinden olur. Bu akış korunmalıdır.

---

## 4. Mevcut Kodun Hedef Matrisle Uyumu (Boşluk Analizi)

| Hedef Yol | Kodda karşılığı | Durum | Boşluk |
|---|---|---|---|
| 1. Shizuku 3.0.5.6 | `RECOMMENDED_VERSION`, `legacyTesterRequest`, `prepareLegacyTester` | ✅ Uyumlu | — |
| 2. Root Global 3.4.1.23 | `ROOT_GLOBAL_THEME_MANAGER_BRIDGE`, `prepareRootGlobalModuleImport` | ⚠️ Kısmen | `SUPPORTED_GLOBAL_VERSIONS` Shizuku içindir ve 3.4.1.23'ü içermez (doğru — tester activity yok). Ancak `isRecommended` 3.4.1.23'ü tanımıyor → Root modülü kurulu olsa bile kartta "önerilen değil" görünebilir. `ROOT_GLOBAL_RECOMMENDED_VERSION` sabiti yok. |
| 3. Çin 11.5.3.1 | `MODERN_NATIVE_LIBRARY` (>= 10.8.7.6) | ✅ Uyumlu | `behavior("11.5.3.1")` = `MODERN_NATIVE_LIBRARY`. Test listesinde 11.1.5.0 var, 11.5.3.1 yok ama eşleme aralık tabanlı olduğu için çalışır. |
| 4. Modern 10.8.7.6+ | `MODERN_THEME_MANAGER_BRIDGE` / `DIRECT_APPLY` / `MANUAL_IMPORT` | ✅ Uyumlu | — |

### 4.1 Ana boşluklar

1. **`ROOT_GLOBAL_RECOMMENDED_VERSION = "3.4.1.23"` sabiti yok** → Root modülünün çalıştığı Global sürüm resmi olarak tanınmıyor; `isRecommended` 3.4.1.23'ü "önerilen" saymıyor.
2. **`ThemeManagerCompatibilityCard`** → Root modülü kartında Root hedef sürümü (3.4.1.23) açıkça belirtilmeli.
3. **`RootThemeManagerUpdater`** → `RECOMMENDED_VERSION`'a (3.0.5.6) sıkı sıkıya bağlı; Root için ayrı hedef sürüm kullanılmalı.
4. **`MainActivity`** → Root modülü kurulu + 3.4.1.23 durumunda `requiresGlobalThemeProtection` mantığı gevşetilmeli (Root modülü Zygisk hook ile Theme Manager'ın içine girer, ayrı Xposed koruması gerektirmez).

---

## 5. Dosya Değişiklik Planı Tablosu

| # | Dosya | Değişiklik | Faz |
|---|---|---|---|
| 1 | `tester-adapter/.../ThemeManagerCompatibility.kt` | `ROOT_GLOBAL_RECOMMENDED_VERSION = "3.4.1.23"` sabiti tanımla; `isRecommended`'a Root sürümü koşulu ekle (Shizuku `SUPPORTED_GLOBAL_VERSIONS`'ı değiştirme) | Faz 1 |
| 2 | `app/.../ThemeManagerCompatibilityCard.kt` | Root modülü kartında Root hedef sürümünü (3.4.1.23) ve Shizuku önerisini (3.0.5.6) ayrı göster | Faz 2 |
| 3 | `app/.../RootThemeManagerUpdater.kt` | Root için ayrı hedef sürüm sabiti kullan (`ROOT_GLOBAL_RECOMMENDED_VERSION`) | Faz 3 |
| 4 | `app/.../MainActivity.kt` | Root modülü kurulu + 3.4.1.23 durumunda `requiresGlobalThemeProtection` mantığını gevşet | Faz 4 |
| 5 | `docs/theme-manager-compatibility.md` | 3.4.1.23 (Root) ve 11.5.3.1 (Çin) bilgilerini ekle | Faz 5 |
| 6 | `docs/architecture.md` | Root/Rootless ayrımını ve 4 runtime yolunu belgele | Faz 6 |

---

## 6. Doğrulama Planı

Her fazdan sonra:

```
./gradlew.bat test assembleDebug
```

- Faz 1: `ThemeManagerCompatibilityTest` güncelle (3.4.1.23 → Root davranışı, 11.5.3.1 → `MODERN_NATIVE_LIBRARY`).
- Faz 2-3: Kart ve updater testleri.
- Faz 4: MainActivity akış testleri (Root modülü kurulu + 3.4.1.23 → `ROOT_GLOBAL_THEME_MANAGER_BRIDGE`).
- Faz 5-6: Dokümantasyon gözden geçirme.

---

## 7. Onay Noktası

Bu rapor onaylandığında Faz 1'den başlayarak 10 fazlı küçük commit stratejisiyle kod değişikliklerine geçilecektir. Her faz sonunda `./gradlew.bat test assembleDebug` çalıştırılacaktır.
