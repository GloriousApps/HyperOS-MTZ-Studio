# Rootsuz Temalar yeniden kurulum testi

Bu deneysel APK: **5.6.3-rootless-test.4**, sürüm kodu **83**; kaynak dal **main**.

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
3. Resmi imzalı test APK'sını mevcut Studio'nun **üzerine güncelleyin; Studio'yu kaldırmayın**. Paket ve resmi imza değişmez; kod 81, test1 (80) ve kararlı 5.6.2 (79) üzerine güncellenebilir. Kod 79'a normal güncellemeyle dönülemez; Studio verilerini korumak için gerekirse daha yüksek kodlu, aynı imzalı kararlı build gerekir.
4. Shizuku/Shevery'yi kablosuz hata ayıklama/ADB modunda başlatıp MTZ Studio'ya izin verin.

## Uygulama içi test

1. **Panel → Temalar uyumluluğu → Deneysel Temalar kurtarma** kartını bulun. Ayrıntılar bilgi simgesindedir.
2. Tek ana düğme **Hazırla ve Yedekle**'ye basın. Bu adım paket kaldırmaz.
3. Hazırlık hedef APK'yı doğrular, mevcut base ve bütün split APK'ları ve Studio kitaplığını yedekler. ADB izin veriyorsa erişilebilir harici temaların tamamını **tek `themes.bak`** dosyasına alır; tema başına ayrı BAK oluşturmaz.
4. Dosyalar otomatik **Downloads/MTZ Studio/Backups/<zaman-kimlik>/** altına kaydedilir. Her public dosya URI üzerinden tekrar açılarak ad, konum, boyut ve SHA-256 denetlenir; sonuç günlüğe kaydedilmeden kurulum açılmaz. Manuel dışa aktarma düğmesi yoktur. Dosya yöneticisinde klasörü kontrol edin; özel yedekleri GitHub'a yüklemeyin.
5. Düğme **Eski sürümü kur** olur. Önce onay penceresindeki **İptal**'i deneyin: Temalar değişmemelidir; pencere tekrar açıldığında risk kutusu boş olmalıdır.
6. Devam etmeyi seçerseniz açık veri kaybı uyarısını okuyup kutuyu işaretleyin ve kurulumu onaylayın. Kurulumdan hemen önce public dosyalar yeniden doğrulanır; silinmiş/değişmiş yedek kaldırmayı engeller.
7. Uygulamayı açık tutun. Günlük, kaldırma ve kurulumdan **önce** kalıcı olarak kaydedilir. Bağlantı koparsa kaldırma otomatik tekrarlanmaz.

## Yedek klasörü ve sınırlar

- `studio-library.zip`: Studio kitaplığı; Xiaomi Temalar uygulama verisi değildir.
- `themes-apks.zip`: Mevcut base/split APK seti; özel veri geri yükleme garantisi değildir.
- `backup.properties` ve `README.txt`: Dosya kimlikleri, hash'ler ve gerçek tema-yedek durumu.
- `themes.bak`: Yalnızca **PARTIAL** durumunda bulunur; bütün doğrulanmış erişilebilir harici tema kayıtları, bileşenleri ve önizlemeleri tek MIUI Backup v2 arşivindedir. Studio BAK dönüştürücü formatındadır; gerçek cihazda dönüşüm ve geri yükleme ayrıca denenmelidir.
- **PARTIAL** tam uygulama yedeği demek değildir; özel veriler, hesaplar ve ayarlar yedeklenmez. OS veya sürümler arası restore doğrulanmadı.
- **UNAVAILABLE**: ADB erişimi yok; **EMPTY**: erişilebilir kökte tema kaydı yok; **FAILED**: kopya/doğrulama başarısız. Bu durumlarda sahte/boş BAK yayımlanmaz. Kitaplık ve APK yedeği doğrulanırsa kullanıcı açık veri kaybı onayıyla kuruluma devam edebilir; tema yedeği alınmış sayılmaz.
- Arşiv sınırları: yaklaşık 1 GiB; girdi başına 512 MiB; Studio BAK okuyucusuyla uyumlu dosya sayısı. Bağlantı, izin veya sınır hatasında tam yedek iddiasında bulunulmaz.
- Test1'in public yedeği olmayan hazırlığı doğrudan kurulum izni vermez; yeniden **Hazırla ve Yedekle** gerekir.

## Başarı ölçütü

- Temalar paket adı ve Xiaomi imzası korunur; sürüm **3.0.5.6-global / 3000506** görünür.
- Eski `ApplyThemeForScreenshot` etkinliği çözümlenir. Bu, gerçek tema içe aktarma/uygulamanın ayrıca denenmesi gerektiğini ortadan kaldırmaz.
- Kullanıcının sahip olduğu küçük bir MTZ ile içe aktarma/uygulama deneyin; Temalar'ın açıldığını ve tema sonucunu kontrol edin.

## Başarısızlık / kesinti

- Karttaki aşama ve hata kodunu kaydedin. Özellikle `INSTALL_FAILED_VERSION_DOWNGRADE` bu yöntemin ilgili ROM'da engeli aşmadığını gösterir; tekrar tekrar kaldırma denemeyin.
- **Temalar’ı yeniden etkinleştir** düğmesi, ayrı onayla yalnızca `pm install-existing --user 0 com.android.thememanager` yolunu dener ve paket kimliğini kontrol eder.
- Bu işlem **fabrika sürümünü garanti etmez ve silinen verileri geri getirmez**. Temalar zaten görünüyorsa yeniden kaldırılmaz.
- Paket kurtarma doğrulanamazsa işlemi durdurun. Uygulama dışında sakladığınız APK seti ve cihazın yedekleme yöntemiyle ayrıca müdahale gerekir; eksik split APK ile kurulum denemeyin.
- Live Diagnostics içinde `rootless_recovery_state` kayıtlarını paylaşabilirsiniz. Hata kodu ve sürümler kaydedilir; özel yedek dosyaları veya tam cihaz dökümleri kaydedilmez.

## Geliştirici kontrolleri

- Durum makinesi testleri: onaysız işlem, bozuk hedef/yedek, hizmet kaybı, değişen paket, staging/günlük yazma hatası, belirsiz kaldırma, eski günlük, eksik/değişmiş public URI, eksik toplu BAK/sayım, imza uyuşmazlığı ve tehlikeli komutların reddi.
- Tek toplu BAK testleri: birden çok tema, manifest/başlık, byte karşılaştırması, USTAR uzun yolları; traversal, link, checksum, truncation, terminator, boyut/dosya sınırı reddi.
- Android debug APK derlemesi ve app/tester-adapter birim testleri çalıştırılır.
- Gerçek HyperOS kaldırma/kurulum ve veri kaybı/kurtarma davranışı masaüstü birim testleriyle doğrulanmış sayılmaz.
