# İmleç yardımcısı

Metin kutusuna odaklanınca imlecin yanında mini ◀ ▶ oklar çıkar; imleci karakter karakter kaydırır.
Basılı tutunca hızlanır. Klavye değil, not defteri değil: yalnızca imleç yardımcısı.

## Nasıl çalışır
- Erişilebilirlik servisi odaktaki düzenlenebilir kutuyu ve imleç konumunu görür, oklar `ACTION_SET_SELECTION` ile imleci kaydırır.
- Oklar erişilebilirlik overlay penceresinde çizilir (ayrı "üstünde göster" izni gerekmez).
- Yalnızca imleç konumunu hesaplamak için odaktaki kutuya bakar; hiçbir şey kaydedilmez ve ağa gönderilmez (manifestte `INTERNET` izni yok). Şifre alanlarında metin içeriğine hiç bakılmaz.

## Seçim modu
Seçili metinde oklar mavi olur; ortadaki düğme hangi ucun (sol/sağ) oynayacağını seçer, uçlar birbirini geçmez. Seçim kendi modelimizle `ACTION_SET_SELECTION` (start<=end) ile yazılır; granülerlik eylemi seçimi imlece indirdiği için seçim modunda kullanılmaz (AOSP `setAccessibilitySelection` -> `stopTextActionMode`).
Uygulamadaki Tanılama kutusu son olayları (yalnızca mod/sayı) gösterir.

## Derleme
`main`'e push -> GitHub Actions -> Releases'te `ImlecYardimcisi-N.apk`.
İmza anahtarı repoda değil: Settings > Secrets > `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`. `versionCode` = Actions run numarası.

## İzinler
1. Erişilebilirlik hizmeti (APK ile kurulduysa: Uygulama bilgisi > ⋮ > Kısıtlı ayarlara izin ver)
2. Bildirimler (Duraklat / Devam et düğmesi için)
