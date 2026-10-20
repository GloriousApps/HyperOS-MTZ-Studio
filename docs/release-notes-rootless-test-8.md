# HyperOS MTZ Studio 5.6.3-rootless-test.8

**Deneysel MediaStore tanı build'i.** Sürüm kodu **87**, kaynak dal **main**.

## Ayrıntılı Live Diagnostics

`BACKUP_PUBLIC_MEDIASTORE_VERIFY` artık tek bir genel hata olarak kalmaz. Doğrulamanın hangi kontrolünde hata olduğunu gösterir:

- `BACKUP_PUBLIC_VERIFY_URI`: URI biçimi
- `BACKUP_PUBLIC_VERIFY_ROW`: MediaStore satırı okunamadı
- `BACKUP_PUBLIC_VERIFY_NAME`: dosya adı farklı döndü
- `BACKUP_PUBLIC_VERIFY_PATH`: klasör yolu farklı döndü
- `BACKUP_PUBLIC_VERIFY_PENDING`: `IS_PENDING` beklenen değerde değil
- `BACKUP_PUBLIC_VERIFY_METADATA`: metadata sorgusu başarısız
- `BACKUP_PUBLIC_VERIFY_READBACK`: dosya tekrar açılamadı/okunamadı
- `BACKUP_PUBLIC_VERIFY_HASH`: boyut veya SHA-256 eşleşmedi

Live Diagnostics içinde ayrıca yalnızca güvenli özet bilgiler kaydedilir: aşama, hata kodu, Android SDK, hedef public klasör ve public dosya sayısı. URI, özel uygulama yolu, yedek içeriği ve ham provider çıktısı kaydedilmez.

Doğrulama hâlâ zorunludur; hata durumunda kurulum açılmaz ve oluşturulan public kayıtlar temizlenir. Studio'yu kaldırmadan mevcut test APK'sının üzerine kurun, **Hazırla ve Yedekle** işlemini tekrar deneyin ve yeni alt hata kodunu paylaşın.
