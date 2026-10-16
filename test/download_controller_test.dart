import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:nonton_yuks/bridge.dart';
import 'package:nonton_yuks/download_controller.dart';

void main() {
  test('merges progress events into items', () async {
    final events = StreamController<Object?>();
    final controller = DownloadController(progressEvents: events.stream);
    addTearDown(() async {
      await events.close();
      controller.dispose();
    });

    var notifications = 0;
    controller.addListener(() => notifications++);

    events.add(<Object?, Object?>{
      'id': 'v1',
      'platform': 'facebook',
      'title': 'Judul',
      'createdAtMs': 10,
      'state': 'queued',
      'progress': 0,
      'etaSeconds': 0,
      'phase': null,
      'bytes': 0,
    });
    await Future<void>.delayed(Duration.zero);
    expect(controller.items.single.title, 'Judul');
    expect(controller.items.single.state, TaskState.queued);

    events.add(<Object?, Object?>{
      'id': 'v1',
      'state': 'running',
      'progress': 0.5,
      'etaSeconds': 12,
      'phase': 'merge',
      'bytes': 1000,
    });
    await Future<void>.delayed(const Duration(milliseconds: 300));

    final item = controller.items.single;
    expect(item.state, TaskState.running);
    expect(item.phase, 'merge');
    expect(item.progress, 0.5);
    expect(item.bytes, 1000);
    expect(item.title, 'Judul'); // absent key preserved
    expect(notifications, greaterThanOrEqualTo(2));
  });

  test('orders items newest first', () async {
    final events = StreamController<Object?>();
    final controller = DownloadController(progressEvents: events.stream);
    addTearDown(() async {
      await events.close();
      controller.dispose();
    });

    events.add(<Object?, Object?>{'id': 'old', 'createdAtMs': 1});
    events.add(<Object?, Object?>{'id': 'new', 'createdAtMs': 2});
    await Future<void>.delayed(Duration.zero);

    expect(controller.items.map((m) => m.id), ['new', 'old']);
  });

  test('ignores malformed events', () async {
    final events = StreamController<Object?>();
    final controller = DownloadController(progressEvents: events.stream);
    addTearDown(() async {
      await events.close();
      controller.dispose();
    });

    events.add('nope');
    events.add(<Object?, Object?>{'state': 'running'});
    await Future<void>.delayed(Duration.zero);

    expect(controller.items, isEmpty);
  });

  test('throttles notifications', () async {
    final events = StreamController<Object?>();
    final controller = DownloadController(progressEvents: events.stream);
    addTearDown(() async {
      await events.close();
      controller.dispose();
    });

    var notifications = 0;
    controller.addListener(() => notifications++);

    for (var i = 0; i < 10; i++) {
      events.add(<Object?, Object?>{
        'id': 'a',
        'state': 'running',
        'progress': i / 10,
        'etaSeconds': 0,
        'phase': 'download',
        'bytes': i,
      });
      await Future<void>.delayed(Duration.zero);
    }
    await Future<void>.delayed(const Duration(milliseconds: 300));

    expect(notifications, greaterThan(0));
    expect(notifications, lessThan(10));
    expect(controller.items.single.progress, closeTo(0.9, 0.001));
  });

  test('probe rejects an invalid link before touching the channel', () {
    final controller = DownloadController(progressEvents: const Stream<Object?>.empty());
    expect(controller.probe('tanpa tautan'), throwsA(isA<ExtractionException>()));
    controller.dispose();
  });

  test('refresh rejects empty and cross-platform replacement links locally', () async {
    final events = StreamController<Object?>();
    final controller = DownloadController(progressEvents: events.stream);
    addTearDown(() async {
      await events.close();
      controller.dispose();
    });
    events.add({'id': 'cached', 'platform': 'youtube', 'state': 'paused'});
    await Future<void>.delayed(Duration.zero);

    await expectLater(controller.refreshLink('cached', url: ''),
        throwsA(isA<ExtractionException>()));
    await expectLater(
        controller.refreshLink('cached', url: 'https://facebook.com/watch?v=123456789'),
        throwsA(isA<ExtractionException>()));
    expect(controller.items.single.state, TaskState.paused);
  });
}
