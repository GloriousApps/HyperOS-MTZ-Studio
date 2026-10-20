# HyperOS MTZ Studio 5.6.3-rootless-test.4

**Deneysel tanı build'i.** Sürüm kodu **83**.

`BACKUP_PUBLIC` hatası artık daha küçük kontrollü aşamalara ayrılır:

- `BACKUP_PUBLIC_ARCHIVE`: APK arşivi oluşturma/doğrulama
- `BACKUP_PUBLIC_README`: açıklama dosyası oluşturma
- `BACKUP_PUBLIC_MEDIASTORE`: Downloads satırı oluşturma veya dosya yazma
- `BACKUP_PUBLIC_PUBLISH`: MediaStore dosyalarını görünür yapma
- `BACKUP_PUBLIC_RECHECK`: yayınlanan URI, konum, boyut veya SHA-256 doğrulaması

Kullanıcıdan kaldırma/kurulum yapılmaz. Studio'yu kaldırmadan mevcut test sürümünün üzerine kurun ve **Hazırla ve Yedekle** işlemini tekrar deneyin. Hata olursa görünen kodu paylaşın. Başarılı olursa `Downloads/MTZ Studio/Backups/` klasörünü kontrol edin.
