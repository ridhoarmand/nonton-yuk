import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nonton_yuks/download_controller.dart';
import 'package:nonton_yuks/main.dart';

const _platform = MethodChannel('com.nonton.yuks/platform');

/// Mocks the native side: settings/list/probe/takePendingShares.
class _Native {
  final settings = <Object?, Object?>{'themeMode': 'system'};
  List<Object?> list = const [];
  List<Object?> shares = const [];
  final probeCalls = <String>[];
  Completer<Map<Object?, Object?>>? probeGate;

  Future<void> install(WidgetTester tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_platform, (call) async {
          return switch (call.method) {
            'getSettings' => Map<Object?, Object?>.from(settings),
            'setSettings' => null,
            'listDownloads' => List<Object?>.from(list),
            'takePendingShares' => List<String>.from(shares.map((s) => s.toString())),
            'probe' => () {
              final args = call.arguments as Map;
              final probed = args['url'].toString();
              probeCalls.add(probed);
              if (probeGate != null) return probeGate!.future;
              final facebook = probed.contains('facebook.com') || probed.contains('fb.watch');
              return <Object?, Object?>{
                'id': facebook ? 'facebook_1195289147628387' : 'youtube_jNQXAC9IVRw',
                'platform': facebook ? 'facebook' : 'youtube',
                'title': 'Pratinjau',
                'durationMs': 19000,
                'approxBytes': 0,
                'height': 240,
              };
            }(),
            'cancel' => null,
            'delete' => null,
            _ => null,
          };
        });
    addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_platform, null));
  }
}

Future<void> _pumpApp(WidgetTester tester) async {
  await tester.pumpWidget(const NontonYuksApp());
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 100));
}

void main() {
  testWidgets('tanpa pilihan tema tetap terang meski sistem gelap', (tester) async {
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
    addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
    final native = _Native();
    native.settings.remove('themeMode');
    await native.install(tester);
    await _pumpApp(tester);
    await tester.pumpAndSettle();

    final context = tester.element(find.byType(CollectionScreen));
    expect(Theme.of(context).brightness, Brightness.light);
  });

  for (final mode in ['light', 'dark']) {
    testWidgets('header dapat diakses pada 320dp dan teks 200% ($mode)', (tester) async {
      tester.view.physicalSize = const Size(320, 800);
      tester.view.devicePixelRatio = 1;
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
        tester.platformDispatcher.clearTextScaleFactorTestValue();
      });
      final native = _Native();
      native.settings['themeMode'] = mode;
      await native.install(tester);
      await _pumpApp(tester);
      await tester.pumpAndSettle();

      final settings = find.byTooltip('Pengaturan');
      final add = find.widgetWithText(FilledButton, 'Tambah video');
      expect(settings.hitTestable(), findsOneWidget);
      expect(add.hitTestable(), findsOneWidget);
      final settingsRect = tester.getRect(settings);
      final addRect = tester.getRect(add);
      const viewport = Rect.fromLTWH(0, 0, 320, 800);
      for (final rect in [settingsRect, addRect]) {
        expect(rect.intersect(viewport), rect);
        expect(rect.width, greaterThanOrEqualTo(48));
        expect(rect.height, greaterThanOrEqualTo(48));
      }
      expect(settingsRect.overlaps(addRect), isFalse);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('startup offline dengan API kosong tetap membuka pustaka lokal', (tester) async {
    final native = _Native();
    native.list = [
      <Object?, Object?>{
        'id': 'youtube_jNQXAC9IVRw',
        'title': 'Video lama',
        'platform': 'youtube',
        'state': 'done',
        'path': '/v.mp4',
        'bytes': 100,
        'createdAtMs': 7,
      },
    ];
    await native.install(tester);
    await _pumpApp(tester);

    expect(find.text('Video lama'), findsOneWidget);
    expect(find.text('Koleksi'), findsOneWidget);
    expect(native.probeCalls, isEmpty);
  });

  testWidgets('share membuka tepat satu sheet tanpa probe otomatis', (tester) async {
    final native = _Native();
    native.shares = ['Tonton https://www.youtube.com/watch?v=jNQXAC9IVRw seru!'];
    await native.install(tester);
    await _pumpApp(tester);
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.byType(AddVideoSheet), findsOneWidget);
    expect(find.textContaining('youtube.com/watch'), findsOneWidget);
    expect(native.probeCalls, isEmpty);
  });

  testWidgets('jawaban probe basi tidak menimpa tautan baru', (tester) async {
    final native = _Native();
    native.probeGate = Completer<Map<Object?, Object?>>();
    await native.install(tester);
    await _pumpApp(tester);

    await tester.tap(find.text('Tambah video'));
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byType(TextField), 'https://www.youtube.com/watch?v=jNQXAC9IVRw');
    await tester.tap(find.text('Periksa'));
    await tester.pump();
    expect(native.probeCalls, hasLength(1));

    // Tautan diganti saat probe pertama masih jalan.
    await tester.enterText(
        find.byType(TextField), 'https://www.youtube.com/watch?v=aqz-KE-bpKQ0');
    await tester.pump();
    native.probeGate!.complete(<Object?, Object?>{
      'id': 'youtube_jNQXAC9IVRw',
      'platform': 'youtube',
      'title': 'BASI',
      'durationMs': 1,
      'approxBytes': 0,
      'height': 0,
    });
    await tester.pump();
    expect(find.text('BASI'), findsNothing);
  });

  testWidgets('sheet manual menerima youtube lalu facebook tanpa picker', (tester) async {
    final native = _Native();
    await native.install(tester);
    await _pumpApp(tester);

    await tester.tap(find.text('Tambah video'));
    await tester.pumpAndSettle();
    expect(find.byType(SegmentedButton<String>), findsNothing);

    await tester.enterText(
        find.byType(TextField), 'https://www.youtube.com/watch?v=jNQXAC9IVRw');
    await tester.tap(find.text('Periksa'));
    await tester.pumpAndSettle();
    expect(find.text('Pratinjau'), findsOneWidget);
    expect(find.textContaining('YouTube ·'), findsOneWidget);

    await tester.enterText(
        find.byType(TextField), 'https://www.facebook.com/reel/1195289147628387');
    await tester.pump();
    expect(find.text('Pratinjau'), findsNothing);
    expect(find.widgetWithText(FilledButton, 'Unduh'), findsNothing);
    await tester.tap(find.text('Periksa'));
    await tester.pumpAndSettle();
    expect(find.text('Pratinjau'), findsOneWidget);
    expect(find.textContaining('Facebook ·'), findsOneWidget);
    expect(native.probeCalls, hasLength(2));
  });

  testWidgets('pengaturan menyimpan tema tanpa meminta url atau token', (tester) async {
    final native = _Native();
    await native.install(tester);
    await _pumpApp(tester);

    await tester.tap(find.byTooltip('Pengaturan'));
    await tester.pumpAndSettle();
    expect(find.text('URL backend'), findsNothing);
    expect(find.text('Token backend'), findsNothing);
    await tester.tap(find.text('Gelap'));
    await tester.tap(find.text('Simpan'));
    await tester.pumpAndSettle();

    final app = tester.widget<MaterialApp>(find.byType(MaterialApp));
    expect(app.themeMode, ThemeMode.dark);
  });

  testWidgets('tema tersimpan diterapkan tanpa restart', (tester) async {
    final native = _Native();
    native.settings['themeMode'] = 'dark';
    await native.install(tester);
    await _pumpApp(tester);

    final app = tester.widget<MaterialApp>(find.byType(MaterialApp));
    expect(app.themeMode, ThemeMode.dark);
  });

  test('event terminal tidak membangkitkan item yang dihapus', () async {
    final events = StreamController<Object?>();
    final controller = DownloadController(progressEvents: events.stream);
    addTearDown(() async {
      await events.close();
      controller.dispose();
    });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_platform, (call) async => null);
    addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_platform, null));

    events.add(<Object?, Object?>{'id': 'v1', 'state': 'running', 'progress': 0.5});
    await Future<void>.delayed(Duration.zero);
    expect(controller.items.single.id, 'v1');

    await controller.delete('v1');
    expect(controller.items, isEmpty);
    events.add(<Object?, Object?>{'id': 'v1', 'state': 'running', 'progress': 0.7});
    await Future<void>.delayed(Duration.zero);
    expect(controller.items, isEmpty);
  });
}
