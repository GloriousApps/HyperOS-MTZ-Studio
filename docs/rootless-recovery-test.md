# Rootsuz Temalar yeniden kurulum testi

Bu deneysel APK: **5.6.3-rootless-test.1**, sürüm kodu **80**.

## Kapsam ve risk

- Yalnızca Global Xiaomi Temalar **3.4.1.23** kurulu bir sistem uygulaması, Android 10+, kullanıcı 0 ve ADB modunda yetkilendirilmiş Shizuku/Shevery için tasarlanmıştır.
- Hedef: depodaki orijinal Xiaomi Temalar **3.0.5.6-global**, sürüm kodu 3000506. Tek, bağımsız APK kullanılır; APKMirror veya eksik split seti kullanılmaz.
- Diğer kullanıcı/profillerde Temalar kuruluysa deney durdurulur. APK kodu cihaz genelinde paylaşıldığından yalnızca `--user 0` yazılması diğer profiller için yeterli güvence değildir.
- Kullanıcıdan kaldırma, cihaz genelindeki sürüm kontrolünü veya fabrika APK alt sınırını aşmayı **garanti etmez**. Kaldırma başarılı olsa bile eski sürümün kurulması reddedilebilir.
- **Mevcut Temalar kaldırılırken temalar, uygulama verileri ve ayarlar silinebilir. Studio ZIP'i tam Xiaomi Temalar yedeği değildir.**
- Hiçbir sistem güncellemesi topluca kaldırılmaz; `uninstall-system-updates`, root veya diğer kullanıcıları kaldırma işlemi uygulanmaz.

## Test öncesi

1. Önemli temaları olan ana telefon yerine test cihazını kullanın.
2. Cihaz destekliyorsa HyperOS'un kendi yerel yedekleme aracından Temalar yedeği alın. Yeni sürüm verisinin eski sürüme geri yüklenmesi ayrıca doğrulanmalıdır.
3. Resmi imzalı test APK'sını yükleyin. Bu, Studio'nun mevcut paketinin üzerine kurulur; ayrı paket değildir. Studio sürüm kodu 80 olduğundan kodu 79 olan kararlı 5.6.2'ye normal güncelleme ile dönülemez. Studio'yu kaldırmak uygulamaya özel yedekleri de siler; önce dışa aktarın.
4. Shizuku/Shevery'yi kablosuz hata ayıklama/ADB modunda başlatıp MTZ Studio'ya izin verin.

## Uygulama içi test

1. **Panel → Temalar uyumluluğu → Deneysel rootsuz Temalar kurtarma** kartını bulun.
2. **Hazırla ve yedeği doğrula** düğmesine basın. Bu adım paket kaldırmaz.
3. Hazırlık; hedef APK SHA-256/paket/sürüm/SDK/imza doğrulaması yapar, mevcut base ve bütün split APK dosyalarını kopyalar, Studio kitaplık ZIP'ini oluşturur, ZIP CRC ve dosya hash'lerini denetler.
4. **Studio kitaplığı ZIP dosyasını dışa aktar** ve **Kurtarma ZIP dosyasını dışa aktar** seçenekleriyle yedekleri uygulama dışına kaydedin. Kurtarma ZIP'i eski APK setini içerir; özel Temalar verilerini içermez. Özel yedekleri GitHub'a yüklemeyin.
5. Önce onay penceresinde **İptal** seçeneğini deneyin: kurulu Temalar değişmemelidir.
6. Veri kaybı uyarısını okuyup kutuyu işaretleyin. **Kaldır ve eski sürümü kur** düğmesinden sonra ikinci onayı verin.
7. Uygulamayı açık tutun. Günlük, kaldırma ve kurulumdan **önce** kalıcı olarak kaydedilir. Bağlantı koparsa kaldırma otomatik tekrarlanmaz.

## Başarı ölçütü

- Temalar paket adı ve Xiaomi imzası korunur; sürüm **3.0.5.6-global / 3000506** görünür.
- Eski `ApplyThemeForScreenshot` etkinliği çözümlenir. Bu, gerçek tema içe aktarma/uygulamanın ayrıca denenmesi gerektiğini ortadan kaldırmaz.
- Kullanıcının sahip olduğu küçük bir MTZ ile içe aktarma/uygulama deneyin; Temalar'ın açıldığını ve tema sonucunu kontrol edin.

## Başarısızlık / kesinti

- Karttaki aşama ve hata kodunu kaydedin. Özellikle `INSTALL_FAILED_VERSION_DOWNGRADE` bu yöntemin ilgili ROM'da engeli aşmadığını gösterir; tekrar tekrar kaldırma denemeyin.
- **Mevcut paketi yeniden etkinleştir** düğmesi, ayrı onayla yalnızca `pm install-existing --user 0 com.android.thememanager` yolunu dener ve paket kimliğini kontrol eder.
- Bu işlem **fabrika sürümünü garanti etmez ve silinen verileri geri getirmez**. Temalar zaten görünüyorsa yeniden kaldırılmaz.
- Paket kurtarma doğrulanamazsa işlemi durdurun. Uygulama dışında sakladığınız APK seti ve cihazın yedekleme yöntemiyle ayrıca müdahale gerekir; eksik split APK ile kurulum denemeyin.
- Live Diagnostics içinde `rootless_recovery_state` kayıtlarını paylaşabilirsiniz. Hata kodu ve sürümler kaydedilir; özel yedek dosyaları veya tam cihaz dökümleri kaydedilmez.

## Geliştirici kontrolleri

- 12 durum makinesi testi: onaysız işlem, bozuk hedef/yedek, hizmet kaybı, değişen paket, staging/günlük yazma hatası, belirsiz kaldırma, uygulama yeniden açılışı, imza uyuşmazlığı ve tehlikeli komutların reddi.
- Android debug APK derlemesi ve app/tester-adapter birim testleri çalıştırılır.
- Gerçek HyperOS kaldırma/kurulum ve veri kaybı/kurtarma davranışı masaüstü birim testleriyle doğrulanmış sayılmaz.
