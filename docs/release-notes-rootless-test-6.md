# HyperOS MTZ Studio 5.6.3-rootless-test.6

**Deneysel HyperOS MediaStore uyumluluk build'i.** Sürüm kodu **85**, kaynak dal **main**.

## Düzeltme

HyperOS 3 MediaProvider bazı cihazlarda `IS_PENDING=1` satırına yazmayı kabul edip aynı satırdan hemen okumayı reddedebiliyor. Önceki build bu erken okumayı `BACKUP_PUBLIC_MEDIASTORE_VERIFY` olarak raporluyordu.

Bu build:

- Dosyayı pending MediaStore satırına yazar.
- Pending satırdan erken readback yapmaz.
- Satırları görünür (`IS_PENDING=0`) yaptıktan sonra ad, klasör, pending durumu, boyut ve SHA-256 değerlerini tekrar açıp zorunlu olarak doğrular.
- Son doğrulama başarısızsa oluşturulan tüm public satırları siler ve kuruluma izin vermez.

Studio'yu kaldırmadan mevcut test APK'sının üzerine kurun ve **Hazırla ve Yedekle** işlemini tekrar deneyin. Başarılı olursa yedekler `Downloads/MTZ Studio/Backups/<zaman-id>/` altında görünmelidir.
