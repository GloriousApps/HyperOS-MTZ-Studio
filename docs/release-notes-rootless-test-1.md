# HyperOS MTZ Studio 5.6.3-rootless-test.1

**Deneysel test APK'sı — kararlı güncelleme değildir.** Sürüm kodu: 80.

## Test hedefi

Rootsuz, Global Xiaomi Temalar **3.4.1.23** bulunan sistem uygulaması, Android 10+, kullanıcı 0 ve ADB modunda yetkilendirilmiş **Shevery/Shizuku**.

Hazırlık tamamlanıp veri kaybı kutusu ve ikinci onay verildikten sonra yalnızca kullanıcı 0 için Temalar kaldırılır; doğrulanmış **3.0.5.6-global** kurulumu denenir. Bu yöntem ROM'un sürüm kontrolünü aşmayı garanti etmez.

> **Temalar sürümünün eski versiyonunu kurmak için mevcut sürüm kaldırılacak ve temalarınız silinecektir.**
>
> Studio kitaplık yedeği ve APK seti saklanır; Xiaomi Temalar'ın özel verileri, temaları ve ayarları tam olarak yedeklenmez. Cihaz destekliyorsa önce HyperOS yerel yedeği alın. Hazırlıktan sonra kurtarma ZIP'ini uygulama dışına aktarın.

## İçerik

- Hedef APK için sabit SHA-256, paket, sürüm, SDK ve Xiaomi imzası kontrolü.
- Studio ZIP CRC/hash kontrolü ve kurulu base/split APK seti yedeği; iki dışa aktarma seçeneği.
- Her paket işleminden önce kalıcı günlük, tek işlem kilidi ve kesintiden sonra açık kurtarma.
- Ayrı onaylı `install-existing --user 0` kurtarması; fabrika sürümü veya silinen verilerin geri gelmesi garanti edilmez.
- Diğer kullanıcı/profillerde kurulu Temalar varsa işlemi durdurma.
- İndirme dosyasının kurulumdan önce silinmesi hatasının düzeltilmesi.
- Test derlemesine özel arayüz; kararlı sürüm/güncelleme kanalından ayrı GitHub ön sürümü.

## Kontroller

- Android debug APK: başarılı.
- App ve tester-adapter birim testleri: başarılı; yeni güvenlik durum makinesi için 12 test.
- `git diff --check`: başarılı.
- Gerçek HyperOS kaldırma, yeniden kurulum ve tema uygulama henüz cihaz üzerinde doğrulanmadı. Bu APK bu test için yayımlanmıştır.

## Test sırası

Panel → Temalar uyumluluğu → **Deneysel rootsuz Temalar kurtarma** → **Hazırla ve yedeği doğrula** → yedekleri dışa aktar → veri kaybı uyarısını onayla → **Kaldır ve eski sürümü kur**.

Başarısız olursa kaldırmayı tekrarlamayın. **Mevcut paketi yeniden etkinleştir** seçeneğini kullanın; ardından karttaki hata kodunu ve Live Diagnostics içindeki `rootless_recovery_state` kayıtlarını paylaşın. Özel yedek dosyalarını GitHub'a yüklemeyin.

Tam test yönergesi: [docs/rootless-recovery-test.md](https://github.com/GloriousApps/HyperOS-MTZ-Studio/blob/test-rootless-5.6.3.1/docs/rootless-recovery-test.md).

Bu resmi imzalı APK mevcut Studio paketinin üzerine kurulur; ayrı uygulama değildir. Studio'yu kaldırmak uygulamaya özel yedekleri de siler. Kod 79 olan kararlı 5.6.2'ye normal güncelleme ile dönüş yapılamaz; yedekleri önce dışa aktarın.
