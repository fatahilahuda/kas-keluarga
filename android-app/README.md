# Muarakas untuk Android

Pembungkus [Capacitor](https://capacitorjs.com) yang menjadikan Muarakas aplikasi Android (APK).

Aplikasinya memuat https://fatahilahuda.github.io/kas-keluarga/ langsung, jadi setiap perubahan di `index.html` pada branch `main` otomatis ikut ke aplikasi tanpa perlu build APK baru. Service worker situs tetap jalan, sehingga setelah dibuka sekali aplikasinya bisa dipakai tanpa internet. Kalau pembukaan pertama gagal karena tidak ada sinyal, tampil `www/offline.html`.

APK hanya perlu dibangun ulang kalau isi folder `android-app/` berubah (ikon, nama, izin, plugin).

## Yang ditambahkan khusus Android

`android/app/src/main/assets/kas-shim.js` disuntikkan ke halaman sebelum skrip aplikasi berjalan (lewat `KasShimPlugin.java`):

- `navigator.share` / `navigator.canShare` diarahkan ke lembar Bagikan Android, karena WebView tidak punya Web Share API. Cadangan JSON, laporan CSV dan undangan keluarga jadi bisa dikirim.
- Klik `<a download>` dengan tautan `blob:` juga diarahkan ke lembar Bagikan (WebView mengabaikannya).
- Tombol Kembali menutup lembar/pop-up yang terbuka dulu, baru meminimalkan aplikasi.
- `navigator.standalone` bernilai `true`, jadi kartu "Pasang di layar utama" tidak muncul.

## Build

GitHub Actions (`.github/workflows/android-apk.yml`) membangun APK setiap ada push ke branch `aplikasi-android` yang mengubah folder ini, atau lewat tombol *Run workflow*.

Supaya APK langsung ditandatangani dan terbit di Releases, isi secret repositori berikut:

| Secret | Isi |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | file keystore dalam base64 |
| `ANDROID_KEYSTORE_PASSWORD` | kata sandi keystore |
| `ANDROID_KEY_ALIAS` | `muarakas` |
| `ANDROID_KEY_PASSWORD` | sama dengan kata sandi keystore |

Tanpa secret itu, workflow menghasilkan `Muarakas-unsigned.apk` sebagai artifact. Semua versi harus ditandatangani dengan kunci yang sama; kalau kuncinya berganti, aplikasi lama harus di-uninstall dulu sebelum versi baru bisa dipasang.

Build lokal (butuh Android SDK):

```sh
npm ci
npm run build:apk
```
