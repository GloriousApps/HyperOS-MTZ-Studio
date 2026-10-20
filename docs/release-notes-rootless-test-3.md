# HyperOS MTZ Studio 5.6.3-rootless-test.3

**CHECK_FAILED tanısı için deneysel ön sürüm.** Sürüm kodu **82**, kaynak dal **main**.

## Bu sürümdeki düzeltme

Önceki test build'i hazırlama sırasında sıradan doğrulama istisnalarını yalnızca `CHECK_FAILED` olarak gösteriyordu. Bu sürüm, özel veri veya ham cihaz çıktısı açığa çıkarmadan kontrollü aşama kodu gösterir:

- `READY_CHECK`: Shizuku/ADB/user 0 hazırlık kontrolü
- `TARGET_CHECK`: kurulu Temalar ve hedef APK doğrulaması
- `BACKUP_LOCAL`: Studio kitaplığı veya kurulu APK setinin yerel yedeği
- `BACKUP_PUBLIC`: Downloads/MTZ Studio/Backups public dosyalarının oluşturulması
- `BACKUP_PUBLIC_VERIFY`: public yedek receipt veya dosya doğrulaması
- `BACKUP_RECHECK`: kurulum öncesi hazırlık snapshot doğrulaması

Kartta görünen kodu ve Live Diagnostics → **Tanılama dışa aktar** çıktısını paylaşabilirsiniz. Dışa aktarma özel yedek dosyalarını veya ham cihaz dökümünü içermez.

## Güvenlik

Bu build hiçbir paketi otomatik kaldırmaz. **Hazırla ve Yedekle** başarısız olursa kurulum açılmaz. Temalar’ı kaldırıp eski sürümü kurma işlemi hâlâ açık veri kaybı onayı ve ikinci onay gerektirir.

Önceki test build'leri gibi bu sürüm de Studio'nun üzerine güncellenir; Studio'yu kaldırmayın. Gerçek cihazdaki MediaStore erişimi, tek `themes.bak` içeriği ve Xiaomi Temalar'ın kaldırma/kurulum davranışı ayrıca doğrulanmalıdır.
