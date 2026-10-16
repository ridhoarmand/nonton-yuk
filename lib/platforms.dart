import 'bridge.dart';

/// A launch platform the app can extract from.
class MediaPlatform {
  const MediaPlatform({required this.id, required this.label});

  final String id; // 'facebook' | 'youtube'
  final String label; // 'Facebook' | 'YouTube'
}

const platforms = <MediaPlatform>[
  MediaPlatform(id: 'facebook', label: 'Facebook'),
  MediaPlatform(id: 'youtube', label: 'YouTube'),
];

const _hosts = <String, List<String>>{
  'facebook': [
    'facebook.com',
    'www.facebook.com',
    'm.facebook.com',
    'web.facebook.com',
    'fb.watch',
    'www.fb.watch',
  ],
  'youtube': [
    'youtube.com',
    'www.youtube.com',
    'm.youtube.com',
    'music.youtube.com',
    'youtu.be',
    'www.youtu.be',
  ],
};

/// Platform a link belongs to, or null when no platform owns the host.
MediaPlatform? platformById(String id) {
  for (final platform in platforms) {
    if (platform.id == id) return platform;
  }
  return null;
}

/// Platform id for a link, or null when no platform owns the host.
String? platformFor(Uri uri) {
  final host = uri.host.toLowerCase();
  for (final platform in platforms) {
    if (_hosts[platform.id]!.contains(host)) return platform.id;
  }
  return null;
}

const _noLink = ExtractionException('Masukkan tepat satu tautan video yang ingin diunduh.');

final _urlPattern = RegExp(r'''https?://[^\s<>"']+''', caseSensitive: false);
final _trailing = RegExp(r'''[.,;!?)\]}'"]+$''');

/// Extracts exactly one normalised, valid link from arbitrary share text,
/// detecting the platform from the link itself.
/// Throws [ExtractionException] for none or more than one.
Uri sharedLink(String text) {
  final found = <String, Uri>{};
  for (final match in _urlPattern.allMatches(text)) {
    final raw = match.group(0)!.replaceFirst(_trailing, '');
    final uri = Uri.tryParse(raw);
    if (uri == null) continue;
    final platformId = platformFor(uri);
    if (platformId == null) continue;
    final normalised = _normalise(uri);
    if (normalised == null) continue;
    if (!_validShape(platformId, normalised)) continue;
    found[normalised.toString()] = normalised;
  }

  if (found.length != 1) throw _noLink;
  return found.values.single;
}

/// https, lower-case host, no fragment, no port. Null when unsafe.
Uri? _normalise(Uri uri) {
  if (uri.scheme != 'http' && uri.scheme != 'https') return null;
  if (uri.userInfo.isNotEmpty) return null;
  if (uri.hasPort && uri.port != 80 && uri.port != 443) return null;
  return Uri(
    scheme: 'https',
    host: uri.host.toLowerCase(),
    path: _trimSlash(uri.path),
    query: uri.hasQuery ? uri.query : null,
  );
}

bool _validShape(String platformId, Uri uri) =>
    platformId == 'facebook' ? _facebookShape(uri) : _youtubeShape(uri);

String _trimSlash(String path) =>
    path.length > 1 && path.endsWith('/') ? path.substring(0, path.length - 1) : path;

bool _facebookShape(Uri uri) {
  final path = _trimSlash(uri.path);
  if (uri.host == 'fb.watch' || uri.host == 'www.fb.watch') {
    return RegExp(r'^/[A-Za-z0-9_-]+$').hasMatch(path);
  }
  if (RegExp(r'^/share/[vr]/[A-Za-z0-9_-]+$').hasMatch(path)) return true;
  if (RegExp(r'^/reel/\d+$').hasMatch(path)) return true;
  if (RegExp(r'^/[^/]+/videos/(?:[^/]+/)?\d+$').hasMatch(path)) return true;
  const watchPaths = {'/watch', '/video.php', '/video/video.php'};
  if (watchPaths.contains(path)) {
    final ids = uri.queryParametersAll['v'];
    return ids != null && ids.length == 1 && RegExp(r'^\d+$').hasMatch(ids.single);
  }
  return false;
}

bool _youtubeShape(Uri uri) {
  final path = _trimSlash(uri.path);
  if (uri.host == 'youtu.be' || uri.host == 'www.youtu.be') {
    return path.length == 12 && _isVideoId(path.substring(1));
  }
  if (path == '/watch') {
    final id = uri.queryParameters['v'];
    return id != null && _isVideoId(id);
  }
  final match = RegExp(r'^/(shorts|live|embed)/([^/]+)$').firstMatch(path);
  return match != null && _isVideoId(match.group(2)!);
}

bool _isVideoId(String id) => RegExp(r'^[A-Za-z0-9_-]{11}$').hasMatch(id);
