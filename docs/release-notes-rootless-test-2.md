# HyperOS MTZ Studio 5.6.3-rootless-test.2

**Deneysel ön sürüm — kararlı güncelleme değildir.** Sürüm kodu **81**, kaynak dal **main**.

## Yenilikler

- Dört ayrı işlem yerine aşamaya göre **tek ana düğme**: önce **Hazırla ve Yedekle**, ardından **Eski sürümü kur**. Açıklamalar bilgi simgesinde; veri kaybı onayı kurulum penceresinde.
- Doğrulanmış yedekler otomatik **Downloads/MTZ Studio/Backups/<zaman-kimlik>/** altına kaydedilir; manuel dışa aktarma gerekmez.
- ADB erişimi izin veriyorsa okunabilen harici temaların tamamı **tek toplu `themes.bak`** dosyasına alınır. Tema başına ayrı BAK üretilmez. Kart gerçek tema sayısını ve kısmi yedek durumunu gösterir.
- Studio kitaplığı, mevcut base/split APK seti, manifest ve açıklama dosyası ayrıca korunur. Her public dosya yeniden açılır ve SHA-256/boyut/konum doğrulanır; kurulum öncesinde tekrar kontrol edilir.
- Test1'in public yedeği olmayan eski hazırlığı kurulum açmaz; yeniden hazırlık gerekir.

## Güvenlik ve kapsam

Global Xiaomi Temalar **3.4.1.23** sistem uygulaması, Android 10+, kullanıcı 0 ve ADB modunda yetkilendirilmiş **Shevery/Shizuku** gerekir. Hedef doğrulanmış orijinal **3.0.5.6-global / 3000506** APK'sıdır. Root, global kaldırma veya sistem güncellemelerini topluca kaldırma kullanılmaz.

> **Temalar sürümünün eski versiyonunu kurmak için mevcut sürüm kaldırılacak ve temalarınız silinecektir.**

`themes.bak` **kısmi harici içerik** yedeğidir; tam Temalar özel verisi, hesap veya ayar yedeği değildir. Sistem geri yüklemesi, sürümler arası geri yükleme ve kaldırma sonrası restore garantisi yoktur. Erişim yoksa/boşsa/kopya başarısızsa bu açıkça gösterilir; sahte BAK oluşturulmaz.

Kullanıcıdan kaldırma, ROM'un sürüm/fabrika alt sınırını aşmayı garanti etmez; eski APK'nın kurulması yine reddedilebilir. Ayrı onaylı **Temalar’ı yeniden etkinleştir** işlemi silinen verileri geri getirmez veya fabrika sürümünü garanti etmez. Önemli temaları olan ana cihaz yerine test cihazı kullanın.

## Kurulum ve test

**Studio'yu kaldırmayın.** Aynı `dev.glorioustr.mtzstudio` paketi ve resmi release imzası korunur. Kod 81 mevcut test1 (80) ve kararlı 5.6.2 (79) üzerine güncelleme içindir. Daha düşük kodlu kararlı APK'ya normal güncellemeyle dönülemez; veri koruyan dönüş için daha yüksek kodlu, aynı imzalı build gerekir.

Panel → Temalar uyumluluğu → **Hazırla ve Yedekle** → public klasörü ve gerçek tema-yedek durumunu kontrol et → yalnızca veri kaybını kabul ediyorsanız **Eski sürümü kur**.

Başarısız kurulum/kesintide kaldırmayı tekrarlamayın; kartın sunduğu ayrı onaylı kurtarmayı kullanın. Özel tema/kitaplık yedeklerini GitHub'a veya hata günlüklerine eklemeyin.

## Doğrulama

- Debug APK derlemesi başarılı.
- App birim testleri: **17 geçti**; tester-adapter birim testleri: **94 geçti** (21 kurtarma politikası ve 9 toplu BAK testi dahil).
- Çeviri kontrolü ve `git diff --check` başarılı.
- Gerçek HyperOS üzerinde MediaStore/ADB erişimi, BAK → MTZ dönüşümü, kaldırma/kurulum ve silinen verilerin geri yüklenmesi henüz doğrulanmadı. Birim testleri cihaz testi veya restore garantisi değildir.

Tam yönerge: [rootsuz kurtarma testi](https://github.com/GloriousApps/HyperOS-MTZ-Studio/blob/test-rootless-5.6.3.2/docs/rootless-recovery-test.md).
