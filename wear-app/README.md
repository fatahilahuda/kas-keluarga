# Muarakas untuk Wear OS

Aplikasi jam native (Kotlin + Compose for Wear OS) untuk Galaxy Watch 4 ke atas dan jam Wear OS 3+ lainnya. Berdiri sendiri: jam bicara langsung ke Supabase lewat RPC yang sama dengan HP (`kas_pull`, `kas_push`), jadi catatan dari jam muncul di HP dan sebaliknya.

## Isi

- **Catat cepat**: ucapkan "bakso 15 ribu" (nominal dan kategori terisi otomatis, AI kategori sama dengan di HP) atau ketik nominal di keypad. Bisa pengeluaran, pemasukan, atau pindah saldo antar kantong. Tanggal bisa hari ini, kemarin, atau 2 hari lalu.
- **Ringkasan**: sisa bulan ini, masuk/keluar, anggaran dan jatah harian, pengeluaran per kategori dengan batas kategori, laba usaha. Bisa mundur ke bulan sebelumnya.
- **Riwayat**: catatan terbaru per hari; catatan sendiri bisa dihapus.
- **Kantong**: saldo tiap kantong dan progres target tabungan.
- **Modul**: usaha, target, utang & piutang, emas (harga emas harian seperti di HP).
- **Tile**: sisa anggaran dengan cincin progres, tombol Suara dan Nominal.
- **Komplikasi**: pengeluaran bulan ini (teks pendek atau cincin terhadap anggaran) untuk watch face.

Yang sengaja tidak ada di jam: pengaturan (kategori, kantong, anggaran, modul) dan catatan rahasia milik pasangan.

## Menyambungkan

1. Buka Muarakas di jam. Jam menampilkan kode 6 digit.
2. Di HP: Muarakas → Pengaturan → **Jam tangan**, ketik kodenya.
3. Jam langsung tersinkron dan tahu jam ini milik siapa.

Cadangan: di jam pilih **Pakai kode keluarga** lalu ketik kode 12 huruf dari halaman Sinkron di HP.

Penyambungan 6 digit butuh fungsi di `supabase/kas_watch_pairing.sql` (jalankan sekali di SQL Editor Supabase).

## Build

GitHub Actions (`.github/workflows/wear-apk.yml`) membangun APK setiap push ke branch `aplikasi-wear` yang mengubah folder ini. Isi secret `WEAR_KEYSTORE_BASE64`, `WEAR_KEYSTORE_PASSWORD`, `WEAR_KEY_ALIAS`, `WEAR_KEY_PASSWORD` supaya APK langsung ditandatangani. Tanpa itu hasilnya draft release `Muarakas-Wear-unsigned.apk`, yang bisa ditandatangani dengan `android-app/tools/v2sign.py` di branch `aplikasi-android`.

## Memasang di jam

Wear OS tidak bisa memasang APK dari berkas biasa. Cara termudah dari HP Android:

1. Di jam: Setelan → Info jam → Info perangkat lunak, ketuk **Versi perangkat lunak** 5 kali (Opsi pengembang aktif).
2. Setelan → Opsi pengembang → nyalakan **Debugging ADB** dan **Debug lewat Wi-Fi**. Catat alamat IP:port yang muncul. Jam dan HP harus di Wi-Fi yang sama.
3. Di HP pasang aplikasi **Bugjaeger** (atau Easy Fire Tools), sambungkan ke IP jam, setujui di jam, lalu pasang `Muarakas-Wear.apk`.

Dari komputer: `adb connect IP:PORT` lalu `adb install Muarakas-Wear.apk`.
