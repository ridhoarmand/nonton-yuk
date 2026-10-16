import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nonton_yuks/bridge.dart';
import 'package:nonton_yuks/download_controller.dart';

void main() {
  test('probe heights parse and reject non-positive entries', () {
    final ok = ProbeResult.fromMap(<Object?, Object?>{
      'id': 'youtube_abc',
      'platform': 'youtube',
      'title': 'T',
      'height': 720,
      'heights': [1080, 720, 360],
    });
    expect(ok.heights, [1080, 720, 360]);

    expect(
      () => ProbeResult.fromMap(<Object?, Object?>{
        'id': 'youtube_abc',
        'platform': 'youtube',
        'title': 'T',
        'heights': [720, 0],
      }),
      throwsA(isA<ExtractionException>()),
    );
  });

  test('paused entries stay active with a dijeda label', () {
    final media = SavedMedia.fromMap(<Object?, Object?>{
      'id': 'a',
      'state': 'paused',
      'restarted': true,
      'bytes': 3 * 1024 * 1024,
    })!;
    expect(media.paused, isTrue);
    expect(media.active, isTrue);
    expect(media.statusLabel, 'Dijeda');
    expect(media.restarted, isTrue);
  });

  test('pause and resume hit the platform channel', () async {
    TestWidgetsFlutterBinding.ensureInitialized();
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(platformChannel, (call) async {
      calls.add(call);
      return null;
    });
    addTearDown(() => TestDefaultBinaryMessengerBinding.instance
        .defaultBinaryMessenger
        .setMockMethodCallHandler(platformChannel, null));

    final controller = DownloadController(
        progressEvents: const Stream<Object?>.empty());
    addTearDown(controller.dispose);

    await controller.pause('v1');
    await controller.resume('v1');
    expect(calls.map((c) => c.method), ['pause', 'resume']);
    expect(calls.first.arguments, {'id': 'v1'});
  });
}
