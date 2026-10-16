import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nonton_yuks/bridge.dart';
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
  bool deferProbes = false;
  final probeRequests = <Map<Object?, Object?>>[];
  final probeReplies = <Completer<Map<Object?, Object?>>>[];
  final enqueueCalls = <Map<Object?, Object?>>[];
  final refreshCalls = <Map<Object?, Object?>>[];
  String network = 'wifi';
  int networkCalls = 0;
  PlatformException? refreshFailure;

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
              probeRequests.add(Map<Object?, Object?>.from(args));
              if (deferProbes) {
                final reply = Completer<Map<Object?, Object?>>();
                probeReplies.add(reply);
                return reply.future;
              }
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
            'networkType' => () {
              networkCalls++;
              return network;
            }(),
            'enqueue' => () {
              final args = Map<Object?, Object?>.from(call.arguments as Map);
              enqueueCalls.add(args);
              return args['id'];
            }(),
            'refreshLink' => () {
              refreshCalls.add(Map<Object?, Object?>.from(call.arguments as Map));
              if (refreshFailure != null) throw refreshFailure!;
              return null;
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

const _source = 'https://www.youtube.com/watch?v=jNQXAC9IVRw';

Map<Object?, Object?> _quality(int height, {int? bytes, String id = 'youtube_jNQXAC9IVRw'}) => {
  'id': id,
  'platform': 'youtube',
  'title': 'Video kualitas',
  'height': height,
  'heights': [1080, 360],
  'approxBytes': bytes,
};

Future<void> _openQuality(WidgetTester tester, _Native native, {int height = 1080}) async {
  native.deferProbes = true;
  await native.install(tester);
  await _pumpApp(tester);
  await tester.tap(find.text('Tambah video'));
  await tester.pumpAndSettle();
  await tester.enterText(find.byType(TextField), _source);
  await tester.tap(find.text('Periksa'));
  await tester.pump();
  native.probeReplies.single.complete(_quality(height, bytes: height == 1080 ? 9437184 : 2097152));
  await tester.pumpAndSettle();
}

Finder _downloadButton() => find.byWidgetPredicate(
  (widget) => widget is FilledButton && widget.child is Text &&
      (widget.child! as Text).data!.startsWith('Unduh'),
);

Map<Object?, Object?> _partial(String state, {String? phase, bool source = true}) => {
  'id': 'youtube_jNQXAC9IVRw',
  'platform': 'youtube',
  'title': 'Cache sebagian',
  'state': state,
  'phase': phase,
  if (source) 'sourceUrl': _source,
  'playable': true,
  'resumable': true,
  'bytes': 2048,
  'approxBytes': 4096,
  'progress': 0.5,
};

Future<void> _openRefresh(WidgetTester tester, _Native native, {String state = 'failed'}) async {
  native.list = [_partial(state)];
  await native.install(tester);
  await _pumpApp(tester);
  await tester.ensureVisible(find.text('Refresh link'));
  await tester.tap(find.text('Refresh link'));
  await tester.pumpAndSettle();
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

  for (final state in ['running', 'paused', 'interrupted', 'failed']) {
    testWidgets('cache $state dapat ditonton tanpa path atau enqueue baru', (tester) async {
      final native = _Native();
      native.list = [
        <Object?, Object?>{
          'id': 'cached',
          'title': 'Video tersimpan sebagian',
          'state': state,
          'playable': true,
          'resumable': state != 'running',
          'bytes': 2048,
          'approxBytes': 4096,
          'progress': 0.5,
        },
      ];
      await native.install(tester);
      await _pumpApp(tester);

      expect(find.text('Tonton sekarang'), findsOneWidget);
      expect(find.byType(AddVideoSheet), findsNothing);
      expect(native.probeCalls, isEmpty);
      if (state == 'interrupted' || state == 'failed') {
        expect(find.text('Lanjutkan'), findsOneWidget);
        expect(find.text('Coba lagi'), findsNothing);
      } else if (state == 'paused') {
        expect(find.byTooltip('Lanjutkan unduhan'), findsOneWidget);
      }
      expect(tester.takeException(), isNull);
    });
  }

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

  testWidgets('kualitas baru memeriksa ukuran lalu enqueue descriptor yang sesuai', (tester) async {
    final native = _Native();
    await _openQuality(tester, native);
    expect(find.text('Unduh · 9 MB'), findsOneWidget);

    await tester.tap(find.text('360p'));
    await tester.pump();
    expect(native.probeRequests.last['height'], 360);
    expect(find.text('Memeriksa ukuran…'), findsOneWidget);
    expect(find.textContaining('9 MB'), findsNothing);
    expect(tester.widget<FilledButton>(_downloadButton()).onPressed, isNull);

    native.probeReplies.last.complete(_quality(360, bytes: 2097152));
    await tester.pumpAndSettle();
    expect(find.text('Unduh · 2 MB'), findsOneWidget);
    expect(find.textContaining('sekitar 2 MB'), findsOneWidget);
    final submit = tester.widget<FilledButton>(_downloadButton()).onPressed!;
    submit();
    submit();
    await tester.pumpAndSettle();
    expect(native.enqueueCalls, hasLength(1));
    expect(native.enqueueCalls.single['height'], 360);
    expect(native.enqueueCalls.single['approxBytes'], 2097152);
    expect(native.enqueueCalls.single['url'], _source);
  });

  testWidgets('probe awal memilih tinggi aktual bukan opsi pertama', (tester) async {
    final native = _Native();
    await _openQuality(tester, native, height: 360);
    expect(tester.widget<ChoiceChip>(find.widgetWithText(ChoiceChip, '360p')).selected, isTrue);
    await tester.tap(_downloadButton());
    await tester.pumpAndSettle();
    expect(native.enqueueCalls.single['height'], 360);
  });

  testWidgets('balasan kualitas 1080p terlambat tidak mengganti 360p', (tester) async {
    final native = _Native();
    await _openQuality(tester, native);
    await tester.tap(find.text('360p'));
    await tester.pump();
    await tester.tap(find.text('1080p'));
    await tester.pump();
    final late1080 = native.probeReplies.last;
    await tester.tap(find.text('360p'));
    await tester.pump();
    native.probeReplies.last.complete(_quality(360, bytes: 2097152));
    await tester.pumpAndSettle();
    late1080.complete(_quality(1080, bytes: 9437184));
    native.probeReplies[1].complete(_quality(360, bytes: 3145728));
    await tester.pumpAndSettle();
    expect(find.text('Unduh · 2 MB'), findsOneWidget);
    expect(find.textContaining('9 MB'), findsNothing);
    expect(tester.widget<ChoiceChip>(find.widgetWithText(ChoiceChip, '360p')).selected, isTrue);
  });

  testWidgets('error kualitas menonaktifkan unduh lama lalu ukuran unknown tidak dikarang', (tester) async {
    final native = _Native();
    await _openQuality(tester, native);
    await tester.tap(find.text('360p'));
    await tester.pump();
    native.probeReplies.last.completeError(
      PlatformException(code: 'qualityUnavailable', message: 'Kualitas tidak tersedia.'),
    );
    await tester.pumpAndSettle();
    expect(find.text('Kualitas tidak tersedia.'), findsOneWidget);
    expect(tester.widget<FilledButton>(_downloadButton()).onPressed, isNull);
    expect(find.textContaining('9 MB'), findsNothing);

    await tester.tap(find.text('1080p'));
    await tester.pump();
    native.probeReplies.last.complete(_quality(1080));
    await tester.pumpAndSettle();
    expect(find.text('Ukuran belum diketahui'), findsOneWidget);
    expect(find.text('Unduh'), findsOneWidget);
    expect(tester.widget<FilledButton>(_downloadButton()).onPressed, isNotNull);
  });

  testWidgets('descriptor video berbeda tidak mengaktifkan kualitas terpilih', (tester) async {
    final native = _Native();
    await _openQuality(tester, native);
    await tester.tap(find.text('360p'));
    await tester.pump();
    native.probeReplies.last.complete(_quality(360, bytes: 2097152, id: 'youtube_aqz-KE-bpKQ0'));
    await tester.pumpAndSettle();
    expect(find.text('Video kualitas'), findsOneWidget);
    expect(tester.widget<FilledButton>(_downloadButton()).onPressed, isNull);
    expect(find.textContaining('2 MB'), findsNothing);
    expect(native.enqueueCalls, isEmpty);
  });

  testWidgets('perubahan link selama konfirmasi seluler membatalkan snapshot enqueue', (tester) async {
    final native = _Native()..network = 'cellular';
    await _openQuality(tester, native);
    await tester.tap(_downloadButton());
    await tester.pumpAndSettle();
    expect(find.text('Paket data seluler'), findsOneWidget);
    final field = tester.widget<TextField>(find.byType(TextField));
    field.controller!.text = 'https://youtu.be/aqz-KE-bpKQ0';
    field.onChanged!(field.controller!.text);
    await tester.tap(find.text('Tetap unduh'));
    await tester.pumpAndSettle();
    expect(native.enqueueCalls, isEmpty);
    expect(find.byType(AddVideoSheet), findsOneWidget);
  });

  for (final state in ['failed', 'interrupted', 'paused']) {
    testWidgets('refresh $state mempertahankan aksi lama dan batal tanpa jaringan', (tester) async {
      final native = _Native()..list = [_partial(state)];
      await native.install(tester);
      await _pumpApp(tester);
      expect(find.text('Tonton sekarang'), findsOneWidget);
      expect(state == 'paused' ? find.byTooltip('Lanjutkan unduhan') : find.text('Lanjutkan'),
        findsOneWidget);
      expect(state == 'paused' ? find.byTooltip('Batalkan unduhan') : find.byTooltip('Hapus unduhan'),
        findsOneWidget);
      await tester.ensureVisible(find.text('Refresh link'));
      await tester.tap(find.text('Refresh link'));
      await tester.pumpAndSettle();
      expect(find.byType(RefreshLinkSheet), findsOneWidget);
      expect(find.textContaining('Cache tetap disimpan'), findsOneWidget);
      expect(find.textContaining('hingga 2 KB'), findsOneWidget);
      expect(find.text('Refresh dari link tersimpan'), findsOneWidget);
      await tester.tap(find.text('Batal'));
      await tester.pumpAndSettle();
      expect(find.byType(RefreshLinkSheet), findsNothing);
      expect(native.refreshCalls, isEmpty);
      expect(native.probeCalls, isEmpty);
      expect(native.networkCalls, 0);
    });
  }

  testWidgets('refresh hanya tersedia untuk item terhenti dengan sumber valid', (tester) async {
    final native = _Native()..list = [
      _partial('failed', source: false),
      {..._partial('failed'), 'id': 'invalid', 'sourceUrl': 'https://example.com/video'},
      {..._partial('running'), 'id': 'running'},
      {..._partial('queued'), 'id': 'queued'},
      {..._partial('canceled'), 'id': 'canceled'},
      {..._partial('done'), 'id': 'done', 'path': '/done.mp4'},
    ];
    await native.install(tester);
    await _pumpApp(tester);
    expect(find.text('Refresh link'), findsNothing);
  });

  testWidgets('refresh tersimpan meminta izin seluler dan menutup sesudah diterima', (tester) async {
    final native = _Native()..network = 'cellular';
    await _openRefresh(tester, native);
    await tester.tap(find.text('Refresh dari link tersimpan'));
    await tester.pumpAndSettle();
    expect(find.text('Paket data seluler'), findsOneWidget);
    expect(native.refreshCalls, isEmpty);
    await tester.tap(find.text('Batal').last);
    await tester.pumpAndSettle();
    expect(find.byType(RefreshLinkSheet), findsOneWidget);
    expect(native.refreshCalls, isEmpty);
    await tester.tap(find.text('Refresh dari link tersimpan'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Tetap unduh'));
    await tester.pumpAndSettle();
    expect(native.refreshCalls, hasLength(1));
    expect(native.refreshCalls.single['id'], 'youtube_jNQXAC9IVRw');
    expect(native.refreshCalls.single.containsKey('url'), isFalse);
    expect(find.byType(RefreshLinkSheet), findsNothing);
  });

  testWidgets('link baru menolak kosong dan platform salah sebelum konfirmasi seluler', (tester) async {
    final native = _Native()..network = 'cellular';
    await _openRefresh(tester, native);
    await tester.tap(find.text('Gunakan link baru'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Verifikasi dan lanjutkan'));
    await tester.pumpAndSettle();
    expect(find.text('Tempel tautan pengganti lebih dulu.'), findsOneWidget);
    await tester.enterText(find.byType(TextField), 'bukan tautan');
    await tester.tap(find.text('Verifikasi dan lanjutkan'));
    await tester.pumpAndSettle();
    expect(native.refreshCalls, isEmpty);
    expect(native.networkCalls, 0);
    await tester.enterText(find.byType(TextField), 'https://www.facebook.com/reel/1195289147628387');
    await tester.tap(find.text('Verifikasi dan lanjutkan'));
    await tester.pumpAndSettle();
    expect(find.text('Gunakan tautan dari platform yang sama.'), findsOneWidget);
    expect(native.networkCalls, 0);
    await tester.enterText(find.byType(TextField), 'https://youtu.be/jNQXAC9IVRw');
    await tester.tap(find.text('Verifikasi dan lanjutkan'));
    await tester.pumpAndSettle();
    expect(find.text('Paket data seluler'), findsOneWidget);
    expect(native.refreshCalls, isEmpty);
    await tester.tap(find.text('Tetap unduh'));
    await tester.pumpAndSettle();
    expect(native.refreshCalls.single['url'], 'https://youtu.be/jNQXAC9IVRw');
    expect(find.byType(RefreshLinkSheet), findsNothing);
  });

  testWidgets('penolakan pekerjaan refresh tampil inline tanpa menutup sheet', (tester) async {
    final native = _Native()
      ..refreshFailure = PlatformException(code: 'downloadStopping', message: 'Unduhan masih berhenti.');
    await _openRefresh(tester, native);
    await tester.tap(find.text('Refresh dari link tersimpan'));
    await tester.pumpAndSettle();
    expect(find.text('Unduhan masih berhenti.'), findsOneWidget);
    expect(find.byType(RefreshLinkSheet), findsOneWidget);
    expect(tester.widget<FilledButton>(
      find.widgetWithText(FilledButton, 'Refresh dari link tersimpan')).onPressed, isNotNull);
  });

  for (final state in ['queued', 'running']) {
    testWidgets('refresh $state memakai indikator tak tentu tanpa mengubah byte', (tester) async {
      final native = _Native()..list = [_partial(state, phase: 'refresh')];
      await native.install(tester);
      await _pumpApp(tester);
      expect(find.text(state == 'queued' ? 'Menunggu verifikasi link' : 'Memverifikasi link'),
        findsOneWidget);
      expect(find.textContaining('2 KB dari 4 KB'), findsOneWidget);
      expect(tester.widget<LinearProgressIndicator>(find.byType(LinearProgressIndicator)).value, isNull);
      expect(find.text('Tonton sekarang'), findsNothing);
      expect(find.text('Refresh link'), findsNothing);
      expect(find.byTooltip('Jeda unduhan'), findsOneWidget);
    });
  }

  testWidgets('refresh dijeda tetap dapat ditonton dan dilanjutkan', (tester) async {
    final native = _Native()..list = [_partial('paused', phase: 'refresh')];
    await native.install(tester);
    await _pumpApp(tester);
    expect(find.text('Dijeda'), findsOneWidget);
    expect(find.text('Tonton sekarang'), findsOneWidget);
    expect(find.byTooltip('Lanjutkan unduhan'), findsOneWidget);
    expect(find.text('Refresh link'), findsOneWidget);
  });

  for (final mode in ['light', 'dark']) {
    testWidgets('sheet kualitas dan refresh reflow 320dp teks 200% dengan keyboard ($mode)', (tester) async {
      tester.view.physicalSize = const Size(320, 800);
      tester.view.devicePixelRatio = 1;
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
        tester.view.resetViewInsets();
        tester.platformDispatcher.clearTextScaleFactorTestValue();
      });
      final native = _Native()..settings['themeMode'] = mode;
      await _openQuality(tester, native);
      await tester.ensureVisible(find.text('360p'));
      await tester.tap(find.text('360p'));
      await tester.pump();
      native.probeReplies.last.complete(_quality(360, bytes: 2097152));
      await tester.pumpAndSettle();
      await tester.ensureVisible(_downloadButton());
      expect(_downloadButton().hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.ensureVisible(find.byTooltip('Tutup'));
      await tester.tap(find.byTooltip('Tutup'));
      await tester.pumpAndSettle();

      final controller = DownloadController(progressEvents: const Stream<Object?>.empty());
      addTearDown(controller.dispose);
      await tester.pumpWidget(MaterialApp(
        theme: tester.widget<MaterialApp>(find.byType(MaterialApp)).theme,
        darkTheme: tester.widget<MaterialApp>(find.byType(MaterialApp)).darkTheme,
        themeMode: mode == 'dark' ? ThemeMode.dark : ThemeMode.light,
        home: Scaffold(body: RefreshLinkSheet(
          controller: controller,
          item: SavedMedia.fromMap(_partial('paused'))!,
        )),
      ));
      await tester.ensureVisible(find.text('Gunakan link baru'));
      await tester.tap(find.text('Gunakan link baru'));
      await tester.pump();
      tester.view.viewInsets = const FakeViewPadding(bottom: 280);
      await tester.pump();
      await tester.ensureVisible(find.byType(TextField));
      await tester.enterText(find.byType(TextField), 'https://youtu.be/jNQXAC9IVRw');
      await tester.ensureVisible(find.text('Verifikasi dan lanjutkan'));
      expect(find.text('Verifikasi dan lanjutkan').hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

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
