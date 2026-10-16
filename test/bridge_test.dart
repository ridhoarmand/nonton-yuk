import 'package:flutter_test/flutter_test.dart';
import 'package:nonton_yuks/bridge.dart';

void main() {
  test('parses a full entry', () {
    final media = SavedMedia.fromMap(<Object?, Object?>{
      'id': 'a',
      'title': 'Judul',
      'platform': 'facebook',
      'uploader': 'Kreator',
      'durationMs': 1234,
      'thumbnailUrl': 'https://x/y.jpg',
      'thumbPath': '/t.jpg',
      'path': '/v.mp4',
      'bytes': 2048,
      'approxBytes': 4096,
      'state': 'done',
      'progress': 1.0,
      'etaSeconds': 0,
      'phase': 'merge',
      'sourceUrl': 'https://facebook.com/watch?v=1',
      'createdAtMs': 99,
    })!;
    expect(media.title, 'Judul');
    expect(media.state, TaskState.done);
    expect(media.path, '/v.mp4');
    expect(media.ready, isTrue);
    expect(media.createdAtMs, 99);
  });

  test('missing optional keys are coerced, not thrown', () {
    final media = SavedMedia.fromMap(<Object?, Object?>{'id': 'a'})!;
    expect(media.title, '');
    expect(media.uploader, isNull);
    expect(media.bytes, 0);
    expect(media.progress, 0);
    expect(media.state, TaskState.queued);
    expect(media.createdAtMs, 0);
  });

  test('malformed entries return null', () {
    expect(SavedMedia.fromMap('nope'), isNull);
    expect(SavedMedia.fromMap(<Object?, Object?>{}), isNull);
    expect(SavedMedia.fromMap(<Object?, Object?>{'id': ''}), isNull);
    expect(SavedMedia.fromMap(<Object?, Object?>{'id': 7}), isNull);
  });

  test('list parsing skips malformed entries', () {
    final list = parseDownloadList(<Object?>[
      <Object?, Object?>{'id': 'a'},
      'bad',
      <Object?, Object?>{'no': 'id'},
      <Object?, Object?>{'id': 'b'},
    ]);
    expect(list.map((m) => m.id), ['a', 'b']);
  });

  test('list parsing tolerates a non-list payload', () {
    expect(parseDownloadList(null), isEmpty);
    expect(parseDownloadList('x'), isEmpty);
  });

  test('state maps to TaskState', () {
    for (final state in TaskState.values) {
      expect(SavedMedia.fromMap({'id': 'a', 'state': state.name})!.state, state);
    }
    expect(SavedMedia.fromMap({'id': 'a', 'state': 'weird'})!.state, TaskState.queued);
  });

  test('wrong value types never throw', () {
    final media = SavedMedia.fromMap(<Object?, Object?>{
      'id': 'a',
      'bytes': '12',
      'progress': 'x',
      'state': 5,
      'title': 42,
    })!;
    expect(media.bytes, 0);
    expect(media.progress, 0);
    expect(media.state, TaskState.queued);
    expect(media.title, '');
  });

  test('applyEvent overlay keeps absent keys', () {
    final media = SavedMedia.fromMap({'id': 'a', 'title': 'Judul', 'uploader': 'Kreator'})!;
    media.applyEvent({'id': 'a', 'state': 'running', 'progress': 0.5});
    expect(media.title, 'Judul');
    expect(media.uploader, 'Kreator');
    expect(media.state, TaskState.running);
    expect(media.progress, 0.5);
  });

  test('ready, active and sizeLabel', () {
    expect(SavedMedia(id: 'a', state: TaskState.done, path: '/x').ready, isTrue);
    expect(SavedMedia(id: 'a', state: TaskState.done).ready, isFalse);
    expect(SavedMedia(id: 'a', state: TaskState.queued).active, isTrue);
    expect(SavedMedia(id: 'a', state: TaskState.running).active, isTrue);
    expect(SavedMedia(id: 'a', state: TaskState.done).active, isFalse);
    expect(SavedMedia(id: 'a', approxBytes: 1024).sizeLabel, '1 KB');
    expect(SavedMedia(id: 'a').sizeLabel, '');
    expect(SavedMedia(id: 'old', state: TaskState.done, path: '/old.mp4').canPlay, isTrue);
    expect(SavedMedia(id: 'empty', state: TaskState.done).canPlay, isFalse);
  });

  test('cached playback and resume capability survive partial progress events', () {
    final media = SavedMedia.fromMap({
      'id': 'cached',
      'state': 'running',
      'bytes': 4096,
      'playable': true,
      'resumable': true,
    })!;
    expect(media.ready, isFalse);
    expect(media.canPlay, isTrue);
    expect(media.resumable, isTrue);

    for (final state in ['paused', 'interrupted', 'failed']) {
      media.applyEvent({'state': state});
      expect(media.canPlay, isTrue, reason: state);
      expect(media.resumable, isTrue, reason: state);
      expect(media.bytes, 4096);
    }
    media.applyEvent({'playable': false, 'resumable': false});
    expect(media.canPlay, isFalse);
    expect(media.resumable, isFalse);
    expect(media.bytes, 4096);
  });

  test('capability flags are opt-in booleans and canceled tasks cannot play', () {
    for (final value in [null, 1, 'true', false]) {
      final media = SavedMedia.fromMap({
        'id': 'a',
        'playable': value,
        'resumable': value,
      })!;
      expect(media.canPlay, isFalse);
      expect(media.resumable, isFalse);
    }
    expect(SavedMedia(id: 'a', state: TaskState.canceled, playable: true).canPlay, isFalse);
  });
}
