# HyperOS MTZ Studio 5.6.3-rootless-test.5

**Deneysel MediaStore uyumluluk build'i.** Sürüm kodu **84**, kaynak dal **main**.

Bu sürümde `BACKUP_PUBLIC_MEDIASTORE` için iki uyumluluk düzeltmesi yapıldı:

- `RELATIVE_PATH` artık Xiaomi/MIUI MediaProvider'ın reddedebileceği son `/` karakterini taşımıyor.
- Public MediaStore dosyaları provider'ın varsayılan çıktı modu ile yazılıyor; açık `"w"` modu kullanılmıyor.

Yeni tanı kodları:

- `BACKUP_PUBLIC_MEDIASTORE_INSERT`
- `BACKUP_PUBLIC_MEDIASTORE_WRITE`
- `BACKUP_PUBLIC_MEDIASTORE_VERIFY`

Başarılı yedek yine `Downloads/MTZ Studio/Backups/<zaman-id>/` altında oluşur. Studio'yu kaldırmadan mevcut test APK'sının üzerine kurun. Bu build oluşturulurken ADB cihazı çalışma ortamında görünmediği için gerçek MediaStore işlemi fiziksel cihazda yeniden denenmelidir.
