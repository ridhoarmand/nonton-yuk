import 'package:flutter/services.dart';

/// Native platform bridge for backend-resolved metadata and downloads.
const platformChannel = MethodChannel('com.nonton.yuks/platform');

/// Push stream of download progress entries from the native side.
const progressChannel = EventChannel('com.nonton.yuks/progress');

enum TaskState { queued, running, paused, done, failed, canceled, interrupted }

class ExtractionException implements Exception {
  const ExtractionException(this.message);

  final String message;

  @override
  String toString() => message;
}

/// Human-readable file size, `''` when unknown.
String formatBytes(int bytes) {
  if (bytes <= 0) return '';
  const kb = 1024;
  const mb = kb * 1024;
  const gb = mb * 1024;
  if (bytes >= gb) return '${(bytes / gb).toStringAsFixed(1).replaceAll('.', ',')} GB';
  if (bytes >= mb) return '${(bytes / mb).round()} MB';
  if (bytes >= kb) return '${(bytes / kb).round()} KB';
  return '$bytes B';
}

/// Clock label such as `10:35` or `1:02:03`.
String formatDuration(int milliseconds) {
  if (milliseconds <= 0) return '0:00';
  final total = milliseconds ~/ 1000;
  final h = total ~/ 3600;
  final m = (total % 3600) ~/ 60;
  final s = total % 60;
  final ss = s.toString().padLeft(2, '0');
  return h > 0 ? '$h:${m.toString().padLeft(2, '0')}:$ss' : '$m:$ss';
}

String? _string(Object? value) => value is String ? value : null;

int _int(Object? value) => value is int ? value : (value is num ? value.toInt() : 0);

double _double(Object? value) => value is num ? value.toDouble() : 0;

TaskState _state(Object? value) => value is String
    ? TaskState.values.firstWhere((s) => s.name == value, orElse: () => TaskState.queued)
    : TaskState.queued;

class ProbeResult {
  const ProbeResult({
    required this.id,
    required this.platform,
    required this.title,
    this.uploader,
    this.durationMs = 0,
    this.thumbnailUrl,
    this.approxBytes = 0,
    this.height = 0,
    this.heights = const [],
  });

  final String id;
  final String platform;
  final String title;
  final String? uploader;
  final int durationMs;
  final String? thumbnailUrl;
  final int approxBytes;
  final int height;
  final List<int> heights;

  factory ProbeResult.fromMap(Map<Object?, Object?> map) {
    final raw = map['heights'];
    final heights = raw is List
        ? [
            for (final v in raw)
              if (v is int && v > 0)
                v
              else if (v is num && v.toInt() > 0)
                v.toInt(),
          ]
        : const <int>[];
    if (raw is List && heights.length != raw.length) throw const ExtractionException('Balasan backend tidak dikenali.');
    return ProbeResult(
      id: _string(map['id']) ?? '',
      platform: _string(map['platform']) ?? '',
      title: _string(map['title']) ?? '',
      uploader: _string(map['uploader']),
      durationMs: _int(map['durationMs']),
      thumbnailUrl: _string(map['thumbnailUrl']),
      approxBytes: _int(map['approxBytes']),
      height: _int(map['height']),
      heights: heights,
    );
  }
}

class SavedMedia {
  SavedMedia({
    required this.id,
    this.platform = '',
    this.title = '',
    this.uploader,
    this.durationMs = 0,
    this.thumbnailUrl,
    this.thumbPath,
    this.path,
    this.bytes = 0,
    this.approxBytes = 0,
    this.state = TaskState.queued,
    this.progress = 0,
    this.etaSeconds = 0,
    this.phase,
    this.error,
    this.sourceUrl,
    this.createdAtMs = 0,
    this.height = 0,
    this.restarted = false,
  });

  String id;
  String platform;
  String title;
  String? uploader;
  int durationMs;
  String? thumbnailUrl;
  String? thumbPath;
  String? path;
  int bytes;
  int approxBytes;
  TaskState state;
  double progress;
  int etaSeconds;
  String? phase;
  String? error;
  String? sourceUrl;
  int createdAtMs;
  int height;
  bool restarted;

  bool get ready => state == TaskState.done && path != null;

  bool get active =>
      state == TaskState.queued ||
      state == TaskState.running ||
      state == TaskState.paused;

  bool get paused => state == TaskState.paused;

  String get sizeLabel => formatBytes(bytes > 0 ? bytes : approxBytes);

  /// Indonesian status text, folding [phase] into the running wording.
  String get statusLabel => switch (state) {
        TaskState.queued => 'Menunggu antrean',
        TaskState.running => switch (phase) {
            'merge' => 'Menggabungkan berkas',
            'video' => 'Mengunduh video',
            'audio' => 'Mengunduh audio',
            _ => 'Mengunduh',
          },
        TaskState.paused => 'Dijeda',
        TaskState.done => 'Siap offline',
        TaskState.interrupted => 'Unduhan terputus',
        TaskState.failed => 'Gagal',
        TaskState.canceled => 'Dibatalkan',
      };

  /// Parses one native entry; returns null for a malformed entry (missing id)
  /// so callers can skip it instead of failing the whole list.
  static SavedMedia? fromMap(Object? raw) {
    if (raw is! Map) return null;
    final id = _string(raw['id']);
    if (id == null || id.isEmpty) return null;
    return SavedMedia(
      id: id,
      platform: _string(raw['platform']) ?? '',
      title: _string(raw['title']) ?? '',
      uploader: _string(raw['uploader']),
      durationMs: _int(raw['durationMs']),
      thumbnailUrl: _string(raw['thumbnailUrl']),
      thumbPath: _string(raw['thumbPath']),
      path: _string(raw['path']),
      bytes: _int(raw['bytes']),
      approxBytes: _int(raw['approxBytes']),
      state: _state(raw['state']),
      progress: _double(raw['progress']),
      etaSeconds: _int(raw['etaSeconds']),
      phase: _string(raw['phase']),
      error: _string(raw['error']),
      sourceUrl: _string(raw['sourceUrl']),
      createdAtMs: _int(raw['createdAtMs']),
      height: _int(raw['height']),
      restarted: raw['restarted'] == true,
    );
  }

  /// Overlays a partial progress event: only keys actually present are applied,
  /// so absent optional keys never clobber known values.
  void applyEvent(Object? raw) {
    if (raw is! Map) return;
    if (raw.containsKey('platform')) platform = _string(raw['platform']) ?? platform;
    if (raw.containsKey('title')) title = _string(raw['title']) ?? title;
    if (raw.containsKey('uploader')) uploader = _string(raw['uploader']);
    if (raw.containsKey('durationMs')) durationMs = _int(raw['durationMs']);
    if (raw.containsKey('thumbnailUrl')) thumbnailUrl = _string(raw['thumbnailUrl']);
    if (raw.containsKey('thumbPath')) thumbPath = _string(raw['thumbPath']);
    if (raw.containsKey('path')) path = _string(raw['path']);
    if (raw.containsKey('bytes')) bytes = _int(raw['bytes']);
    if (raw.containsKey('approxBytes')) approxBytes = _int(raw['approxBytes']);
    if (raw.containsKey('state')) state = _state(raw['state']);
    if (raw.containsKey('progress')) progress = _double(raw['progress']);
    if (raw.containsKey('etaSeconds')) etaSeconds = _int(raw['etaSeconds']);
    if (raw.containsKey('phase')) phase = _string(raw['phase']);
    if (raw.containsKey('error')) error = _string(raw['error']);
    if (raw.containsKey('sourceUrl')) sourceUrl = _string(raw['sourceUrl']);
    if (raw.containsKey('createdAtMs')) createdAtMs = _int(raw['createdAtMs']);
    if (raw.containsKey('height')) height = _int(raw['height']);
    if (raw.containsKey('restarted')) restarted = raw['restarted'] == true;
  }
}

/// Skips malformed entries rather than failing the whole result.
List<SavedMedia> parseDownloadList(Object? raw) {
  if (raw is! List) return const [];
  final out = <SavedMedia>[];
  for (final entry in raw) {
    final media = SavedMedia.fromMap(entry);
    if (media != null) out.add(media);
  }
  return out;
}

Future<T> _guard<T>(Future<T> Function() body) async {
  try {
    return await body();
  } on ExtractionException {
    rethrow;
  } on PlatformException catch (e) {
    throw ExtractionException(e.message ?? 'Operasi gagal. Coba lagi.');
  } on MissingPluginException {
    throw const ExtractionException('Fitur unduhan tidak tersedia di perangkat ini.');
  } on TypeError {
    // Bentuk balasan native meleset dari yang diharapkan: nyatakan, jangan gantung layar.
    throw const ExtractionException('Balasan pengunduh tidak dikenali. Perbarui aplikasi lalu coba lagi.');
  }
}

/// Theme preference stored natively.
class AppSettings {
  const AppSettings({this.themeMode = 'light'});

  final String themeMode;

  factory AppSettings.fromMap(Map<Object?, Object?> map) => AppSettings(
        themeMode: _string(map['themeMode']) ?? 'light',
      );

  Map<String, String> toMap() => {'themeMode': themeMode};
}

Future<AppSettings> getSettings() => _guard(() async {
      final res = await platformChannel.invokeMapMethod<Object?, Object?>('getSettings');
      if (res == null) return const AppSettings();
      return AppSettings.fromMap(res);
    });

Future<void> setSettings(AppSettings settings) => _guard(() async {
      await platformChannel.invokeMethod<Object?>('setSettings', settings.toMap());
    });

Future<ProbeResult> probeLink(String url, String platformId) => _guard(() async {
      final res = await platformChannel.invokeMapMethod<Object?, Object?>(
          'probe', {'url': url, 'platform': platformId});
      if (res == null) throw const ExtractionException('Tidak bisa membaca informasi video. Coba lagi.');
      return ProbeResult.fromMap(res);
    });

Future<String> enqueueDownload({
  required String url,
  required String id,
  required String title,
  required String platform,
  String? uploader,
  int durationMs = 0,
  String? thumbnailUrl,
  int approxBytes = 0,
  int height = 0,
}) =>
    _guard(() async {
      final rid = await platformChannel.invokeMethod<String>('enqueue', {
        'url': url,
        'id': id,
        'title': title,
        'platform': platform,
        'uploader': ?uploader,
        'durationMs': durationMs,
        'thumbnailUrl': ?thumbnailUrl,
        'approxBytes': approxBytes,
        if (height > 0) 'height': height,
      });
      if (rid == null || rid.isEmpty) throw const ExtractionException('Gagal memulai unduhan. Coba lagi.');
      return rid;
    });

Future<void> cancelDownload(String id) => _guard(() async {
      await platformChannel.invokeMethod<Object?>('cancel', {'id': id});
    });

Future<void> pauseDownload(String id) => _guard(() async {
      await platformChannel.invokeMethod<Object?>('pause', {'id': id});
    });

Future<void> resumeDownload(String id) => _guard(() async {
      await platformChannel.invokeMethod<Object?>('resume', {'id': id});
    });

/// wifi, cellular, or unknown: the mobile-data warning needs no exact subtype.
Future<String> networkType() => _guard(() async {
      final value = await platformChannel.invokeMethod<String>('networkType');
      return value ?? 'unknown';
    });

Future<void> deleteDownload(String id) => _guard(() async {
      await platformChannel.invokeMethod<Object?>('delete', {'id': id});
    });

Future<List<SavedMedia>> listDownloads() => _guard(() async {
      final raw = await platformChannel.invokeListMethod<Object?>('listDownloads');
      return parseDownloadList(raw);
    });

