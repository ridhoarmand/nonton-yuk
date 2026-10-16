# Nonton Yuks

Simpan video publik Facebook dan YouTube di ponsel untuk ditonton offline. Package Dart `nonton_yuks`, application ID Android `com.nonton.yuks`.

Fitur:

- Antrean unduhan FIFO: jeda/lanjutkan tanpa mengulang dari awal, progres persen + byte + kecepatan + ETA.
- Pilihan resolusi YouTube saat pratinjau; thumbnail tampil selama mengunduh.
- Konfirmasi sebelum mengunduh memakai paket data seluler.
- Pemutar offline: putar/jeda, lompat 10 detik, kecepatan pemutaran, dan layar penuh. Volume serta kecerahan mengikuti perangkat.
- Tema Terang/Gelap/Sistem.
- Koleksi berupa daftar terbaru lebih dahulu, dengan thumbnail, durasi, ukuran berkas, dan menu hapus. Ketuk thumbnail atau baris untuk memutar.

Backend ekstraksi terpisah (Python stdlib + yt-dlp, baca di repo `backend-nonton-yuks`): server hanya menjawab deskriptor CDN, ponsel yang mengunduh dan menggabungkan video dengan `MediaMuxer` bawaan Android. Tidak ada Firebase, akun, WebView, atau folder publik di APK; berkas di penyimpanan privat (`filesDir/videos/`, `filesDir/thumbs/`, `filesDir/downloads.json`).

## Syarat

- Flutter SDK (stable) + Android SDK (compile SDK sesuai `flutter.compileSdkVersion`).
- Backend sudah deploy dan bisa dijangkau ponsel via HTTPS.

## Konfigurasi backend (env, tanpa secret di repo)

Origin + token backend disuntik saat build Gradle, tidak ada di source code:

```sh
export NONTON_YUKS_API_ORIGIN=https://backend-Anda.example
export NONTON_YUKS_API_TOKEN=<isi NONTONIN_API_TOKEN di .env backend>
flutter build apk --release --split-per-abi
```

Alternatif: tulis `nontonYuksApiOrigin` / `nontonYuksApiToken` di `~/.gradle/gradle.properties`. Tanpa keduanya, probe/unduh gagal cepat dengan pesan konfigurasi. `android/local.properties`, `key.properties`, `*.jks`/`*.keystore` sudah di-gitignore.

## Perintah

```sh
flutter pub get
flutter analyze
flutter test
flutter build apk --debug
```

Tes instrumentasi native (`MediaTransferTest`, mux + verifikasi MP4) butuh emulator/device menyala:

```sh
android/gradlew -p android :app:connectedDebugAndroidTest
```

## Rilis

R8 + resource shrinking aktif. Rilis memakai kunci debug secara default; ganti `signingConfig` di `android/app/build.gradle.kts` untuk distribusi. Instaloni tanpa menghapus data:

```sh
adb install -r build/app/outputs/flutter-apk/app-arm64-v8a-release.apk
```

## Peta berkas

- `lib/platforms.dart` - registri platform dan pemilihan tepat satu tautan dari teks share.
- `lib/bridge.dart` - channel native, model data, `ExtractionException`, pemformat angka.
- `lib/download_controller.dart` - satu `ChangeNotifier`: langganan progres, teruskan perintah.
- `lib/main.dart` - pustaka (koleksi, antrean, riwayat), sheet tambah video, pengaturan tema.
- `lib/player_screen.dart` - pemutar berkas lokal, tanpa fallback streaming.
- `MainActivity.kt` - pemipaan channel, penampung share, tipe jaringan, dan sesi layar tetap menyala saat membuka player.
- `ApiClient.kt` - resolver `POST /v1/extract` + validasi deskriptor penuh.
- `MediaTransfer.kt` - HTTPS terpin, unduh per track dengan resume Range, mux salin-sampel, verifikasi MP4.
- `DownloadManager.kt` - antrean satu worker, commit atomik setelah verifikasi, jeda/lanjut.
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

`com.nonton.yuks/progress` (EventChannel) mendorong potongan entri saat status berubah. Status: `queued`, `running` (`phase` `download`/`video`/`audio`/`merge`, `progress` 0..1, `etaSeconds`, `speedBps`, `bytes`), `paused`, `done`, `failed`, `canceled`, `interrupted`. URL bertanda tangan dan token tidak pernah mencapai Dart, log, atau store.
