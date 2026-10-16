# Nonton Yuks

Simpan video publik Facebook dan YouTube dari ponsel untuk ditonton offline. Package Dart `nonton_yuks`, application ID Android `com.nonton.yuks`.

Tema default Terang; pilih Gelap atau Sistem di Pengaturan lalu Simpan. Logo aplikasi dan launcher menggunakan artwork utuh `assets/nonton-yuk.jpeg`. Pink menandai aksi utama, biru informasi/progres; latar koleksi memakai gradasi diagonal pink-biru dari artwork, dengan kartu netral agar isi tetap terbaca. Font sistem, spacing 16, dan radius 8/12/20 mempertahankan keterbacaan serta struktur rak koleksi.

Application ID ini memasang aplikasi terpisah dari `com.nontonin.offline`. Koleksi dan pengaturan aplikasi lama tidak otomatis berpindah. Jangan uninstall aplikasi lama jika koleksinya masih diperlukan.

## Arsitektur

Backend yang sudah ada di `../../nontonin_api` membaca metadata dan menjawab deskriptor CDN; aplikasi mengunduh langsung dari CDN lalu menggabung dengan `MediaMuxer` bawaan Android. Tidak ada Python, FFmpeg, yt-dlp, WebView, akun, atau folder publik di APK: berkas ditulis ke penyimpanan privat (`filesDir/videos/`, `filesDir/thumbs/`, `filesDir/downloads.json`).

| Bagian | Stack backend |
| --- | --- |
| Runtime/HTTP | Python 3.14, standard library `ThreadingHTTPServer` + `BaseHTTPRequestHandler` (`Dockerfile`, `api.py`) |
| Ekstraksi | `yt-dlp==2026.8.19`, `yt-dlp-ejs==0.8.0` (`requirements.txt`) |
| JavaScript challenge | QuickJS-NG 0.12.0 (`Dockerfile`) |
| API | `POST /v1/extract`, JSON `{"text":"<tautan>"}`, header `Authorization: Bearer <token>`; token server dari `NONTONIN_API_TOKEN` |
| Deploy | Docker Compose, bind host `127.0.0.1:8080`, container read-only; ponsel mengakses lewat reverse proxy HTTPS, bukan HTTP publik |
| Data/video | Tanpa database server atau cloud video storage. Backend mengembalikan metadata/deskriptor, Android mengunduh CDN dan mux lokal; koleksi tersimpan privat di perangkat |

Rebranding memakai backend ini; tidak menambah Firebase, Supabase, autentikasi akun, atau backend kedua.

Peta berkas:

- `lib/platforms.dart` - registri platform (Facebook, YouTube) dan pemilihan **tepat satu** tautan dari teks apa pun yang dibagikan.
- `lib/bridge.dart` - channel native, model data (`SavedMedia`, `ProbeResult`, `TaskState`, `AppSettings`), `ExtractionException`, pemformat angka.
- `lib/download_controller.dart` - satu `ChangeNotifier`: langganan progres, gabung event, teruskan perintah. Startup tidak tergantung jaringan.
- `lib/main.dart` - pustaka library-first (koleksi, antrean, unduhan lainnya), sheet tambah video, pengaturan backend/tema. Tema terang/gelap/sistem.
- `lib/player_screen.dart` - pemutar berkas lokal: volume/kecepatan per sesi, kecerahan jendela aplikasi, layar penuh. Tanpa fallback streaming.
- `MainActivity.kt` - pemipaan channel, penampung share, sesi kecerahan jendela player.
- `ApiClient.kt` - settings (`SharedPreferences settings`), resolver `POST /v1/extract` + validasi deskriptor penuh.
- `MediaTransfer.kt` - HTTPS terpin (validasi semua jawaban DNS, SNI hostname asli), unduh per track, mux salin-sampel, verifikasi MP4.
- `DownloadManager.kt` - antrean FIFO satu worker, commit atomik hanya setelah verifikasi, token cancel per run, migrasi non-destruktif.
- `DownloadService.kt` / `DownloadStore.kt` - foreground service `dataSync`; JSON atomik (`AtomicFile`) di path yang sama.

### Kontrak channel

`com.nonton.yuks/platform` (MethodChannel):

| Metode | Argumen | Hasil |
| --- | --- | --- |
| `getSettings` | - | `{themeMode}` |
| `setSettings` | `{themeMode}` | - |
| `probe` | `{url, platform}` | `{id, title, uploader, platform, durationMs, thumbnailUrl, approxBytes, height, heights}` (`id` = `{platform}_{videoId}`; tanpa URL CDN) |
| `enqueue` | `url, id, title, platform, uploader, durationMs, thumbnailUrl, approxBytes` + `height` opsional (pilihan resolusi YouTube) | `id` |
| `cancel` / `delete` / `pause` / `resume` | `id` | - |
| `networkType` | - | `wifi`, `cellular`, atau `unknown` (dialog konfirmasi paket data) |
| `listDownloads` | - | daftar entri `SavedMedia` |
| `takePendingShares` | - | daftar teks share yang belum diambil |
| `beginPlayerSession` | - | `{brightness}` |
| `setPlayerBrightness` | `{value}` (0.05..1) | - |
| `endPlayerSession` | - | - |

`com.nonton.yuks/progress` (EventChannel) mendorong potongan entri yang sama saat status berubah; `MainActivity` juga memanggil `shareAvailable` di channel platform ketika share masuk saat aplikasi hidup.

Status satu entri: `queued`, `running` (dengan `phase` `download`/`video`/`audio`/`merge`, `progress` 0..1 tanpa pernah 100% sebelum commit, `etaSeconds`, `bytes`), `paused` (partial dipertahankan, lanjut tanpa restart), `done`, `failed`, `canceled`, `interrupted`. Total tak dikenal tampil tak tentu + byte berjalan; server yang mengabaikan Range memicu `restarted` ("Mulai dari awal"). Pesan galat selalu Bahasa Indonesia yang bisa ditindaklanjuti; URL bertanda tangan dan token tidak pernah mencapai Dart, log, atau store.

## Konfigurasi backend (env, tanpa secret di repo)

Origin + token backend disuntik saat build, tidak ada di source code:

```sh
export NONTON_YUKS_API_ORIGIN=https://backend-Anda.example
export NONTON_YUKS_API_TOKEN=$(grep NONTONIN_API_TOKEN /jalan/ke/backend/.env | cut -d= -f2-)
flutter build apk --release --split-per-abi
```

Alternatif tanpa env: tulis di `~/.gradle/gradle.properties` (`nontonYuksApiOrigin`, `nontonYuksApiToken`); `android/local.properties`, `key.properties`, `*.jks`/`*.keystore` sudah di-gitignore. Tanpa keduanya, probe/unduh gagal cepat dengan pesan konfigurasi.

## Perintah workstation ini

```sh
export JAVA_HOME=/home/ridhoarmand/.local/share/JetBrains/Toolbox/apps/android-studio/jbr
export ANDROID_HOME=/home/ridhoarmand/Android/Sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$HOME/.cache/flutter_sdk/bin:$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
flutter pub get
flutter analyze
flutter test
flutter build apk --debug
android/gradlew -p android :app:connectedDebugAndroidTest   # butuh AVD menyala
```

Overlay Flutter privat di `$HOME/.cache/flutter_sdk`. Emulator yang dipakai: AVD `nontonin_light` (Android 30 `google_apis` x86_64, RAM 2 GB, tanpa Play Store).

## Ukuran dan rilis

R8 + resource shrinking aktif; tidak ada AAR/R8-workaround tersisa. Native libs hanya `libflutter.so` + `libapp.so`.

```sh
flutter build apk --release --split-per-abi
```

Pengukuran sebelum rebranding/application ID baru (baseline: APK universal lama 52.424.274 byte = 50,00 MiB, tanpa R8, berisi Python/FFmpeg):

| ABI | Byte | MiB | Turun vs baseline |
| --- | --- | --- | --- |
| arm64-v8a | 19.322.596 | 18,43 | 63,1% |
| x86_64 | 20.822.762 | 19,86 | 60,3% |
| armeabi-v7a | 16.731.312 | 15,96 | 68,1% |

Kunci rilis masih kunci debug. AAB adalah opsi delivery Play, bukan angka yang dilaporkan.

## Bukti dan batasan

Verifikasi rebranding `com.nonton.yuks`:

- `flutter test test/collection_test.dart`: 8 lulus; `flutter test`: 39 lulus; `flutter analyze`: tanpa issue.
- APK debug berhasil dibangun; `aapt dump badging` mengonfirmasi ID `com.nonton.yuks`, label `Nonton Yuks`, activity `com.nonton.yuks.MainActivity`, dan adaptive launcher icon. Target `:app:assembleDebugAndroidTest` berhasil dikompilasi; instrumentasi tidak dijalankan ulang.
- AVD Android 30: instalasi baru tetap Terang saat sistem Gelap, koleksi kosong terbuka tanpa backend, Terang/Gelap/Sistem tersimpan setelah restart, dan share YouTube membuka sheet dengan arahan konfigurasi backend.
- Header 320dp/teks 200% di kedua tema: nama membungkus utuh dan Pengaturan/Tambah video tetap bisa dioperasikan. Keyboard Tab/Enter serta field di atas keyboard diperiksa selama smoke rebranding.
- Lima launcher legacy berukuran 48/72/96/144/192px; foreground adaptive 432px dengan alpha dan logo utuh pada mask lingkaran. Kontras teks role yang digunakan minimal 4.5:1, batas kontrol minimal 3:1.
- Instalasi lama tetap ada, dengan hash pengaturan, metadata koleksi, MP4, dan thumbnail tidak berubah. Playback offline diperiksa sebelum cutover ID; aplikasi baru tidak menerima salinan koleksi lama. Networking/backend dan release APK baru tidak diuji ulang.

Baseline sebelum rebranding (`com.nontonin.offline`):

36 tes Dart + 21 regression backend hijau; 5/5 instrumentasi mux (`MediaTransferTest`) di AVD. Alur nyata debug: pengaturan backend → tambah → periksa → unduh pasangan H264/AAC → mux → `done` terverifikasi → putar dengan suara/gambar → backend dimatikan → tetap putar offline. Duplikat selesai ditolak dengan pesan yang menyebut judul lama. Upgrade `adb install -r` ke release: koleksi/thumb/hash utuh, task basi menjadi `interrupted` tanpa autostart. Release x86_64 di AVD: startup + playback lokal lulus (R8 tidak merusak path native/player).

Belum diverifikasi; jangan dianggap aman:

- Perangkat `arm64-v8a` fisik (artefak diukur, belum diinstal di hardware arm64).
- Release networking end-to-end lewat HTTPS: butuh trust CA uji di AVD (jalur `adb root/remount` gagal karena filesystem read-only walau verity sudah off; tidak ada bypass TLS yang dipasang).
- Signed URL terikat IP backend vs ponsel beda jaringan: fresh-resolve saat job mulai mengatasi expiry, bukan IP binding.
- Android 13+ (izin notifikasi runtime) dan Android 14+ (batas FGS `dataSync`) di API aslinya.
- Sampel Facebook besar/progresif-HD penuh; dua sampel FB publik tidak tersedia tanpa login saat smoke.
