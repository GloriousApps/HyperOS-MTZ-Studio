# HyperOS MTZ Studio v5.1.0

## Tema çevirisi

- Çeviri ekranına isteğe bağlı **OCR Çeviri deneyseldir** akışı eklendi.
- ML Kit tabanlı OCR, tema görsellerindeki kısa Çince etiketleri cihaz üzerinde çevirmeyi dener.
- Güvenle düzenlenemeyen görseller değiştirilmez; böylece arka plan ve düzen bozulmalarına karşı daha güvenli davranılır.
- Super Duo ve benzer kilit ekranı bileşenleri için bilinen önizleme etiketleri iyileştirildi.
- OCR seçilmediğinde mevcut yerel çeviri akışı aynı şekilde kullanılabilir.

## Tema uygulama güvenilirliği

- Xiaomi Temalar özel yerel kataloğu geçici olarak okunamadığında işlem artık gereksiz şekilde engellenmez.
- Root MTZ Import akışında katalog ön-snapshot'ı yalnızca temizlik/eşleştirme için kullanılır; geçici katalog hatası gerçek içe aktarma ve uygulama adımını durdurmaz.
- Uygulama ve canlı tanılama kayıtlarına bu durum için ayrı teşhis bilgisi eklendi.

## Uygulama

- Uygulama sürümü `5.1.0` oldu.
