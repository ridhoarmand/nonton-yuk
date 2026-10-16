import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:video_player/video_player.dart';

import 'bridge.dart';

const _speeds = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0];

enum _Panel { none, volume, brightness, speed }

/// Local file player: volume/speed apply to this player only, brightness to
/// this app window only. Nothing here touches global device settings.
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
  String? panelError;
  bool _controls = true;
  Timer? _hide;

  double _volume = 1;
  double _beforeMute = 0.5;
  double _speed = 1;
  int _shownSecond = -1;
  double? _brightness;
  bool _brightnessTouched = false;
  bool _fullscreen = false;
  bool _session = false;
  _Panel _panel = _nonePanel;

  static const _nonePanel = _Panel.none;

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

  Future<void> _beginSession() async {
    if (_session || !mounted) return;
    try {
      final res = await platformChannel.invokeMapMethod<Object?, Object?>('beginPlayerSession');
      final value = res?['brightness'];
      _session = true;
      if (value is num && mounted) setState(() => _brightness = value.toDouble().clamp(0.05, 1.0));
    } on PlatformException {
      // Kecerahan jendela tidak tersedia; pemutaran tetap jalan.
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
    final second = active.value.position.inSeconds;
    if (second != _shownSecond) {
      _shownSecond = second;
      setState(() {});
    }
  }

  void _scheduleHide() {
    _hide?.cancel();
    // Navigasi aksesibilitas aktif: kontrol tetap tampil, tanpa sembunyi otomatis.
    if (MediaQuery.accessibleNavigationOf(context)) return;
    _hide = Timer(const Duration(milliseconds: 3500), () {
      if (!mounted) return;
      if ((controller?.value.isPlaying ?? false) && _panel == _Panel.none) {
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

  Future<void> _setVolume(double value) async {
    final active = controller;
    if (active == null) return;
    try {
      await active.setVolume(value.clamp(0.0, 1.0));
      if (mounted) {
        setState(() {
          _volume = value.clamp(0.0, 1.0);
          panelError = null;
        });
      }
    } catch (_) {
      if (mounted) setState(() => panelError = 'Volume tidak dapat diubah.');
    }
  }

  Future<void> _toggleMute() async {
    if (_volume > 0) {
      _beforeMute = _volume;
      await _setVolume(0);
    } else {
      await _setVolume(_beforeMute > 0 ? _beforeMute : 0.5);
    }
  }

  Future<void> _setSpeed(double value) async {
    final active = controller;
    if (active == null) return;
    try {
      await active.setPlaybackSpeed(value);
      if (mounted) {
        setState(() {
          _speed = value;
          panelError = null;
        });
      }
    } catch (_) {
      if (mounted) setState(() => panelError = 'Kecepatan tidak dapat diubah.');
    }
  }

  Future<void> _setBrightness(double value) async {
    if (!_session) return;
    try {
      await platformChannel.invokeMethod<Object?>('setPlayerBrightness', {'value': value});
      if (mounted) {
        setState(() {
          _brightness = value;
          _brightnessTouched = true;
          panelError = null;
        });
      }
    } on PlatformException catch (e) {
      if (mounted) setState(() => panelError = e.message ?? 'Kecerahan tidak dapat diubah.');
    } on MissingPluginException {
      // Bukan Android: tidak ada sesi kecerahan untuk diubah.
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
      // Kembali ke rute ini: sesi dibuka lagi tanpa auto-play.
      unawaited(_beginSession().then((_) async {
        if (_brightnessTouched && _brightness != null && _session) {
          try {
            await platformChannel.invokeMethod<Object?>('setPlayerBrightness', {'value': _brightness});
          } on PlatformException {
            // Jendela kembali tanpa kecerahan pilihan; pengguna dapat menggeser lagi.
          } on MissingPluginException {
            // Bukan Android: tidak ada yang perlu diterapkan ulang.
          }
        }
      }));
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

  void _openPanel(_Panel panel) {
    _hide?.cancel();
    setState(() {
      _panel = _panel == panel ? _Panel.none : panel;
      _controls = true;
      panelError = null;
    });
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
        return OrientationBuilder(builder: (context, orientation) {
          final landscape = orientation == Orientation.landscape;
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
                child: AnimatedOpacity(
                  opacity: _controls ? 1 : 0,
                  duration: const Duration(milliseconds: 180),
                  child: _chrome(active, landscape),
                ),
              ),
            ),
          ]);
        });
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
        onPressed: () => Navigator.pop(context),
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

  Widget _chrome(VideoPlayerController active, bool landscape) => DecoratedBox(
    decoration: const BoxDecoration(
      gradient: LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: [Color(0xCC000000), Color(0x00000000), Color(0xE6000000)],
        stops: [0, 0.4, 1],
      ),
    ),
    child: Column(children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(4, 4, 16, 0),
        child: Row(children: [
          IconButton(
            icon: const Icon(Icons.arrow_back, color: Colors.white),
            tooltip: 'Kembali',
            onPressed: () => unawaited(_close()),
          ),
          Expanded(child: Text(widget.title, maxLines: 1, overflow: TextOverflow.ellipsis,
            style: const TextStyle(color: Colors.white, fontSize: 15, fontWeight: FontWeight.w600))),
        ]),
      ),
      const Spacer(),
      if (landscape && _panel != _Panel.none)
        Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
          Expanded(child: _transport(active)),
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 280),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(0, 0, 16, 8),
              child: _panelCard(),
            ),
          ),
        ])
      else ...[
        _transport(active),
        if (_panel != _Panel.none)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: _panelCard(),
          ),
      ],
    ]),
  );

  Widget _transport(VideoPlayerController active) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
    child: Column(children: [
      VideoProgressIndicator(
        active,
        allowScrubbing: true,
        padding: const EdgeInsets.symmetric(vertical: 14),
        colors: const VideoProgressColors(
          playedColor: Colors.white,
          bufferedColor: Color(0x66FFFFFF),
          backgroundColor: Color(0x33FFFFFF),
        ),
      ),
      Row(mainAxisAlignment: MainAxisAlignment.center, children: [
        IconButton(
          icon: const Icon(Icons.replay_10, color: Colors.white),
          tooltip: 'Mundur 10 detik',
          onPressed: () => unawaited(_skip(const Duration(seconds: -10))),
        ),
        IconButton(
          icon: Icon(active.value.isPlaying ? Icons.pause : Icons.play_arrow,
            color: Colors.white, size: 32),
          tooltip: active.value.isPlaying ? 'Jeda video' : 'Putar video',
          onPressed: () => unawaited(_togglePlay()),
        ),
        IconButton(
          icon: const Icon(Icons.forward_10, color: Colors.white),
          tooltip: 'Maju 10 detik',
          onPressed: () => unawaited(_skip(const Duration(seconds: 10))),
        ),
      ]),
      Text(
        '${formatDuration(active.value.position.inMilliseconds)} / ${formatDuration(active.value.duration.inMilliseconds)}',
        style: const TextStyle(color: Colors.white70, fontSize: 13,
          fontFeatures: [FontFeature.tabularFigures()]),
      ),
      const SizedBox(height: 4),
      // Bar kecil berlabel; panel terkait dibuka sesuai permintaan.
      SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          TextButton.icon(
            icon: Icon(_volume == 0 ? Icons.volume_off : Icons.volume_up, color: Colors.white),
            label: Text('Volume ${_volumeLabel()}', style: const TextStyle(color: Colors.white)),
            onPressed: () => _openPanel(_Panel.volume),
          ),
          TextButton.icon(
            icon: const Icon(Icons.brightness_6, color: Colors.white),
            label: const Text('Kecerahan', style: TextStyle(color: Colors.white)),
            onPressed: () => _openPanel(_Panel.brightness),
          ),
          TextButton(
            onPressed: () => _openPanel(_Panel.speed),
            child: Text('${_formatSpeed(_speed)}×', style: const TextStyle(color: Colors.white)),
          ),
          IconButton(
            icon: Icon(_fullscreen ? Icons.fullscreen_exit : Icons.fullscreen, color: Colors.white),
            tooltip: _fullscreen ? 'Keluar layar penuh' : 'Layar penuh',
            onPressed: () => unawaited(_fullscreen ? _exitFullscreen() : _enterFullscreen()),
          ),
        ]),
      ),
    ]),
  );

  String _volumeLabel() => _volume == 0 ? 'mati' : '${(_volume * 100).round()}%';

  static String _formatSpeed(double value) =>
      value.toString().replaceAll(RegExp(r'\.0$'), '');

  Widget _panelCard() => Container(
    padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
    decoration: const BoxDecoration(
      color: Color(0xF2222222),
      borderRadius: BorderRadius.all(Radius.circular(12)),
    ),
    // Slider mengikuti chrome putih di atas video, bukan aksen pustaka.
    child: SliderTheme(
      data: const SliderThemeData(
        activeTrackColor: Colors.white,
        inactiveTrackColor: Color(0x61FFFFFF),
        thumbColor: Colors.white,
        overlayColor: Color(0x29FFFFFF),
      ),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
      switch (_panel) {
        _Panel.volume => _volumePanel(),
        _Panel.brightness => _brightnessPanel(),
        _Panel.speed => _speedPanel(),
        _Panel.none => const SizedBox.shrink(),
      },
      if (panelError != null) ...[
        const SizedBox(height: 4),
        Text(panelError!, style: const TextStyle(color: Color(0xFFFFB4AB), fontSize: 13)),
      ],
      ]),
    ),
  );

  Widget _volumePanel() => Row(children: [
    IconButton(
      icon: Icon(_volume == 0 ? Icons.volume_off : Icons.volume_up, color: Colors.white),
      tooltip: _volume == 0 ? 'Suarakan' : 'Bisukan',
      onPressed: () => unawaited(_toggleMute()),
    ),
    Expanded(
      child: Slider(
        value: _volume,
        min: 0,
        max: 1,
        divisions: 20,
        label: _volumeLabel(),
        onChanged: (value) => unawaited(_setVolume(value)),
      ),
    ),
    SizedBox(
      width: 52,
      child: Text(_volumeLabel(), textAlign: TextAlign.end,
        style: const TextStyle(color: Colors.white, fontSize: 13,
          fontFeatures: [FontFeature.tabularFigures()])),
    ),
  ]);

  Widget _brightnessPanel() {
    final value = _brightness ?? 0.5;
    return Row(children: [
      const Icon(Icons.brightness_6, color: Colors.white),
      Expanded(
        child: Slider(
          value: value.clamp(0.05, 1.0),
          min: 0.05,
          max: 1,
          label: '${(value * 100).round()}%',
          onChanged: (next) => unawaited(_setBrightness(next)),
        ),
      ),
      SizedBox(
        width: 52,
        child: Text('${(value * 100).round()}%', textAlign: TextAlign.end,
          style: const TextStyle(color: Colors.white, fontSize: 13,
            fontFeatures: [FontFeature.tabularFigures()])),
      ),
    ]);
  }

  Widget _speedPanel() => Wrap(
    spacing: 8,
    children: [
      for (final option in _speeds)
        ChoiceChip(
          label: Text('${_formatSpeed(option)}×',
            style: TextStyle(color: _speed == option ? Colors.black : Colors.white)),
          selected: _speed == option,
          selectedColor: Colors.white,
          checkmarkColor: Colors.black,
          backgroundColor: const Color(0xFF333333),
          shape: const RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(10)),
          ),
          onSelected: (_) => unawaited(_setSpeed(option)),
        ),
    ],
  );
}
