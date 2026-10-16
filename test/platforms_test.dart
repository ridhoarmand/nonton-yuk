import 'package:flutter_test/flutter_test.dart';
import 'package:nonton_yuks/bridge.dart';
import 'package:nonton_yuks/platforms.dart';

void main() {
  group('platformFor / platformById', () {
    test('preselects platform by host', () {
      expect(platformFor(Uri.parse('https://m.facebook.com/x')), 'facebook');
      expect(platformFor(Uri.parse('https://youtu.be/abc')), 'youtube');
      expect(platformFor(Uri.parse('https://example.com/x')), isNull);
    });

    test('rejects lookalike host', () {
      expect(platformFor(Uri.parse('https://facebook.com.evil.test/watch?v=123')), isNull);
    });

    test('platformById resolves and is null otherwise', () {
      expect(platformById('youtube')!.label, 'YouTube');
      expect(platformById('nope'), isNull);
    });
  });

  group('sharedLink facebook', () {
    test('extracts from share text with trailing punctuation', () {
      expect(
        sharedLink('Tonton ini https://www.facebook.com/watch?v=1234567890, seru!').toString(),
        'https://www.facebook.com/watch?v=1234567890',
      );
    });

    test('collapses duplicates', () {
      expect(
        sharedLink('https://fb.watch/abc_DEF/ https://fb.watch/abc_DEF/').toString(),
        'https://fb.watch/abc_DEF',
      );
    });

    test('normalises scheme and port', () {
      expect(
        sharedLink('http://www.facebook.com/reel/12345').toString(),
        'https://www.facebook.com/reel/12345',
      );
    });

    test('accepts every supported facebook shape', () {
      for (final link in [
        'https://www.facebook.com/watch?v=123',
        'https://m.facebook.com/reel/456',
        'https://web.facebook.com/somepage/videos/789',
        'https://facebook.com/share/r/AbC-123',
        'https://facebook.com/share/v/AbC-123',
        'https://fb.watch/xyz_9',
      ]) {
        expect(sharedLink(link), isNotNull, reason: link);
      }
    });

    test('rejects more than one link', () {
      expect(
        () => sharedLink('https://fb.watch/a https://fb.watch/b'),
        throwsA(isA<ExtractionException>()),
      );
    });

    test('rejects no link', () {
      expect(() => sharedLink('tidak ada tautan di sini'), throwsA(isA<ExtractionException>()));
    });

    test('rejects lookalike host', () {
      expect(
        () => sharedLink('https://facebook.com.evil.test/watch?v=123'),
        throwsA(isA<ExtractionException>()),
      );
    });

    test('rejects userinfo and unusual port', () {
      expect(() => sharedLink('https://user@facebook.com/reel/1'), throwsA(isA<ExtractionException>()));
      expect(() => sharedLink('https://facebook.com:8443/reel/1'), throwsA(isA<ExtractionException>()));
    });

  });

  group('sharedLink youtube', () {
    test('accepts every supported youtube shape', () {
      for (final link in [
        'https://youtu.be/dQw4w9WgXcQ',
        'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
        'https://m.youtube.com/shorts/dQw4w9WgXcQ',
        'https://www.youtube.com/live/dQw4w9WgXcQ',
        'https://www.youtube.com/embed/dQw4w9WgXcQ',
        'https://music.youtube.com/watch?v=dQw4w9WgXcQ',
      ]) {
        expect(sharedLink(link), isNotNull, reason: link);
      }
    });

    test('strips fragment and trailing slash', () {
      expect(
        sharedLink('https://youtu.be/dQw4w9WgXcQ/#t=30').toString(),
        'https://youtu.be/dQw4w9WgXcQ',
      );
    });

  });

  group('sharedLink auto-detect', () {
    test('accepts both platforms without a choice', () {
      expect(
        sharedLink('Tonton https://youtu.be/jNQXAC9IVRw seru!').toString(),
        'https://youtu.be/jNQXAC9IVRw',
      );
      expect(
        sharedLink('Tonton https://www.facebook.com/reel/1195289147628387 seru!').toString(),
        'https://www.facebook.com/reel/1195289147628387',
      );
    });

    test('rejects one youtube plus one facebook link', () {
      expect(
        () => sharedLink(
          'https://youtu.be/jNQXAC9IVRw https://www.facebook.com/reel/1195289147628387',
        ),
        throwsA(isA<ExtractionException>()),
      );
    });
  });

  group('formatBytes / formatDuration', () {
    test('bytes', () {
      expect(formatBytes(0), '');
      expect(formatBytes(312 * 1024 * 1024), '312 MB');
      expect(formatBytes(1536 * 1024 * 1024), '1,5 GB');
    });

    test('duration', () {
      expect(formatDuration(0), '0:00');
      expect(formatDuration(635000), '10:35');
      expect(formatDuration(3723000), '1:02:03');
    });
  });
}
