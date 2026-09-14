# HyperOS MTZ Studio

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/logo_banner.png" alt="HyperOS MTZ Studio" width="640">
</p>

<p align="center">
  Xiaomi HyperOS ve MIUI için açık kaynaklı MTZ çalışma alanı.<br>
  Temaları içe aktarın, düzenleyin, çevirin, dönüştürün, birleştirin ve uyumlu cihazlarda uygulayın.
</p>

<p align="center">
  <strong>🇹🇷 Türkçe</strong> · <a href="readme_en.md">🇬🇧 English</a>
</p>

<p align="center">
  <a href="https://github.com/GloriousApps/HyperOS-MTZ-Studio/releases/latest"><img alt="Son sürüm" src="https://img.shields.io/github/v/release/GloriousApps/HyperOS-MTZ-Studio?display_name=tag&style=for-the-badge&color=7357e6"></a>
  <a href="https://github.com/GloriousApps/HyperOS-MTZ-Studio/actions/workflows/release.yml"><img alt="Release derlemesi" src="https://img.shields.io/github/actions/workflow/status/GloriousApps/HyperOS-MTZ-Studio/release.yml?style=for-the-badge&label=Release"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white">
</p>

<p align="center">
  <a href="https://github.com/GloriousApps/HyperOS-MTZ-Studio/releases/tag/v4.6.0"><strong>v4.6.0 APK indir</strong></a>
  · <a href="docs/release-notes-v4.6.0.md">Sürüm notları</a>
  · <a href="docs/theme-manager-compatibility.md">Uyumluluk</a>
  · <a href="https://github.com/GloriousApps/HyperOS-MTZ-Studio/issues">Sorun bildir</a>
</p>

## v4 arayüzü

<p align="center">
  <img src="docs/screenshots/v4/shizuku-panel.png" alt="Shizuku ve Tema Yöneticisi uyumluluk paneli" width="24%">
  <img src="docs/screenshots/v4/root-panel.png" alt="Root ve FolkPatch yetkilendirme paneli" width="24%">
  <img src="docs/screenshots/v4/shizuku-library.png" alt="Aktif tema ve kayıtlı temalar kitaplığı" width="24%">
  <img src="docs/screenshots/v4/shizuku-tools.png" alt="MTZ Studio araçları" width="24%">
</p>

## Neler yapar?

- **MTZ Import:** MTZ paketlerini doğrular, özel kitaplığa ekler ve önizleme verilerini üretir. Tek dosya veya en fazla beş dosyalı toplu içe aktarma kullanılabilir. Desteklenen modern Xiaomi Temalar sürümlerinde Shizuku/Shevery ile aynı tema Xiaomi kitaplığına da aktarılır.
- **BAK Converter:** Desteklenen Xiaomi Temalar `.bak` yedeklerini güvenli biçimde MTZ’ye dönüştürür; sonuç doğrudan kitaplığa gelir.
- **Tema Dil Aracı:** Görünür tema metinlerini uygulama diline göre çevirir. XML, JSON ve güvenli MAML içerikleri için çeviri belleği ve isteğe bağlı API sağlayıcıları kullanır. Çeviri tamamlandığında eski Xiaomi kitaplığı kopyası geçersiz kılınır ve güncel MTZ desteklenen Shizuku/Shevery akışında yeniden eşitlenir.
- **Temanı Oluştur:** Bir temel temaya başka paketlerden kilit ekranı, simgeler, durum çubuğu, `clock_2x4` widget’ı, yazı tipi, duvar kâğıdı ve diğer seçili bileşenleri ekleyerek yeni MTZ oluşturur. Oluşturulan tema, desteklenen modern Xiaomi Temalar sürümlerinde yerel kitaplığa da hazırlanır.
- **Kitaplık:** Uygulanan temayı, içe aktarılan ve oluşturulan temaları font paketlerinden ayrı gösterir. Kitaplığı Yönet ekranı çoklu seçimle silme ve desteklenen cihazlarda Xiaomi Temalar’dan yerel tema alma imkânı sunar.
- **Yedekleme ve güncelleme:** Studio kitaplığı için bulut/WebDAV yedekleme ve geri yükleme sunar. Her ön plana gelişte GitHub güncellemesini arka planda denetler; yeni sürümü bildirir ve doğrulanmış APK indirmesini yüzde ilerlemesiyle gösterir.
- **Live Diagnostics:** İçe aktarma, dönüştürme, çeviri ve uygulama adımlarını kaydeder; Temalar paketiyle ilgili sorunların incelenmesini kolaylaştırır.

## Erişim modları

| Mod | Kullanım |
| --- | --- |
| **Root** | Algılanan root yöneticisi üzerinden gelişmiş Tema Yöneticisi işlemleri, uyumlu uygulama akışı ve tanılama. |
| **Shizuku / Shevery** | Root olmadan BAK Converter, yerel MTZ işlemleri, tema koruması ve desteklenen uygulama akışı. |
| **Yetkilendirme yok** | MTZ kitaplığı, önizleme, çeviri, besteci, dışa aktarma ve yedekleme kullanılabilir. Tema uygulama için Shizuku veya Shevery gerekir. |

Rootsuz cihazlarda Shizuku ya da [Shevery](https://github.com/HmnDev-Tech/shevery/releases) kurup Kablosuz hata ayıklama ile başlatın ve MTZ Studio iznini verin. Uygulama, kurulu yöneticiyi otomatik algılar ve gereken hizmeti açar.

## Tema Yöneticisi uyumluluğu

Studio yalnızca sabit bir Temalar sürüm listesine güvenmez. Eski Global sürümlerde `ApplyThemeForScreenshot` etkinliğini; 10.8.7.6 ve sonrası modern sürümlerde ise yerleşik yerel tema kitaplığı ekranını çalışma anında doğrular. Bu yollardan biri hazırsa profil **Uyumlu** gösterilir. Hiçbiri yoksa açık bir uyumsuzluk notu ve kurtarma seçeneği sunulur.

Bu seçenek, doğrulanmış Xiaomi Themes `3.0.5.6-global` paketini uygulama içinden Android İndirme Yöneticisiyle doğrudan indirir:

- [Xiaomi Themes 3.0.5.6-global APK](https://github.com/GloriousApps/HyperOS-MTZ-Studio/releases/download/v4.0.0/Xiaomi_Themes_3.0.5.6-global.apk)
- [SHA-256 doğrulama dosyası](https://github.com/GloriousApps/HyperOS-MTZ-Studio/releases/download/v4.0.0/Xiaomi_Themes_3.0.5.6-global.apk.sha256)

Xiaomi son uygulama ekranını ve tema kabul davranışını ROM’a göre değiştirebilir. Studio, uygulama sonuçlarını ve uyumluluğu mümkün olduğunda çalışma anında doğrular; manuel Xiaomi onayı yine görünebilir.

## Kurulum

1. [v4.6.0 Release](https://github.com/GloriousApps/HyperOS-MTZ-Studio/releases/tag/v4.6.0) sayfasından `MTZ_Studio_v4.6.0.apk` dosyasını indirin.
2. Eski bir sürümden geçiyorsanız önemli Studio kitaplığınızı yedekleyin.
3. APK’yı kurun, uygulamayı açın ve erişim modunun algılanmasını bekleyin.
4. Rootsuz tema uygulama için Shizuku/Shevery iznini verin.

v2.1.0 ve sonrasındaki resmi sürümler aynı imza anahtarını kullanır; normalde mevcut kurulumun üzerine güncellenir. Android, APK kurulumunun son onayını her zaman kullanıcıdan ister.

## Geliştirme

Gereksinimler: JDK 17, Android SDK API 36 ve Android 8.0 / API 26 veya üzeri.

```powershell
.\gradlew.bat test assembleDebug
```

Release imzalama için `MTZ_RELEASE_STORE_FILE`, `MTZ_RELEASE_STORE_PASSWORD`, `MTZ_RELEASE_KEY_ALIAS` ve `MTZ_RELEASE_KEY_PASSWORD` değişkenleri kullanılır.

## Belgeler

- [Mimari](docs/architecture.md)
- [Tema Yöneticisi uyumluluğu](docs/theme-manager-compatibility.md)
- [Yerelleştirme](docs/localization.md)
- [Tema Dil Aracı](docs/theme-language-translation.md)
- [Gizlilik ve güvenlik modeli](docs/threat-model.md)

## Sorumlu kullanım

Tema, font, simge ve görselleri yalnızca sahibi olduğunuz veya kullanım izniniz bulunan paketlerde kullanın. Xiaomi, HyperOS ve MIUI ilgili sahiplerinin ticari markalarıdır. Bu bağımsız proje Xiaomi ile bağlantılı değildir ve Xiaomi tarafından desteklenmez.

<p align="center">
  HyperOS kullanıcıları ve tema üreticileri için <a href="https://github.com/GloriousApps">GloriousApps</a> tarafından geliştirildi.
</p>
