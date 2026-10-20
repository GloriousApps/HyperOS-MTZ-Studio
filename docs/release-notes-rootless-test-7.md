# HyperOS MTZ Studio 5.6.3-rootless-test.7

**Deneysel HyperOS MediaStore path uyumluluk build'i.** Sürüm kodu **86**, kaynak dal **main**.

HyperOS cihazında `Download/MTZ Studio/Backups/<benzersiz-id>/` yolu MediaStore tarafından klasör kaydı olarak normalize edildiği için yayın sonrası dosya readback doğrulaması başarısız olabiliyordu.

Bu build:

- Dosyaları doğrudan `Download/MTZ Studio/Backups/` içine yazar.
- Her dosya adına benzersiz işlem öneki ekler; tekrar hazırlama ve eski yedeklerle çakışmaz.
- Dosyalar görünür olduktan sonra ad, klasör, `IS_PENDING=0`, boyut ve SHA-256 doğrulamasını sürdürür.
- Doğrulama başarısızsa public kayıtları temizler ve kurulum açmaz.

Studio'yu kaldırmadan mevcut test APK'sının üzerine kurun. **Hazırla ve Yedekle** sonrasında dosyalar doğrudan `Downloads/MTZ Studio/Backups/` içinde görünmelidir.
