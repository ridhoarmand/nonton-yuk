# NontonYuk!

Simpan video publik Facebook dan YouTube di ponsel untuk ditonton offline. Package Dart `nonton_yuks`, application ID Android `com.nonton.yuks`.

Fitur:

- Antrean unduhan dengan cache bersama: setelah buffer siap, ketuk **Tonton sekarang** sambil sisa video disimpan.
- Jeda/lanjutkan mempertahankan byte tersimpan, termasuk setelah aplikasi tertutup. Progres menampilkan persen, byte, kecepatan, dan ETA.
- Pilihan resolusi YouTube saat pratinjau; thumbnail tampil selama mengunduh.
- Konfirmasi sebelum mengunduh memakai paket data seluler.
- Pemutar offline: putar/jeda, lompat 10 detik, kecepatan pemutaran, dan layar penuh. Volume serta kecerahan mengikuti perangkat.
- Tema Terang/Gelap/Sistem.
- Koleksi berupa daftar terbaru lebih dahulu, dengan thumbnail, durasi, ukuran berkas, dan menu hapus. Ketuk thumbnail atau baris untuk memutar.

Backend ekstraksi terpisah (Python stdlib + yt-dlp, repo `backend-nonton-yuks`) hanya menjawab deskriptor CDN. Android menyimpan media ke cache privat Media3; player membaca cache yang sama. Video dan audio terpisah diputar bersamaan tanpa menulis ulang seluruh video menjadi satu MP4. Setelah seluruh bagian tersimpan, pemutaran tidak membutuhkan jaringan. MP4 koleksi lama tetap dapat diputar.

## Syarat

- Flutter SDK (stable) + Android SDK (compile SDK sesuai `flutter.compileSdkVersion`).
- Backend sudah deploy dan bisa dijangkau ponsel via HTTPS.

## Konfigurasi backend (env, tanpa secret di repo)

Origin + token backend disuntik saat build Gradle, tidak ada di source code:

```sh
export NONTON_YUKS_API_ORIGIN=https://backend.example
export NONTON_YUKS_API_TOKEN='token-dari-backend'
flutter build apk --release --obfuscate --split-per-abi --split-debug-info=build/symbols
```

Alternatif: tulis `nontonYuksApiOrigin` / `nontonYuksApiToken` di `~/.gradle/gradle.properties`. Tanpa keduanya, probe/unduh gagal cepat dengan pesan konfigurasi. `android/local.properties`, `key.properties`, `*.jks`/`*.keystore` sudah di-gitignore.

## Perintah

```sh
flutter pub get
flutter analyze
flutter test
flutter build apk --debug
```

Tes instrumentasi native (cache bersama, playback sebelum selesai, pemulihan jeda/proses, validasi transport, dan pemulihan MP4 lama) membutuhkan emulator khusus pengujian serta `JAVA_HOME` mengarah ke JDK Android Studio. Jangan jalankan pada perangkat berisi koleksi penting: tugas instrumentasi dapat menghapus aplikasi dan datanya setelah pengujian.

```sh
android/gradlew -p android :app:connectedDebugAndroidTest
```

## Rilis

R8 dan resource shrinking aktif; perintah di atas juga mengobfuscasi Dart. Simpan direktori `build/symbols/` bersama arsip rilis untuk membaca stack trace dengan `flutter symbolize`. Direktori build dan simbol tidak ikut Git. Obfuscation tidak membuat token di APK menjadi rahasia.

Rilis memakai kunci debug secara default; ganti `signingConfig` di `android/app/build.gradle.kts` untuk distribusi publik. Untuk pembaruan, gunakan sertifikat yang sama dan version code lebih tinggi. Instal tanpa menghapus data:

```sh
adb install -r build/app/outputs/flutter-apk/app-arm64-v8a-release.apk
```

## Jeda dan pemulihan

- Menutup aplikasi tidak menghapus cache. Saat dibuka lagi, unduhan yang belum lengkap dijeda; ketuk **Lanjutkan** untuk meneruskan. Cache yang sudah lengkap langsung dikenali sebagai siap offline.
- Hanya **Batalkan** atau **Hapus** yang menghapus data unduhan terpilih. Gangguan koneksi dan respons terpotong mempertahankan bagian yang sudah tersimpan.
- Jika server mendukung Range, hanya bagian yang belum ada yang diminta. Server yang mengabaikan Range memakai respons berurutan yang sama selama transfer; setelah jeda server dapat mengirim ulang awalan, tetapi cache dan progres tidak kembali ke nol.
- URL media dapat kedaluwarsa atau kontennya berubah. Aplikasi menolak mencampur representasi berbeda dan tidak menghapus cache diam-diam.
- Berkas video/audio lama yang sudah lengkap dapat diputar terpisah tanpa merge. Partial lama tanpa metadata yang cukup dipertahankan, tetapi tidak ditebak atau digabung dengan sumber baru.


## Peta berkas

- `lib/platforms.dart` - registri platform dan pemilihan tepat satu tautan dari teks share.
- `lib/bridge.dart` - channel native, model data, `ExtractionException`, pemformat angka.
- `lib/download_controller.dart` - satu `ChangeNotifier`: langganan progres, teruskan perintah.
- `lib/main.dart` - pustaka (koleksi, antrean, riwayat), sheet tambah video, pengaturan tema.
- `lib/player_screen.dart` - layar Flutter untuk native player, navigasi kembali, dan fullscreen.
- `CachedPlayerView.kt` - ExoPlayer/PlayerView dengan sumber berdasarkan ID unduhan, bukan URL dari Dart.
- `MainActivity.kt` - pemipaan channel, penampung share, tipe jaringan, dan lifecycle player.
- `ApiClient.kt` - resolver `POST /v1/extract` dan validasi deskriptor.
- `MediaCache.kt` - cache persisten, pengunduh dan player bersama, sumber video/audio serta fragmen berurutan.
- `PinnedHttpDataSource.kt` - HTTPS terpin, validasi DNS/redirect/Range, serta pembatalan socket.
- `MediaTransfer.kt` - pemeriksaan MP4 koleksi lama dan track lama yang sudah lengkap.
- `DownloadManager.kt` - antrean, progres byte cache, jeda/lanjut, dan pemulihan setelah proses berhenti.
- `DownloadService.kt` / `DownloadStore.kt` - foreground service `dataSync`; JSON atomik.

### Kontrak channel

`com.nonton.yuks/platform` (MethodChannel):

| Metode | Argumen | Hasil |
| --- | --- | --- |
| `getSettings` / `setSettings` | `{themeMode}` | - / - |
| `probe` | `{url, platform}` | `{id, title, uploader, platform, durationMs, thumbnailUrl, approxBytes, height, heights}` (tanpa URL CDN) |
| `enqueue` | `url, id, title, platform, ...` + `height` opsional | `id` |
| `cancel` / `delete` / `pause` / `resume` | `id` | - |
| `networkType` | - | `wifi`, `cellular`, atau `unknown` |
| `listDownloads` / `takePendingShares` | - | daftar entri / daftar teks share |

`com.nonton.yuks/progress` (EventChannel) mendorong potongan entri saat status berubah. Status: `queued`, `running`, `paused`, `done`, `failed`, `canceled`, `interrupted`; entri menyertakan `progress`, `etaSeconds`, `speedBps`, `bytes`, `playable`, dan `resumable`. Status `done` hanya diberikan saat seluruh byte media tersimpan.

Player native memakai platform view `com.nonton.yuks/player` dengan argumen `{id}`. URL media bertanda tangan dan header disimpan hanya di manifest privat Android agar transfer dapat dilanjutkan; tidak dikirim ke Dart atau log. Token backend tidak masuk manifest media.
