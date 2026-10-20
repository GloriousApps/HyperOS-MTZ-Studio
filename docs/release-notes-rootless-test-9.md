# HyperOS MTZ Studio 5.6.3-rootless-test.9

**Deneysel MediaStore OEM uyumluluk build'i.** Sürüm kodu **88**, kaynak dal **main**.

## Düzeltme

Bazı HyperOS/MIUI MediaProvider sürümleri, MediaStore'a verilen `DISPLAY_NAME` değerini normalize ediyor veya çakışma eki ekliyor. Bu nedenle önceki build yalnızca dosya adı farklı döndüğü için `BACKUP_PUBLIC_VERIFY_NAME` ile duruyordu.

Bu build:

- URI, public klasör, `IS_PENDING=0`, dosya boyutu ve SHA-256 doğrulamalarını korur.
- Yalnızca provider'ın görüntüleme adını değiştirmesini tolere eder.
- Beklenen ve gerçek dosya adını güvenli biçimde Live Diagnostics'e `rootless_media_store_name_normalized` olayıyla kaydeder.
- URI veya içerik doğrulaması başarısızsa yine public kayıtları temizler ve kuruluma izin vermez.

Studio'yu kaldırmadan mevcut test APK'sının üzerine kurun ve **Hazırla ve Yedekle** işlemini tekrar deneyin. Başarılı olursa dosyalar `Downloads/MTZ Studio/Backups/` altında görünmelidir.
