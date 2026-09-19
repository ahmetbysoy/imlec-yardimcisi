# İmleç yardımcısı

Metin kutusuna odaklanınca imlecin yanında mini ◀ ▶ oklar çıkar; imleci karakter karakter kaydırır.
Basılı tutunca hızlanır. Klavye değil, not defteri değil: yalnızca imleç yardımcısı.

## Nasıl çalışır
- Erişilebilirlik servisi odaktaki düzenlenebilir kutuyu ve imleç konumunu görür, oklar `ACTION_SET_SELECTION` ile imleci kaydırır.
- Oklar erişilebilirlik overlay penceresinde çizilir (ayrı "üstünde göster" izni gerekmez).
- Yazılan metin okunmaz, kaydedilmez, ağa gönderilmez.

## Derleme
`main`'e push -> GitHub Actions -> Releases'te `ImlecYardimcisi-N.apk`.

## İzinler
1. Erişilebilirlik hizmeti (APK ile kurulduysa: Uygulama bilgisi > ⋮ > Kısıtlı ayarlara izin ver)
2. Bildirimler (Duraklat / Devam et düğmesi için)
