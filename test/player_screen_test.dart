import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nonton_yuks/player_screen.dart';

class _NativePlayer {
  final calls = <MethodCall>[];
  final chrome = <MethodCall>[];
  final navigator = GlobalKey<NavigatorState>();
  MethodChannel? channel;
  double? earlyRatio;
  Completer<void>? chromeGate;

  List<Object?> get orientations => chrome
      .where((call) => call.method == 'SystemChrome.setPreferredOrientations')
      .map((call) => call.arguments)
      .toList();

  Future<void> install(WidgetTester tester) async {
    final messenger = tester.binding.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      chrome.add(call);
      if (call.method == 'SystemChrome.setEnabledSystemUIMode') {
        final gate = chromeGate;
        chromeGate = null;
        await gate?.future;
      }
      return null;
    });
    messenger.setMockMethodCallHandler(SystemChannels.platform_views, (call) async {
      if (call.method == 'create') {
        final id = (call.arguments as Map)['id'];
        channel = MethodChannel('com.nonton.yuks/player/$id');
        messenger.setMockMethodCallHandler(channel!, (call) async {
          calls.add(call);
          if (call.method == 'ready' && earlyRatio != null) {
            await event('videoAspectRatio', {'aspectRatio': earlyRatio});
          }
          return null;
        });
      }
      return null;
    });
    addTearDown(() async {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pumpAndSettle();
      if (channel != null) messenger.setMockMethodCallHandler(channel!, null);
      messenger.setMockMethodCallHandler(SystemChannels.platform_views, null);
      messenger.setMockMethodCallHandler(SystemChannels.platform, null);
    });
    await tester.pumpWidget(MaterialApp(
      navigatorKey: navigator,
      home: const Scaffold(body: Text('Koleksi uji')),
    ));
    navigator.currentState!.push(MaterialPageRoute<void>(
      builder: (_) => const PlayerScreen(title: 'Video uji', id: 'fixture'),
    ));
    await tester.pumpAndSettle();
    expect(channel, isNotNull);
  }

  Future<void> event(String method, Object? arguments) async {
    final reply = Completer<void>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
      channel!.name,
      const StandardMethodCodec().encodeMethodCall(MethodCall(method, arguments)),
      (data) {
        if (data != null) const StandardMethodCodec().decodeEnvelope(data);
        reply.complete();
      },
    );
    await reply.future;
  }
}

void main() {
  testWidgets('ready replays early portrait metadata before fullscreen', (tester) async {
    final native = _NativePlayer()..earlyRatio = 9 / 16;
    await native.install(tester);

    expect(native.calls.map((call) => call.method), contains('ready'));
    expect(native.orientations, [ ['DeviceOrientation.portraitUp'] ]);
    expect(find.byType(AppBar), findsOneWidget);
  });

  testWidgets('ratio selects media orientation and ignores invalid or duplicate values', (tester) async {
    final native = _NativePlayer();
    await native.install(tester);
    expect(native.orientations, isEmpty);

    for (final payload in <Object?>[
      null, 1, {'aspectRatio': '1.5'}, {'aspectRatio': true},
      {'aspectRatio': 0}, {'aspectRatio': -1},
      {'aspectRatio': double.nan}, {'aspectRatio': double.infinity},
    ]) {
      await native.event('videoAspectRatio', payload);
    }
    expect(native.orientations, isEmpty);

    await native.event('videoAspectRatio', {'aspectRatio': 16 / 9});
    expect(native.orientations, [
      ['DeviceOrientation.landscapeLeft', 'DeviceOrientation.landscapeRight'],
    ]);
    await native.event('videoAspectRatio', {'aspectRatio': 4 / 3});
    expect(native.orientations, hasLength(1));
    await native.event('videoAspectRatio', {'aspectRatio': 1});
    expect(native.orientations.last, ['DeviceOrientation.portraitUp']);
    await native.event('videoAspectRatio', {'aspectRatio': 9 / 16});
    expect(native.orientations, hasLength(2));
    expect(find.byType(AppBar), findsOneWidget);
  });

  testWidgets('fullscreen hides app bar, first back preserves portrait, second closes', (tester) async {
    final native = _NativePlayer()..earlyRatio = 9 / 16;
    await native.install(tester);
    await native.event('fullscreen', {'enabled': true});
    await tester.pumpAndSettle();

    expect(find.byType(AppBar), findsNothing);
    expect(native.chrome.any((call) => call.method == 'SystemChrome.setEnabledSystemUIMode' &&
        call.arguments == 'SystemUiMode.immersiveSticky'), isTrue);
    expect(native.orientations.last, ['DeviceOrientation.portraitUp']);
    expect(native.calls.lastWhere((call) => call.method == 'setFullscreen').arguments,
        {'enabled': true});

    await native.navigator.currentState!.maybePop();
    await tester.pumpAndSettle();
    expect(find.byType(PlayerScreen), findsOneWidget);
    expect(find.byType(AppBar), findsOneWidget);
    expect(native.orientations.last, ['DeviceOrientation.portraitUp']);
    expect(native.calls.lastWhere((call) => call.method == 'setFullscreen').arguments,
        {'enabled': false});

    await native.navigator.currentState!.maybePop();
    await tester.pumpAndSettle();
    expect(find.byType(PlayerScreen), findsNothing);
    expect(find.text('Koleksi uji'), findsOneWidget);
    expect(native.orientations.last, isEmpty);
    expect(native.chrome.lastWhere((call) => call.method == 'SystemChrome.setEnabledSystemUIMode')
        .arguments, 'SystemUiMode.edgeToEdge');
    await native.event('videoAspectRatio', {'aspectRatio': 16 / 9});
    expect(native.orientations.last, isEmpty);
  });

  testWidgets('close discards queued rotations and late native events', (tester) async {
    final native = _NativePlayer();
    await native.install(tester);
    final gate = Completer<void>();
    native.chromeGate = gate;
    final first = native.event('videoAspectRatio', {'aspectRatio': 16 / 9});
    await tester.pump();
    final queued = native.event('videoAspectRatio', {'aspectRatio': 9 / 16});
    await tester.pump();
    await tester.tap(find.byTooltip('Kembali ke koleksi'));
    await tester.pump();
    await native.event('videoAspectRatio', {'aspectRatio': 2});
    gate.complete();
    await first;
    await queued;
    await tester.pumpAndSettle();

    expect(find.byType(PlayerScreen), findsNothing);
    expect(native.orientations, isNotEmpty);
    expect(native.orientations.every((value) => (value as List).isEmpty), isTrue);
  });

  testWidgets('disposing during chrome update restores unlocked collection', (tester) async {
    final native = _NativePlayer();
    await native.install(tester);
    final gate = Completer<void>();
    native.chromeGate = gate;
    final pending = native.event('videoAspectRatio', {'aspectRatio': 16 / 9});
    await tester.pump();
    await tester.pumpWidget(const MaterialApp(home: Text('Koleksi pengganti')));
    gate.complete();
    await pending;
    await tester.pumpAndSettle();

    expect(native.orientations.last, isEmpty);
    expect(native.orientations.every((value) => (value as List).isEmpty), isTrue);
    expect(native.calls.map((call) => call.method), contains('dispose'));
    expect(find.text('Koleksi pengganti'), findsOneWidget);
  });
}
