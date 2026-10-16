import 'dart:async';

import 'package:flutter/foundation.dart';

import 'bridge.dart';
import 'platforms.dart';

class DownloadController extends ChangeNotifier {
  DownloadController({Stream<Object?>? progressEvents})
      : _events = progressEvents ?? progressChannel.receiveBroadcastStream() {
    _subscription = _events.listen(_applyEvent);
  }

  final Stream<Object?> _events;
  final Map<String, SavedMedia> _items = {};
  final Set<String> _tombstones = {};
  StreamSubscription<Object?>? _subscription;
  Timer? _notifyTimer;
  DateTime? _lastNotify;
  bool _disposed = false;

  bool initialized = false;
  bool busy = false;
  String? error;

  /// Newest first.
  List<SavedMedia> get items => _items.values.toList()
    ..sort((a, b) => b.createdAtMs.compareTo(a.createdAtMs));

  void _notify() {
    if (_disposed) return;
    final now = DateTime.now();
    if (_lastNotify == null || now.difference(_lastNotify!) >= const Duration(milliseconds: 250)) {
      _lastNotify = now;
      notifyListeners();
      return;
    }
    _notifyTimer ??= Timer(const Duration(milliseconds: 250), () {
      _notifyTimer = null;
      _lastNotify = DateTime.now();
      if (!_disposed) notifyListeners();
    });
  }

  void _applyEvent(Object? event) {
    final created = SavedMedia.fromMap(event);
    if (created == null || _tombstones.contains(created.id)) return;
    final existing = _items[created.id];
    if (existing == null) {
      _items[created.id] = created;
    } else {
      existing.applyEvent(event);
    }
    _notify();
  }

  /// Subscribes to progress and reconciles with the native list. Call once.
  /// Library startup never depends on the network; failures surface per action.
  Future<void> initialize() async {
    _subscription ??= _events.listen(_applyEvent);
    await reload();
  }

  Future<void> reload() async {
    busy = true;
    _notify();
    try {
      final incoming = await listDownloads();
      final ids = {for (final media in incoming) media.id};
      _items.removeWhere((id, _) => !ids.contains(id));
      _tombstones.removeWhere(ids.contains);
      for (final media in incoming) {
        _items[media.id] = media;
      }
      initialized = true;
    } on ExtractionException catch (e) {
      error = e.message;
    } finally {
      busy = false;
      _notify();
    }
  }

  /// Validates the link locally before touching the native probe.
  /// The platform is detected from the link itself.
  Future<ProbeResult> probe(String link, {int height = 0}) async {
    final uri = sharedLink(link);
    final platformId = platformFor(uri)!;
    busy = true;
    error = null;
    _notify();
    try {
      return await probeLink(uri.toString(), platformId, height: height);
    } on ExtractionException catch (e) {
      error = e.message;
      rethrow;
    } finally {
      busy = false;
      _notify();
    }
  }

  Future<String?> enqueue(String link, ProbeResult probe, {int height = 0}) async {
    final uri = sharedLink(link);
    final platformId = platformFor(uri)!;
    if (probe.platform.isNotEmpty && probe.platform != platformId) {
      throw const ExtractionException('Tautan berubah. Periksa ulang sebelum mengunduh.');
    }
    busy = true;
    error = null;
    _notify();
    try {
      final id = await enqueueDownload(
        url: uri.toString(),
        id: probe.id,
        title: probe.title,
        platform: platformId,
        uploader: probe.uploader,
        durationMs: probe.durationMs,
        thumbnailUrl: probe.thumbnailUrl,
        approxBytes: probe.approxBytes,
        height: height,
      );
      await reload();
      return id;
    } on ExtractionException catch (e) {
      error = e.message;
      rethrow;
    } finally {
      busy = false;
      _notify();
    }
  }

  Future<void> cancel(String id) => cancelDownload(id);

  Future<void> pause(String id) => pauseDownload(id);

  Future<void> resume(String id) => resumeDownload(id);

  Future<void> refreshLink(String id, {String? url}) async {
    String? source;
    if (url != null) {
      final uri = sharedLink(url);
      final item = _items[id];
      if (item != null && platformFor(uri) != item.platform) {
        throw const ExtractionException('Tautan harus berasal dari platform video yang sama.');
      }
      source = uri.toString();
    }
    await refreshDownloadLink(id, url: source);
    await reload();
  }

  Future<void> delete(String id) async {
    try {
      await deleteDownload(id);
    } on ExtractionException {
      // File may already be gone; drop the entry regardless.
    }
    _items.remove(id);
    _tombstones.add(id);
    _notify();
  }

  @override
  void dispose() {
    _disposed = true;
    _notifyTimer?.cancel();
    _subscription?.cancel();
    super.dispose();
  }
}
