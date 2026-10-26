# MTZ Studio — Tema Runtime Mimarisi

## Kapsam

MTZ Studio, kurulu Xiaomi Themes paketinin gerçek yeteneklerini çalışma anında denetler. 3.0.5.6'ya zorunlu dönüş, APK değiştirme ve bu dönüş için Theme Manager yedeği uygulamanın parçası değildir.

## Uygulama yolu seçimi

1. Kurulu `com.android.thememanager` paketinde `ApplyThemeForScreenshot` çözümlenebiliyorsa, doğrulanmış tester isteği kullanılır.
2. Bu etkinlik yoksa ve gerçek root erişimi ile `Xiaomi Themes Runtime + MTZ Support` modülü hazırsa, root modülünün yerleşik içe aktarma/uygulama köprüsü kullanılır.
3. Modern `10.8.7.6+` sürümlerinde native yerel tema kitaplığı ve yetkili Shizuku/Shevery akışı kullanılabilir.
4. Kullanılabilir otomatik yol yoksa mevcut MTZ ortak depolamaya hazırlanır ve Xiaomi Themes'in ilan ettiği manuel yüzey açılır. Kurulu paket zorla değiştirilmez.

Sürüm numarası tek başına etkinlik veya uygulama yeteneği kanıtı değildir. `ApplyThemeForScreenshot` kontrolü Android `PackageManager` üzerinden yapılır; bu nedenle 3.0.5.14 ve sonrasındaki değişiklikler sabit bir allowlist'e bağlanmaz.

## Root runtime modülü

Korunan modül:

- Kimlik: `xiaomi_themes_global_import`
- Ad: `Xiaomi Themes Runtime + MTZ Import`
- Global runtime: `3.4.1.23-global`
- Non-Global runtime: `11.5.3.1`
- Global yol: doğrulanmış Zygisk/MTZ import köprüsü
- Non-Global yol: doğrulanmış native yerel kitaplık runtime'ı

Modül kurulumu APK ve ZIP bütünlüğünü, paket imzasını, hedef aileyi ve canlı Package Manager sonucunu doğrular. Geçerli Mods-Center `miui_theme_mod` V7 kurulumu varsa mevcut runtime benimsenir ve üzerine yazılmaz.

## Kaldırılan yol

Aşağıdaki akışlar kaldırılmıştır:

- Ghostlock veya benzeri 3.0.5.6'ya düşüren root modülü
- Shevery/ADB üzerinden zorunlu 3.0.5.6 kurulum akışı
- Bu downgrade için Theme Manager APK, uygulama verisi ve tema içeriği kurtarma/yedekleme kartı
- 3.0.5.6 APK'sı ve ona bağlı updater/recovery sınıfları

Studio'nun genel MTZ kitaplığı yedekleme/geri yükleme özellikleri ile modern Xiaomi Themes native import için kullanılan HyperOS backup servisi bu kaldırmadan bağımsızdır.

## Doğrulama

```text
./gradlew.bat test assembleDebug
```

Bir cihazda uygulanmış tema sonucu Xiaomi Themes tarafından doğrulanmadığında Studio bunu başarılı kabul etmez; manuel Xiaomi onayı ve ROM davranışı cihazda kalır.
