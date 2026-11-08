# Yazıya Dök

WhatsApp sesli mesajlarını telefonda, internetsiz yazıya döker (whisper.cpp, Türkçe).
Uygulamada internet izni yoktur, ses de metin de telefondan çıkmaz.

## İndir

[Releases](https://github.com/DenizTanisman/yaziya-dok/releases/latest) sayfasından `YaziyaDok-*.apk` dosyasını indir.
APK geliştirici anahtarıyla imzalıdır, telefonda "bilinmeyen uygulamaları yükle" iznini vermen gerekir.
En düşük sürüm Android 11, işlemci arm64 (`dotprod` ve `i8mm` destekli). Yalnızca Samsung Galaxy S24 Ultra üzerinde denendi.

## Kurulum

**1. Modelleri indir** ve telefonda `İndirilenler/Modeller/` klasörüne koy:

- [ggml-small-q5_1.bin](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin): hızlı aşama
- [ggml-medium-q5_0.bin](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q5_0.bin): doğru aşama
- [ggml-large-v3-turbo-q5_0.bin](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin): isteğe bağlı, en doğrusu ama yavaş

**2. İzinleri ver:** tüm dosyalara erişim, bildirimlere erişim, bildirim gösterme. Telefon USB'deyse:

```sh
PKG=dev.deniz.local_harness_ses
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
adb shell cmd notification allow_listener $PKG/dev.deniz.ses.service.WaListener
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
```

## Kullanım

1. **Otomatik:** WhatsApp'tan sesli mesaj bildirimi gelince uygulama dosyayı bulur, yazıya döker ve sessiz bir bildirim gösterir. Bildirime dokununca sohbet açılır, "Kopyala" düğmesi de vardır.
2. **Paylaş:** WhatsApp'ta sesli mesaja basılı tut › Paylaş › *Yazıya Dök*.
3. **Liste:** Uygulamayı açınca son sesli mesajlar listelenir, *Yazıya dök* ya da *Dinle*.

Dökümler iki aşamalıdır: önce `small` birkaç saniyede metni basar, sonra `medium` aynı bildirimi daha doğru metinle sessizce günceller.
Ayarlardan model değiştirilebilir.

## Ölçüm (S24 Ultra)

10 sesli mesaj, toplam 145 saniye. Hata oranı kelime hata oranıdır, noktalama hariç.

| Motor | Hata (ışın 5) | 15 sn ses, telefonda |
|---|---|---|
| whisper small q5_1 | %24 | ~5 sn |
| whisper medium q5_0 | %14 | ~20 sn |
| whisper large-v3-turbo q5_0 | %12 | ~37 sn |

## Kaynaktan derleme

```sh
git clone https://github.com/ggml-org/whisper.cpp third_party/whisper.cpp
git -C third_party/whisper.cpp checkout 6a8addd
export JAVA_HOME=<JDK 21 yolu>
./gradlew testDebugUnitTest assembleDebug
```

Yerel kod debug APK'da da Release olarak derlenir. CPU hedefi `armv8.2-a+dotprod+i8mm+fp16`.

whisper.cpp MIT lisanslıdır (Georgi Gerganov ve katkıda bulunanlar), APK içinde derlenmiş olarak yer alır.
