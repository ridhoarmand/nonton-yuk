import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'bridge.dart';
import 'download_controller.dart';
import 'platforms.dart';
import 'player_screen.dart';

// Pustaka video pribadi Android, feminin modern: ENERGY 2 / RHYTHM 2 / MOTION 1.
// Pink menandai aksi, biru informasi; latar koleksi mengikuti warna artwork.
const _lightScheme = ColorScheme.light(
  primary: Color(0xFFB52E70),
  onPrimary: Color(0xFFFFFFFF),
  primaryContainer: Color(0xFFFCE0ED),
  onPrimaryContainer: Color(0xFF64203F),
  secondary: Color(0xFF246A9A),
  onSecondary: Color(0xFFFFFFFF),
  secondaryContainer: Color(0xFFE1F2FC),
  onSecondaryContainer: Color(0xFF163D57),
  tertiary: Color(0xFF246A9A),
  onTertiary: Color(0xFFFFFFFF),
  tertiaryContainer: Color(0xFFE1F2FC),
  onTertiaryContainer: Color(0xFF163D57),
  surface: Color(0xFFFFFFFF),
  onSurface: Color(0xFF292433),
  onSurfaceVariant: Color(0xFF665A64),
  outline: Color(0xFF82727E),
  error: Color(0xFFB3261E),
  onError: Color(0xFFFFFFFF),
);
const _darkScheme = ColorScheme.dark(
  primary: Color(0xFFFFACD1),
  onPrimary: Color(0xFF591632),
  primaryContainer: Color(0xFF71304E),
  onPrimaryContainer: Color(0xFFFFD8E8),
  secondary: Color(0xFF99D3F3),
  onSecondary: Color(0xFF11394E),
  secondaryContainer: Color(0xFF244A61),
  onSecondaryContainer: Color(0xFFD2EEFF),
  tertiary: Color(0xFF99D3F3),
  onTertiary: Color(0xFF11394E),
  tertiaryContainer: Color(0xFF244A61),
  onTertiaryContainer: Color(0xFFD2EEFF),
  surface: Color(0xFF26212C),
  onSurface: Color(0xFFF5EDF4),
  onSurfaceVariant: Color(0xFFCABCC8),
  outline: Color(0xFF9A8997),
  error: Color(0xFFFFB4AB),
  onError: Color(0xFF161819),
);
const _lightBg = Color(0xFFFFF8FC);
const _darkBg = Color(0xFF1B1720);

ThemeData _theme(ColorScheme scheme, Color bg) {
  final focusBorder = WidgetStateProperty.resolveWith<BorderSide?>(
    (states) => states.contains(WidgetState.focused)
        ? BorderSide(color: scheme.onSurface, width: 2)
        : null,
  );
  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: bg,
    appBarTheme: AppBarTheme(
      foregroundColor: scheme.onSurface,
      backgroundColor: scheme.surface,
      surfaceTintColor: Colors.transparent,
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(foregroundColor: scheme.secondary).copyWith(side: focusBorder),
    ),
    iconButtonTheme: IconButtonThemeData(style: ButtonStyle(side: focusBorder)),
    progressIndicatorTheme: ProgressIndicatorThemeData(color: scheme.secondary),
    // Sistem font: terbaca tanpa unduhan font. Judul 24/700, seksi 20/600,
    // judul video 16/600, metadata 13-14 dengan angka tabular.
    textTheme: const TextTheme(
      headlineSmall: TextStyle(fontSize: 24, fontWeight: FontWeight.w700, height: 1.25),
      titleLarge: TextStyle(fontSize: 20, fontWeight: FontWeight.w600, height: 1.3),
      titleMedium: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, height: 1.35),
      bodyMedium: TextStyle(fontSize: 14, height: 1.5),
      bodySmall: TextStyle(fontSize: 13, height: 1.5, fontFeatures: [FontFeature.tabularFigures()]),
    ),
    cardTheme: const CardThemeData(
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(12))),
    ),
    // Radius sebagai hierarki: 8 gambar, 12 panel, 20 sheet. Tanpa shadow per item.
    bottomSheetTheme: const BottomSheetThemeData(
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
    ),
    dialogTheme: const DialogThemeData(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(20))),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      border: OutlineInputBorder(
        borderRadius: const BorderRadius.all(Radius.circular(12)),
        borderSide: BorderSide(color: scheme.outline),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: const BorderRadius.all(Radius.circular(12)),
        borderSide: BorderSide(color: scheme.outline),
      ),
    ),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: ButtonStyle(
        minimumSize: const WidgetStatePropertyAll(Size.fromHeight(48)),
        shape: const WidgetStatePropertyAll(
          RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(12))),
        ),
        side: WidgetStateProperty.resolveWith(
          (states) => focusBorder.resolve(states) ?? BorderSide(color: scheme.outline),
        ),
        textStyle: const WidgetStatePropertyAll(TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
        // Pilihan aktif memakai aksen keputusan, bukan bawaan platform.
        backgroundColor: WidgetStateProperty.resolveWith(
          (states) => states.contains(WidgetState.selected) ? scheme.primary : Colors.transparent,
        ),
        foregroundColor: WidgetStateProperty.resolveWith(
          (states) => states.contains(WidgetState.selected) ? scheme.onPrimary : scheme.onSurface,
        ),
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: ButtonStyle(
        side: focusBorder,
        minimumSize: const WidgetStatePropertyAll(Size.fromHeight(52)),
        shape: const WidgetStatePropertyAll(
          RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(12))),
        ),
        textStyle: const WidgetStatePropertyAll(TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
      ),
    ),
  );
}

void main() {
  runApp(const NontonYuksApp());
}

class NontonYuksApp extends StatefulWidget {
  const NontonYuksApp({super.key});

  @override
  State<NontonYuksApp> createState() => _NontonYuksAppState();
}

class _NontonYuksAppState extends State<NontonYuksApp> {
  ThemeMode _mode = ThemeMode.light;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    try {
      final settings = await getSettings();
      if (mounted) setState(() => _mode = _parseMode(settings.themeMode));
    } on ExtractionException {
      // Pustaka offline tetap dibuka dengan tema terang.
    }
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Nonton Yuks',
    theme: _theme(_lightScheme, _lightBg),
    darkTheme: _theme(_darkScheme, _darkBg),
    themeMode: _mode,
    home: CollectionScreen(
      onThemeChanged: (name) => setState(() => _mode = _parseMode(name)),
    ),
  );
}

ThemeMode _parseMode(String name) => switch (name) {
  'dark' => ThemeMode.dark,
  'system' => ThemeMode.system,
  _ => ThemeMode.light,
};

class CollectionScreen extends StatefulWidget {
  const CollectionScreen({super.key, required this.onThemeChanged});

  final ValueChanged<String> onThemeChanged;

  @override
  State<CollectionScreen> createState() => _CollectionScreenState();
}

class _CollectionScreenState extends State<CollectionScreen> {
  final controller = DownloadController();
  AppSettings settings = const AppSettings();
  String? _stashedShare;
  bool _sheetOpen = false;

  @override
  void initState() {
    super.initState();
    unawaited(_start());
  }

  Future<void> _start() async {
    platformChannel.setMethodCallHandler((call) async {
      if (call.method == 'shareAvailable') await _drain();
    });
    await controller.initialize();
    await _refreshSettings();
    await _drain();
  }

  Future<void> _refreshSettings() async {
    try {
      settings = await getSettings();
    } on ExtractionException {
      settings = const AppSettings();
    }
    if (mounted) setState(() {});
  }

  /// Share intake: menampung satu teks dan membuka satu
  /// sheet tanpa probe/unduh otomatis. Ditahan saat rute lain menutupi pustaka.
  Future<void> _drain() async {
    final shares = await platformChannel.invokeListMethod<String>('takePendingShares') ?? const [];
    if (shares.isEmpty || !mounted) return;
    final text = shares.last;
    if (_sheetOpen || ModalRoute.of(context)?.isCurrent != true) {
      setState(() => _stashedShare = text);
      return;
    }
    await _openSheet(initialText: text);
  }

  Future<void> _openSheet({String? initialText}) async {
    _sheetOpen = true;
    try {
      final changed = await showModalBottomSheet<bool>(
        context: context,
        isScrollControlled: true,
        constraints: const BoxConstraints(maxWidth: 560),
        builder: (_) => AddVideoSheet(
          controller: controller,
          initialText: initialText ?? _stashedShare,
        ),
      );
      _stashedShare = null;
      if (changed == true) await controller.reload();
    } finally {
      _sheetOpen = false;
    }
    // Bagikan yang datang saat sheet terbuka mendapat gilirannya sendiri.
    if (mounted && _stashedShare != null) await _openSheet();
  }

  Future<void> _openSettings() async {
    await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => SettingsScreen(
          initial: settings,
          onSaved: (saved) {
            setState(() => settings = saved);
            widget.onThemeChanged(saved.themeMode);
          },
        ),
      ),
    );
    await _refreshSettings();
  }

  Future<void> _play(SavedMedia item) async {
    final path = item.path;
    if (path == null || path.isEmpty) return;
    await Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => PlayerScreen(title: item.title, path: path)),
    );
    await controller.reload();
    // Share yang ditahan saat player terbuka dibuka setelah kembali ke pustaka.
    if (mounted && _stashedShare != null) await _openSheet();
  }

  Future<void> _confirmDelete(SavedMedia item) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Hapus video?'),
        content: Text('“${item.title}” dihapus dari perangkat dan tidak bisa diputar offline lagi.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Batal')),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text('Hapus', style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ),
        ],
      ),
    );
    if (confirmed == true) await controller.delete(item.id);
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            Theme.of(context).colorScheme.primaryContainer,
            Theme.of(context).colorScheme.secondaryContainer,
          ],
        ),
      ),
      child: SafeArea(top: false, child: ListenableBuilder(
        listenable: controller,
        builder: (context, _) {
          final items = controller.items;
          final active = items.where((m) => m.active).toList();
          final ready = items.where((m) => m.ready).toList();
          final others = items.where((m) => !m.active && !m.ready).toList();
          return RefreshIndicator(
            onRefresh: controller.reload,
            child: CustomScrollView(slivers: [
              SliverAppBar(
                floating: true,
                flexibleSpace: DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: [
                        Theme.of(context).colorScheme.primaryContainer,
                        Theme.of(context).colorScheme.secondaryContainer,
                      ],
                    ),
                  ),
                  child: const SizedBox.expand(),
                ),
                toolbarHeight: 24 + MediaQuery.textScalerOf(context).scale(20) * 2 * 1.3,
                leading: Center(
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: Image.asset(
                      'assets/nonton-yuk-logo.png',
                      width: 40,
                      height: 40,
                      fit: BoxFit.cover,
                      cacheWidth: 128,
                      excludeFromSemantics: true,
                    ),
                  ),
                ),
                title: Text(
                  'Nonton Yuks',
                  style: Theme.of(context).textTheme.titleLarge,
                  textScaler: MediaQuery.textScalerOf(context),
                  maxLines: 2,
                ),
                actions: [
                  IconButton(
                    style: IconButton.styleFrom(minimumSize: const Size(48, 48)),
                    icon: const Icon(Icons.settings_outlined),
                    tooltip: 'Pengaturan',
                    onPressed: () => unawaited(_openSettings()),
                  ),
                ],
              ),
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
                  child: SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      onPressed: () => unawaited(_openSheet()),
                      icon: const Icon(Icons.add),
                      label: const Text('Tambah video'),
                    ),
                  ),
                ),
              ),
              if (!controller.initialized)
                const SliverFillRemaining(
                  child: Center(child: Text('Membuka pustaka…')),
                )
              else ...[
                if (active.isNotEmpty) ...[
                  const SliverToBoxAdapter(child: _SectionTitle('Sedang diunduh')),
                  SliverList.builder(
                    itemCount: active.length,
                    itemBuilder: (context, i) => _QueueRow(
                      item: active[i],
                      onCancel: () => unawaited(controller.cancel(active[i].id)),
                      onPause: () => unawaited(controller.pause(active[i].id)),
                      onResume: () => unawaited(controller.resume(active[i].id)),
                    ),
                  ),
                ],
                SliverToBoxAdapter(child: _SectionTitle('Koleksi', count: ready.length)),
                if (ready.isEmpty)
                  SliverToBoxAdapter(child: _emptyState())
                else
                  SliverToBoxAdapter(child: _CollectionGrid(
                    items: ready,
                    onPlay: _play,
                    onDelete: _confirmDelete,
                  )),
                if (others.isNotEmpty) ...[
                  const SliverToBoxAdapter(child: _SectionTitle('Unduhan lainnya')),
                  SliverList.builder(
                    itemCount: others.length,
                    itemBuilder: (context, i) => _TerminalRow(
                      item: others[i],
                      onRetry: _retryOf(others[i]),
                      onDelete: () => unawaited(_confirmDelete(others[i])),
                    ),
                  ),
                ],
                const SliverToBoxAdapter(child: SizedBox(height: 24)),
              ],
            ]),
          );
        },
      )),
    ),
  );

  VoidCallback? _retryOf(SavedMedia item) {
    final source = item.sourceUrl;
    if (source == null || source.isEmpty) return null;
    if (platformById(item.platform) == null) return null;
    return () => unawaited(_openSheet(initialText: source));
  }

  Widget _emptyState() => const Padding(
    padding: EdgeInsets.fromLTRB(16, 8, 16, 24),
    child: Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Belum ada video tersimpan', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
          SizedBox(height: 8),
          Text(
            'Ketuk Tambah video lalu tempel tautan, atau bagikan tautan dari aplikasi lain ke sini.',
            style: TextStyle(height: 1.5),
          ),
        ]),
      ),
    ),
  );
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text, {this.count});

  final String text;
  final int? count;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 8),
      child: Row(children: [
        Text(text, style: theme.textTheme.titleLarge),
        if (count != null && count! > 0) ...[
          const SizedBox(width: 8),
          Text('$count', style: theme.textTheme.bodySmall),
        ],
      ]),
    );
  }
}

/// Koleksi siap tonton: ubin 16:9 berukuran isi; baris saat sempit atau teks besar.
class _CollectionGrid extends StatelessWidget {
  const _CollectionGrid({required this.items, required this.onPlay, required this.onDelete});

  final List<SavedMedia> items;
  final ValueChanged<SavedMedia> onPlay;
  final ValueChanged<SavedMedia> onDelete;

  @override
  Widget build(BuildContext context) {
    final width = MediaQuery.widthOf(context);
    final scaleFactor = MediaQuery.textScalerOf(context).scale(1);
    if (width < 360 || scaleFactor >= 1.5) {
      return Column(
        children: [for (final item in items) _ReadyRow(item: item, onPlay: onPlay, onDelete: onDelete)],
      );
    }
    final columns = width >= 900 ? 4 : width >= 600 ? 3 : 2;
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 1100),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Wrap(
            spacing: 12,
            runSpacing: 16,
            children: [
              for (final item in items)
                SizedBox(
                  width: (width.clamp(0, 1100) - 32 - 12 * (columns - 1)) / columns,
                  child: _Tile(item: item, onPlay: onPlay, onDelete: onDelete),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Tile extends StatelessWidget {
  const _Tile({required this.item, required this.onPlay, required this.onDelete});

  final SavedMedia item;
  final ValueChanged<SavedMedia> onPlay;
  final ValueChanged<SavedMedia> onDelete;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return InkWell(
      borderRadius: const BorderRadius.all(Radius.circular(8)),
      onTap: () => onPlay(item),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        _Thumb(
          thumbPath: item.thumbPath,
          durationMs: item.durationMs,
          title: item.title,
          width: double.infinity,
        ),
        const SizedBox(height: 8),
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(item.title, maxLines: 2, overflow: TextOverflow.ellipsis,
                style: theme.textTheme.titleMedium),
              const SizedBox(height: 2),
              Text(
                [
                  platformById(item.platform)?.label ?? item.platform,
                  if (item.bytes > 0) formatBytes(item.bytes),
                ].join(' · '),
                style: theme.textTheme.bodySmall,
              ),
            ]),
          ),
          _ItemMenu(onDelete: () => onDelete(item)),
        ]),
      ]),
    );
  }
}

class _ReadyRow extends StatelessWidget {
  const _ReadyRow({required this.item, required this.onPlay, required this.onDelete});

  final SavedMedia item;
  final ValueChanged<SavedMedia> onPlay;
  final ValueChanged<SavedMedia> onDelete;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 8, 12),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        _Thumb(thumbPath: item.thumbPath, durationMs: item.durationMs, title: item.title, width: 120),
        const SizedBox(width: 12),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            InkWell(
              onTap: () => onPlay(item),
              child: Text(item.title, maxLines: 2, overflow: TextOverflow.ellipsis,
                style: theme.textTheme.titleMedium),
            ),
            const SizedBox(height: 2),
            Text(
              [
                platformById(item.platform)?.label ?? item.platform,
                if (item.bytes > 0) formatBytes(item.bytes),
              ].join(' · '),
              style: theme.textTheme.bodySmall,
            ),
          ]),
        ),
        _ItemMenu(onDelete: () => onDelete(item)),
      ]),
    );
  }
}

class _ItemMenu extends StatelessWidget {
  const _ItemMenu({required this.onDelete});

  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) => PopupMenuButton<String>(
    icon: const Icon(Icons.more_vert),
    tooltip: 'Pilihan video',
    onSelected: (_) => onDelete(),
    itemBuilder: (context) => const [
      PopupMenuItem(value: 'delete', child: Text('Hapus')),
    ],
  );
}

/// Gambar lokal; tidak pernah streaming poster saat thumb offline sudah ada.
class _Thumb extends StatelessWidget {
  const _Thumb({required this.thumbPath, required this.durationMs, required this.title, required this.width, this.networkUrl});

  final String? thumbPath;
  final String? networkUrl;
  final int durationMs;
  final String title;
  final double width;

  @override
  Widget build(BuildContext context) {
    final path = thumbPath;
    final net = networkUrl;
    final body = Stack(fit: StackFit.expand, children: [
          if (path != null && path.isNotEmpty)
            Image.file(
              File(path),
              fit: BoxFit.cover,
              errorBuilder: (_, _, _) => _ThumbFallback(title: title),
            )
          else if (net != null && net.isNotEmpty)
            Image.network(
              net,
              fit: BoxFit.cover,
              errorBuilder: (_, _, _) => _ThumbFallback(title: title),
              loadingBuilder: (context, child, progress) =>
                  progress == null ? child : _ThumbFallback(title: title),
            )
          else
            _ThumbFallback(title: title),
          if (durationMs > 0)
            Positioned(
              right: 6,
              bottom: 6,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: const BoxDecoration(
                  color: Color(0xE6000000),
                  borderRadius: BorderRadius.all(Radius.circular(6)),
                ),
                child: Text(
                  formatDuration(durationMs),
                  style: const TextStyle(color: Colors.white, fontSize: 12,
                    fontFeatures: [FontFeature.tabularFigures()]),
                ),
              ),
            ),
        ]);
    final clipped = ClipRRect(
      borderRadius: const BorderRadius.all(Radius.circular(8)),
      child: body,
    );
    // Lebar takhingga (ubin grid): kunci rasio 16:9; lebar pasti: kunci piksel.
    if (width == double.infinity) return AspectRatio(aspectRatio: 16 / 9, child: clipped);
    return SizedBox(width: width, height: width * 9 / 16, child: clipped);
  }
}

class _ThumbFallback extends StatelessWidget {
  const _ThumbFallback({required this.title});

  final String title;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ColoredBox(
      color: scheme.surface,
      child: Center(
        child: Text(
          title.trim().isEmpty ? '…' : title.trim().substring(0, 1).toUpperCase(),
          style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 28, fontWeight: FontWeight.w600),
        ),
      ),
    );
  }
}

class _QueueRow extends StatelessWidget {
  const _QueueRow({required this.item, required this.onCancel, required this.onPause, required this.onResume});

  final SavedMedia item;
  final VoidCallback onCancel;
  final VoidCallback onPause;
  final VoidCallback onResume;

  String _detail() {
    final total = item.approxBytes;
    final parts = <String>[];
    if (item.restarted) parts.add('Mulai dari awal');
    if (total > 0 && item.progress > 0) parts.add('${(item.progress * 100).round()}%');
    if (item.bytes > 0) {
      parts.add(total > 0
          ? '${formatBytes(item.bytes)} dari ${formatBytes(total)}'
          : '${formatBytes(item.bytes)} diunduh');
    }
    if (item.speedBps > 0) parts.add(formatSpeed(item.speedBps));
    if (item.etaSeconds > 0) parts.add('tersisa ${formatDuration(item.etaSeconds * 1000)}');
    return parts.join(' · ');
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final detail = _detail();
    final pausable = !item.paused && item.phase != 'merge';
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 8, 12),
      child: Row(children: [
        _Thumb(thumbPath: item.thumbPath, networkUrl: item.thumbnailUrl, durationMs: 0, title: item.title, width: 120),
        const SizedBox(width: 12),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(item.title, maxLines: 2, overflow: TextOverflow.ellipsis,
              style: theme.textTheme.titleMedium),
            const SizedBox(height: 2),
            Text(item.statusLabel, style: theme.textTheme.bodySmall),
            if (detail.isNotEmpty) ...[
              const SizedBox(height: 2),
              Text(detail, style: theme.textTheme.bodySmall?.copyWith(
                fontFeatures: const [FontFeature.tabularFigures()])),
            ],
            const SizedBox(height: 6),
            LinearProgressIndicator(value: item.active && item.progress > 0 ? item.progress : null),
          ]),
        ),
        if (item.paused)
          IconButton(
            icon: const Icon(Icons.play_arrow),
            tooltip: 'Lanjutkan unduhan',
            onPressed: onResume,
          )
        else if (pausable)
          IconButton(
            icon: const Icon(Icons.pause),
            tooltip: 'Jeda unduhan',
            onPressed: onPause,
          ),
        IconButton(
          icon: const Icon(Icons.close),
          tooltip: 'Batalkan unduhan',
          onPressed: onCancel,
        ),
      ]),
    );
  }
}

class _TerminalRow extends StatelessWidget {
  const _TerminalRow({required this.item, required this.onRetry, required this.onDelete});

  final SavedMedia item;
  final VoidCallback? onRetry;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 8, 12),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(item.title, maxLines: 2, overflow: TextOverflow.ellipsis,
              style: theme.textTheme.titleMedium),
            const SizedBox(height: 2),
            // Pesan error sudah menjelaskan status; jangan mengulang labelnya.
            Text(
              (item.error ?? '').isNotEmpty ? item.error! : item.statusLabel,
              style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.error),
            ),
          ]),
        ),
        if (onRetry != null)
          TextButton(onPressed: onRetry, child: const Text('Coba lagi'))
        else
          const SizedBox(width: 8),
        IconButton(
          icon: const Icon(Icons.delete_outline),
          tooltip: 'Hapus unduhan',
          onPressed: onDelete,
        ),
      ]),
    );
  }
}

/// Satu tombol utama membuka sheet; state async milik sheet sendiri.
class AddVideoSheet extends StatefulWidget {
  const AddVideoSheet({super.key, required this.controller, this.initialText});

  final DownloadController controller;
  final String? initialText;

  @override
  State<AddVideoSheet> createState() => _AddVideoSheetState();
}

class _AddVideoSheetState extends State<AddVideoSheet> {
  final link = TextEditingController();
  ProbeResult? preview;
  String? error;
  bool checking = false;
  int _seq = 0;

  @override
  void initState() {
    super.initState();
    final seed = widget.initialText;
    if (seed != null && seed.isNotEmpty) link.text = seed;
  }

  void _invalidate() {
    _seq++;
    if (preview != null || error != null || checking) {
      setState(() {
        preview = null;
        error = null;
        checking = false;
      });
    }
  }

  Future<void> _paste() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    if (!mounted) return;
    final text = data?.text?.trim() ?? '';
    _seq++;
    if (text.isEmpty) {
      setState(() {
        preview = null;
        checking = false;
        error = 'Papan klip kosong. Salin tautan lebih dulu, atau bagikan dari aplikasi lain.';
      });
      return;
    }
    setState(() {
      link.text = text;
      preview = null;
      error = null;
      checking = false;
    });
  }

  Future<void> _check() async {
    final text = link.text.trim();
    if (text.isEmpty) {
      setState(() => error = 'Tempel tautan video lebih dulu.');
      return;
    }
    final ticket = ++_seq;
    setState(() {
      preview = null;
      error = null;
      checking = true;
    });
    try {
      final result = await widget.controller.probe(text);
      if (!mounted || ticket != _seq) return; // Tautan berubah; jawaban basi dibuang.
      setState(() => preview = result);
    } on ExtractionException catch (failure) {
      if (!mounted || ticket != _seq) return;
      setState(() => error = failure.message);
    } finally {
      if (mounted && ticket == _seq) setState(() => checking = false);
    }
  }

  Future<void> _download({int height = 0}) async {
    final result = preview;
    if (result == null) return;
    if (!await _confirmMobileData()) return;
    try {
      final id = await widget.controller.enqueue(link.text.trim(), result, height: height);
      if (!mounted) return;
      if (id == null) {
        setState(() => error = widget.controller.error ?? 'Unduhan tidak dapat dimulai. Coba lagi.');
        return;
      }
      if (mounted) Navigator.pop(context, true);
    } on ExtractionException catch (failure) {
      if (mounted) setState(() => error = failure.message);
    }
  }

  /// Offline-first tool: downloads on cellular need an explicit tap, never a surprise.
  Future<bool> _confirmMobileData() async {
    String net;
    try {
      net = await networkType();
    } on ExtractionException {
      net = 'unknown';
    }
    if (net != 'cellular' || !mounted) return true;
    final go = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Paket data seluler'),
        content: const Text(
          'Perangkat tidak memakai Wi-Fi. Unduhan memakai paket data seluler dan bisa menghabiskan kuota.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Batal')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Tetap unduh')),
        ],
      ),
    );
    return go == true;
  }

  @override
  void dispose() {
    link.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final bottom = MediaQuery.viewInsetsOf(context).bottom;
    return SafeArea(
      child: SingleChildScrollView(
        padding: EdgeInsets.fromLTRB(20, 12, 20, bottom + 20),
        child: ConstrainedBox(
          constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * 0.9),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Text('Tambah video', style: Theme.of(context).textTheme.titleLarge),
              const Spacer(),
              IconButton(
                icon: const Icon(Icons.close),
                tooltip: 'Tutup',
                onPressed: () => Navigator.pop(context, false),
              ),
            ]),
            const SizedBox(height: 12),
            TextField(
              controller: link,
              minLines: 2,
              maxLines: 4,
              keyboardType: TextInputType.url,
              textInputAction: TextInputAction.done,
              onChanged: (_) => _invalidate(),
              onSubmitted: (_) => unawaited(_check()),
              decoration: InputDecoration(
                labelText: 'Tautan video',
                hintText: 'Tempel tautan YouTube atau Facebook publik.',
                suffixIcon: IconButton(
                  icon: const Icon(Icons.content_paste),
                  tooltip: 'Tempel dari papan klip',
                  onPressed: () => unawaited(_paste()),
                ),
              ),
            ),
            if (error != null) ...[
              const SizedBox(height: 8),
              Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ],
            const SizedBox(height: 12),
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                onPressed: checking ? null : () => unawaited(_check()),
                icon: checking
                    ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.search),
                label: Text(checking ? 'Memeriksa tautan' : 'Periksa'),
              ),
            ),
            if (preview != null) ...[
              const SizedBox(height: 16),
              _PreviewCard(result: preview!, onDownload: (h) => _download(height: h)),
            ],
          ]),
        ),
      ),
    );
  }
}

class _PreviewCard extends StatefulWidget {
  const _PreviewCard({required this.result, required this.onDownload});

  final ProbeResult result;
  final void Function(int height) onDownload;

  @override
  State<_PreviewCard> createState() => _PreviewCardState();
}

class _PreviewCardState extends State<_PreviewCard> {
  late int selected = widget.result.heights.isNotEmpty
      ? widget.result.heights.first
      : widget.result.height;

  @override
  void didUpdateWidget(covariant _PreviewCard old) {
    super.didUpdateWidget(old);
    if (old.result != widget.result) {
      selected = widget.result.heights.isNotEmpty
          ? widget.result.heights.first
          : widget.result.height;
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final result = widget.result;
    final url = result.thumbnailUrl;
    final heights = result.heights;
    return Card(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        if (url != null && url.isNotEmpty)
          ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: 180),
            child: ClipRRect(
              borderRadius: const BorderRadius.vertical(top: Radius.circular(12)),
              child: AspectRatio(
                aspectRatio: 16 / 9,
                child: Image.network(
                  url,
                  fit: BoxFit.cover,
                  errorBuilder: (_, _, _) => _ThumbFallback(title: result.title),
                  loadingBuilder: (context, child, progress) =>
                      progress == null ? child : _ThumbFallback(title: result.title),
                ),
              ),
            ),
          ),
        Padding(
          padding: const EdgeInsets.all(16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(result.title, maxLines: 3, overflow: TextOverflow.ellipsis,
              style: theme.textTheme.titleMedium),
            const SizedBox(height: 6),
            Text(
              [
                platformById(result.platform)?.label ?? result.platform,
                if (result.uploader != null) result.uploader!,
                if (result.height > 0) 'sampai ${result.height}p',
                if (result.durationMs > 0) formatDuration(result.durationMs),
                if (result.approxBytes > 0) 'sekitar ${formatBytes(result.approxBytes)}',
              ].join(' · '),
              style: theme.textTheme.bodySmall,
            ),
            if (heights.length > 1) ...[
              const SizedBox(height: 12),
              SegmentedButton<int>(
                segments: [
                  for (final h in heights) ButtonSegment(value: h, label: Text('${h}p')),
                ],
                selected: {selected},
                onSelectionChanged: (set) => setState(() => selected = set.single),
                showSelectedIcon: false,
              ),
            ],
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: () => widget.onDownload(heights.length > 1 ? selected : 0),
                child: Text(result.approxBytes > 0 ? 'Unduh · ${formatBytes(result.approxBytes)}' : 'Unduh'),
              ),
            ),
          ]),
        ),
      ]),
    );
  }
}

/// Pengaturan tema; tersimpan native, berlaku tanpa restart.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.initial, required this.onSaved});

  final AppSettings initial;
  final ValueChanged<AppSettings> onSaved;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late String theme = widget.initial.themeMode;
  String? error;
  bool saving = false;

  Future<void> _save() async {
    setState(() {
      error = null;
      saving = true;
    });
    final next = AppSettings(themeMode: theme);
    try {
      await setSettings(next);
      widget.onSaved(next);
      if (mounted) Navigator.pop(context);
    } on ExtractionException catch (failure) {
      if (mounted) setState(() => error = failure.message);
    } finally {
      if (mounted) setState(() => saving = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Pengaturan')),
    body: ListView(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
      children: [
        Text('Tema', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 12),
        SegmentedButton<String>(
          segments: const [
            ButtonSegment(value: 'system', label: Text('Sistem')),
            ButtonSegment(value: 'light', label: Text('Terang')),
            ButtonSegment(value: 'dark', label: Text('Gelap')),
          ],
          selected: {theme},
          onSelectionChanged: (selection) => setState(() => theme = selection.first),
        ),
        if (error != null) ...[
          const SizedBox(height: 12),
          Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
        ],
        const SizedBox(height: 16),
        SizedBox(
          width: double.infinity,
          child: FilledButton(
            onPressed: saving ? null : () => unawaited(_save()),
            child: Text(saving ? 'Menyimpan…' : 'Simpan'),
          ),
        ),
      ],
    ),
  );
}
