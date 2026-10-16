import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:video_player/video_player.dart';

import 'bridge.dart';

const _speeds = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0];

class PlayerScreen extends StatefulWidget {
  const PlayerScreen({super.key, required this.title, required this.path});

  final String title;
  final String path;

  @override
  State<PlayerScreen> createState() => _PlayerScreenState();
}

class _PlayerScreenState extends State<PlayerScreen> with WidgetsBindingObserver {
  VideoPlayerController? controller;
  String? error;
  bool _controls = true;
  Timer? _hide;

  double _speed = 1;
  int _shownSecond = -1;
  bool _wasPlaying = false;
  bool _fullscreen = false;
  bool _session = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_open());
  }

  Future<void> _open() async {
    final file = File(widget.path);
    if (!await file.exists() || await file.length() == 0) {
      if (mounted) setState(() => error = _openFailed);
      return;
    }
    // Pemutar hanya membuka berkas privat yang sudah diverifikasi, tidak pernah URL jaringan.
    final next = VideoPlayerController.file(file);
    try {
      await next.initialize();
    } catch (_) {
      try {
        await next.dispose();
      } catch (_) {}
      if (mounted) setState(() => error = _openFailed);
      return;
    }
    if (!mounted) {
      try {
        await next.dispose();
      } catch (_) {}
      return;
    }
    setState(() => controller = next);
    // Label elapsed/total mengikuti posisi; scrub bar mengurus dirinya sendiri.
    next.addListener(_trackPosition);
    await _beginSession();
    await next.play();
    _scheduleHide();
  }

  static const _openFailed =
      'Berkas video ini tidak dapat dibuka. Kemungkinan unduhannya belum lengkap; '
      'hapus lalu unduh ulang dari tautan sumber.';

  // Layar tetap menyala selama memutar; tanpa itu video berhenti di tengah jalan.
  Future<void> _beginSession() async {
    if (_session || !mounted) return;
    try {
      await platformChannel.invokeMethod<Object?>('beginPlayerSession');
      _session = true;
    } on PlatformException {
      // Sesi tidak tersedia; pemutaran tetap jalan.
    } on MissingPluginException {
      // Bukan Android: pemutaran tetap jalan tanpa sesi jendela.
    }
  }

  Future<void> _endSession() async {
    if (!_session) return;
    _session = false;
    try {
      await platformChannel.invokeMethod<Object?>('endPlayerSession');
    } on PlatformException {
      // Sesi sudah berakhir di sisi native; tidak ada yang perlu dipulihkan.
    } on MissingPluginException {
      // Bukan Android: tidak ada sesi jendela.
    }
  }

  void _trackPosition() {
    final active = controller;
    if (active == null || !mounted) return;
    final value = active.value;
    if (value.hasError) {
      _hide?.cancel();
      setState(() => error = _openFailed);
      return;
    }
    final second = value.position.inSeconds;
    if (second != _shownSecond || value.isPlaying != _wasPlaying ||
        (value.isCompleted && !_controls)) {
      _shownSecond = second;
      _wasPlaying = value.isPlaying;
      setState(() {
        if (value.isCompleted) {
          _hide?.cancel();
          _controls = true;
        }
      });
    }
  }

  void _scheduleHide() {
    _hide?.cancel();
    // Navigasi aksesibilitas aktif: kontrol tetap tampil, tanpa sembunyi otomatis.
    if (MediaQuery.accessibleNavigationOf(context)) return;
    _hide = Timer(const Duration(milliseconds: 3500), () {
      if (!mounted) return;
      if (controller?.value.isPlaying ?? false) {
        setState(() => _controls = false);
      }
    });
  }

  void _reveal() {
    _hide?.cancel();
    if (!_controls && mounted) setState(() => _controls = true);
  }

  void _toggleControls() {
    setState(() {
      _controls = !_controls;
      if (_controls) _scheduleHide();
    });
  }

  Future<void> _togglePlay() async {
    final active = controller;
    if (active == null) return;
    if (active.value.isPlaying) {
      await active.pause();
      _reveal();
    } else {
      if (active.value.isCompleted) await active.seekTo(Duration.zero);
      await active.play();
      _scheduleHide();
    }
    if (mounted) setState(() {});
  }

  Future<void> _skip(Duration delta) async {
    final active = controller;
    if (active == null) return;
    final limit = active.value.duration;
    var target = active.value.position + delta;
    if (target < Duration.zero) target = Duration.zero;
    if (target > limit) target = limit;
    await active.seekTo(target);
    if (mounted) setState(() {});
  }

  Future<void> _setSpeed(double value) async {
    final active = controller;
    if (active == null) return;
    try {
      await active.setPlaybackSpeed(value);
      if (mounted) setState(() => _speed = value);
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Kecepatan tidak dapat diubah.')),
        );
      }
    }
  }

  Future<void> _enterFullscreen() async {
    await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    await SystemChrome.setPreferredOrientations([DeviceOrientation.landscapeLeft, DeviceOrientation.landscapeRight]);
    if (mounted) setState(() => _fullscreen = true);
  }

  Future<void> _exitFullscreen() async {
    await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    await SystemChrome.setPreferredOrientations([]);
    if (mounted) setState(() => _fullscreen = false);
  }

  Future<void> _restoreChrome() async {
    _fullscreen = false;
    try {
      await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
      await SystemChrome.setPreferredOrientations([]);
    } on PlatformException {
      // Chrome sistem tidak dapat dipulihkan; rute tetap ditutup.
    }
  }

  Future<void> _close() async {
    await _endSession();
    await _restoreChrome();
    if (mounted) Navigator.pop(context);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) {
      unawaited(controller?.pause());
      unawaited(_endSession());
      _reveal();
    } else if (mounted && ModalRoute.of(context)?.isCurrent == true) {
      // Kembali ke rute ini: kunci layar-menyala dipasang lagi tanpa auto-play.
      unawaited(_beginSession());
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _hide?.cancel();
    unawaited(_endSession());
    unawaited(_restoreChrome());
    final active = controller;
    controller = null;
    if (active != null) {
      active.removeListener(_trackPosition);
      unawaited(() async {
        try {
          await active.dispose();
        } catch (_) {}
      }());
    }
    super.dispose();
  }


  @override
  Widget build(BuildContext context) => PopScope(
    canPop: false,
    onPopInvokedWithResult: (didPop, _) async {
      if (didPop) return;
      // Back pertama keluar dari layar penuh, berikutnya menutup rute.
      if (_fullscreen) {
        await _exitFullscreen();
        return;
      }
      await _close();
    },
    child: Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(child: () {
        if (error != null) return _errorState();
        final active = controller;
        if (active == null) {
          return const Center(child: CircularProgressIndicator(color: Colors.white));
        }
        return Stack(children: [
          Positioned.fill(
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: _toggleControls,
              child: Center(
                child: AspectRatio(
                  aspectRatio: active.value.aspectRatio,
                  child: VideoPlayer(active),
                ),
              ),
            ),
          ),
          Positioned.fill(
            child: IgnorePointer(
              ignoring: !_controls,
              child: ExcludeSemantics(
                excluding: !_controls,
                child: AnimatedOpacity(
                  opacity: _controls ? 1 : 0,
                  duration: const Duration(milliseconds: 180),
                  child: _chrome(active),
                ),
              ),
            ),
          ),
        ]);
      }()),
    ),
  );

  Widget _errorState() => Padding(
    padding: const EdgeInsets.all(24),
    child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
      const Icon(Icons.videocam_off_outlined, size: 40, color: Colors.white70),
      const SizedBox(height: 16),
      Text(error!, textAlign: TextAlign.center,
        style: const TextStyle(color: Colors.white, height: 1.5)),
      const SizedBox(height: 24),
      FilledButton(
        onPressed: () => unawaited(_close()),
        style: FilledButton.styleFrom(
          backgroundColor: Colors.white,
          foregroundColor: Colors.black,
          minimumSize: const Size(220, 52),
          shape: const RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(10))),
        ),
        child: const Text('Kembali ke koleksi'),
      ),
    ]),
  );

  Widget _chrome(VideoPlayerController active) => Stack(children: [
    Positioned(
      top: 0,
      left: 0,
      right: 0,
      child: ColoredBox(
        color: const Color(0xCC000000),
        child: Row(children: [
          IconButton(
            icon: const Icon(Icons.arrow_back, color: Colors.white),
            tooltip: 'Kembali',
            onPressed: () => unawaited(_close()),
          ),
          Expanded(
            child: Text(widget.title, maxLines: 1, overflow: TextOverflow.ellipsis,
              style: const TextStyle(color: Colors.white, fontSize: 15, fontWeight: FontWeight.w600)),
          ),
          PopupMenuButton<double>(
            tooltip: 'Kecepatan pemutaran',
            initialValue: _speed,
            onOpened: _reveal,
            onCanceled: _scheduleHide,
            onSelected: (speed) async {
              await _setSpeed(speed);
              if (mounted) _scheduleHide();
            },
            itemBuilder: (_) => [
              for (final speed in _speeds)
                CheckedPopupMenuItem(
                  value: speed,
                  checked: speed == _speed,
                  child: Text('${_formatSpeed(speed)}×'),
                ),
            ],
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text('${_formatSpeed(_speed)}×',
                style: const TextStyle(color: Colors.white)),
            ),
          ),
        ]),
      ),
    ),
    Center(
      child: DecoratedBox(
        decoration: const BoxDecoration(
          color: Color(0xCC000000),
          borderRadius: BorderRadius.all(Radius.circular(12)),
        ),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          IconButton(
            icon: const Icon(Icons.replay_10, color: Colors.white),
            tooltip: 'Mundur 10 detik',
            onPressed: () => unawaited(_skip(const Duration(seconds: -10))),
          ),
          IconButton(
            icon: Icon(
              active.value.isCompleted ? Icons.replay
                  : active.value.isPlaying ? Icons.pause : Icons.play_arrow,
              color: Colors.white, size: 36,
            ),
            style: IconButton.styleFrom(minimumSize: const Size(64, 64)),
            tooltip: active.value.isCompleted ? 'Putar ulang'
                : active.value.isPlaying ? 'Jeda video' : 'Putar video',
            onPressed: () => unawaited(_togglePlay()),
          ),
          IconButton(
            icon: const Icon(Icons.forward_10, color: Colors.white),
            tooltip: 'Maju 10 detik',
            onPressed: () => unawaited(_skip(const Duration(seconds: 10))),
          ),
        ]),
      ),
    ),
    Positioned(
      bottom: 0,
      left: 0,
      right: 0,
      child: ColoredBox(
        color: const Color(0xCC000000),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            VideoProgressIndicator(
              active,
              allowScrubbing: true,
              padding: const EdgeInsets.symmetric(vertical: 20),
              colors: const VideoProgressColors(
                playedColor: Colors.white,
                bufferedColor: Color(0x66FFFFFF),
                backgroundColor: Color(0x33FFFFFF),
              ),
            ),
            Row(children: [
              Expanded(
                child: Text(
                  '${formatDuration(active.value.position.inMilliseconds)} / ${formatDuration(active.value.duration.inMilliseconds)}',
                  style: const TextStyle(color: Colors.white, fontSize: 13,
                    fontFeatures: [FontFeature.tabularFigures()]),
                ),
              ),
              IconButton(
                icon: Icon(_fullscreen ? Icons.fullscreen_exit : Icons.fullscreen, color: Colors.white),
                tooltip: _fullscreen ? 'Keluar layar penuh' : 'Layar penuh',
                onPressed: () => unawaited(_fullscreen ? _exitFullscreen() : _enterFullscreen()),
              ),
            ]),
          ]),
        ),
      ),
    ),
  ]);

  static String _formatSpeed(double value) =>
      value.toString().replaceAll(RegExp(r'\.0$'), '');
}
