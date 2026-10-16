import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

class PlayerScreen extends StatefulWidget {
  const PlayerScreen({super.key, required this.title, required this.id});

  final String title;
  final String id;

  @override
  State<PlayerScreen> createState() => _PlayerScreenState();
}

class _PlayerScreenState extends State<PlayerScreen> with WidgetsBindingObserver {
  MethodChannel? _player;
  bool _fullscreen = false;
  bool _closing = false;
  bool? _mediaLandscape;
  Future<void> _chromeChange = Future<void>.value();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  void _created(int viewId) {
    if (!mounted || _closing) return;
    final channel = MethodChannel('com.nonton.yuks/player/$viewId');
    _player = channel;
    channel.setMethodCallHandler((call) async {
      if (!mounted || _closing) return;
      final args = call.arguments;
      if (args is! Map) return;
      if (call.method == 'fullscreen' && args['enabled'] is bool) {
        await _setFullscreen(args['enabled'] as bool);
      } else if (call.method == 'videoAspectRatio') {
        final ratio = args['aspectRatio'];
        if (ratio is! num || !ratio.isFinite || ratio <= 0) return;
        final landscape = ratio > 1;
        if (_mediaLandscape == landscape) return;
        _mediaLandscape = landscape;
        await _applyChrome();
      }
    });
    unawaited(_command('ready'));
    if (WidgetsBinding.instance.lifecycleState != AppLifecycleState.resumed) {
      unawaited(_command('pause'));
    }
  }

  Future<void> _command(String method, [Object? arguments]) async {
    try {
      await _player?.invokeMethod<Object?>(method, arguments);
    } on PlatformException {
      // Native disposal can finish before the platform-view route teardown.
    } on MissingPluginException {
      // The view may already have been removed by Android.
    }
  }

  Future<void> _setFullscreen(bool enabled) async {
    if (!mounted || _closing) return;
    setState(() => _fullscreen = enabled);
    await _command('setFullscreen', {'enabled': enabled});
    await _applyChrome();
  }

  Future<void> _applyChrome({bool restore = false}) {
    final fullscreen = _fullscreen;
    final landscape = _mediaLandscape;
    _chromeChange = _chromeChange.then((_) async {
      if (!restore && (!mounted || _closing)) return;
      try {
        await SystemChrome.setEnabledSystemUIMode(
          !restore && fullscreen
              ? SystemUiMode.immersiveSticky
              : SystemUiMode.edgeToEdge,
        );
        if (!restore && (!mounted || _closing)) return;
        if (restore || landscape != null) {
          await SystemChrome.setPreferredOrientations(restore
              ? []
              : landscape!
                  ? [DeviceOrientation.landscapeLeft, DeviceOrientation.landscapeRight]
                  : [DeviceOrientation.portraitUp]);
        }
      } on PlatformException {
        // Android may restrict orientation; back and playback remain usable.
      } on MissingPluginException {
        // No system chrome outside Android.
      }
    });
    return _chromeChange;
  }

  Future<void> _close() async {
    if (_closing) return;
    _closing = true;
    await _command('pause');
    await _applyChrome(restore: true);
    if (mounted) Navigator.pop(context);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) unawaited(_command('pause'));
  }

  @override
  void dispose() {
    _closing = true;
    WidgetsBinding.instance.removeObserver(this);
    _player?.setMethodCallHandler(null);
    unawaited(_command('dispose'));
    unawaited(_applyChrome(restore: true));
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: false,
    onPopInvokedWithResult: (didPop, _) async {
      if (didPop) return;
      if (_fullscreen) {
        await _setFullscreen(false);
      } else {
        await _close();
      }
    },
    child: Scaffold(
      backgroundColor: Colors.black,
      appBar: _fullscreen ? null : AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        leading: IconButton(
          tooltip: 'Kembali ke koleksi',
          icon: const Icon(Icons.arrow_back),
          onPressed: () => unawaited(_close()),
        ),
        title: Text(widget.title, maxLines: 1, overflow: TextOverflow.ellipsis),
      ),
      body: SafeArea(
        top: false,
        child: defaultTargetPlatform == TargetPlatform.android
            ? PlatformViewLink(
                viewType: 'com.nonton.yuks/player',
                surfaceFactory: (context, controller) => AndroidViewSurface(
                  controller: controller as AndroidViewController,
                  gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
                  hitTestBehavior: PlatformViewHitTestBehavior.opaque,
                ),
                onCreatePlatformView: (params) {
                  final controller = PlatformViewsService.initSurfaceAndroidView(
                    id: params.id,
                    viewType: 'com.nonton.yuks/player',
                    layoutDirection: TextDirection.ltr,
                    creationParams: {'id': widget.id},
                    creationParamsCodec: const StandardMessageCodec(),
                    onFocus: () => params.onFocusChanged(true),
                  );
                  controller.addOnPlatformViewCreatedListener(params.onPlatformViewCreated);
                  controller.addOnPlatformViewCreatedListener(_created);
                  unawaited(controller.create());
                  return controller;
                },
              )
            : const Center(
                child: Padding(
                  padding: EdgeInsets.all(24),
                  child: Text('Pemutar tersedia di Android. Kembali ke koleksi untuk memilih video lain.',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Colors.white)),
                ),
              ),
      ),
    ),
  );
}
