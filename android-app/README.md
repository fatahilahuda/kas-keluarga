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
- `window.KasNfc` untuk undangan keluarga lewat NFC (`KasNfcPlugin.java`):
  - **Kirim**: `NfcInviteService.java` membuat HP berperan seperti stiker NFC (NFC Forum Type 4) berisi tautan `…/kas-keluarga/#gabung=KODE`, hanya selama tombol "Kirim lewat NFC" aktif. HP Android dengan aplikasi ini langsung membuka Muarakas; iPhone menawarkan membuka tautannya; HP Android lain membukanya di browser.
  - **Terima**: mode pembaca NFC selama lembar "Terima lewat NFC" terbuka, atau ketukan biasa saat HP tidak terkunci (filter `NDEF_DISCOVERED` di manifest).
  - Kejadian `url` dan `shared` diteruskan ke halaman sebagai event `kasnfc`.
- `window.KasBio` untuk membuka kunci aplikasi dengan sidik jari atau wajah (`KasBioPlugin.java`, BiometricPrompt Android), karena WebView tidak punya WebAuthn. `status()` memberi `{ available }`, `authenticate({ title, subtitle, cancel })` selalu selesai dengan `{ ok }` atau `{ ok: false, reason }` (`cancel`, `lockout`, `none`, `unavailable`, `error`).
- Izin kamera untuk pemindai QR undangan.

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

## Menandatangani APK di luar GitHub Actions

Kalau secret belum diisi, workflow menghasilkan `Muarakas-unsigned.apk` (draft release). APK itu bisa ditandatangani dengan kunci rilis (`muarakas-release.keystore`, alias `muarakas`, disimpan pemilik di luar repo):

```sh
python3 android-app/tools/v2sign.py Muarakas-unsigned.apk Muarakas.apk muarakas-release.keystore password.txt
```

Skrip ini memakai APK Signature Scheme v2 (cukup untuk minSdk 24) dan menolak APK yang entrinya belum ter-align. Sidik jari SHA-256 sertifikat rilis:
`E8:8A:79:56:5D:8A:0E:E7:54:0D:C0:9C:6E:EE:EF:C8:D0:2F:24:26:FD:4D:F9:63:20:EA:9E:A3:02:32:2B:AF`

## Catat cepat: suara, widget, shortcut

- **Suara** (`KasNative`, terpasang sebagai `window.KasQuickNative`): `SpeechRecognizer` langsung dengan bahasa `id-ID`, tanpa dialog Google. Web app menampilkan layar mendengarkan sendiri dan menerima event lewat `window.__kasVoice`.
- **Popup** (`QuickAddActivity`): jendela transparan berisi web app dalam mode `?quick=voice|text`, hanya sheet Catat. Origin sama dengan aplikasi utama, jadi datanya sama.
- **Widget** (`KasWidget`): sisa anggaran bulan ini plus tombol Suara dan Ketik. Angkanya dikirim web app lewat `KasQuickNative.widget(json)` setiap kali menyimpan; kalau kunci aplikasi aktif, angka disembunyikan.
- **Shortcut ikon** (`res/xml/shortcuts.xml`): tekan lama ikon Muarakas, pilih "Catat suara" atau "Catat ketik".
- **Tile Quick Settings** (`KasQsTile`): "Catat Muarakas" di panel notifikasi, langsung ke suara.
