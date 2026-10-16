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
    title: 'NontonYuk!',
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

  Future<void> _openRefresh(SavedMedia item) async {
    if (_sheetOpen) return;
    _sheetOpen = true;
    try {
      await showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        constraints: const BoxConstraints(maxWidth: 560),
        builder: (_) => RefreshLinkSheet(controller: controller, item: item),
      );
    } finally {
      _sheetOpen = false;
    }
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
    if (!item.canPlay) return;
    await Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => PlayerScreen(title: item.title, id: item.id)),
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
                  'NontonYuk!',
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
                      onPlay: () => unawaited(_play(active[i])),
                      onRefresh: _refreshOf(active[i]),
                    ),
                  ),
                ],
                SliverToBoxAdapter(child: _SectionTitle('Koleksi', count: ready.length)),
                if (ready.isEmpty)
                  SliverToBoxAdapter(child: _emptyState())
                else
                  SliverList.builder(
                    itemCount: ready.length,
                    itemBuilder: (context, i) => _ReadyRow(
                      key: ValueKey(ready[i].id),
                      item: ready[i],
                      onPlay: _play,
                      onDelete: _confirmDelete,
                    ),
                  ),
                if (others.isNotEmpty) ...[
                  const SliverToBoxAdapter(child: _SectionTitle('Unduhan lainnya')),
                  SliverList.builder(
                    itemCount: others.length,
                    itemBuilder: (context, i) => _TerminalRow(
                      item: others[i],
                      onRetry: _retryOf(others[i]),
                      onPlay: others[i].canPlay ? () => unawaited(_play(others[i])) : null,
                      onDelete: () => unawaited(_confirmDelete(others[i])),
                      onRefresh: _refreshOf(others[i]),
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
    if (item.resumable) return () => unawaited(controller.resume(item.id));
    final source = item.sourceUrl;
    if (source == null || source.isEmpty) return null;
    if (platformById(item.platform) == null) return null;
    return () => unawaited(_openSheet(initialText: source));
  }

  VoidCallback? _refreshOf(SavedMedia item) {
    if (item.state != TaskState.paused && item.state != TaskState.failed &&
        item.state != TaskState.interrupted) {
      return null;
    }
    final source = item.sourceUrl;
    if (source == null || source.isEmpty || platformById(item.platform) == null) return null;
    try {
      if (platformFor(sharedLink(source)) != item.platform) return null;
    } on ExtractionException {
      return null;
    }
    return () => unawaited(_openRefresh(item));
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


class _ReadyRow extends StatelessWidget {
  const _ReadyRow({super.key, required this.item, required this.onPlay, required this.onDelete});

  final SavedMedia item;
  final ValueChanged<SavedMedia> onPlay;
  final ValueChanged<SavedMedia> onDelete;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final largeText = MediaQuery.textScalerOf(context).scale(1) >= 1.5;
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 800),
        child: InkWell(
          onTap: () => onPlay(item),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 8, 8),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              _Thumb(
                thumbPath: item.thumbPath,
                networkUrl: item.thumbnailUrl,
                durationMs: item.durationMs,
                title: item.title,
                width: largeText ? 88 : 112,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(item.title, maxLines: largeText ? 3 : 2,
                    overflow: TextOverflow.ellipsis, style: theme.textTheme.titleMedium),
                  const SizedBox(height: 4),
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
          ),
        ),
      ),
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
  const _QueueRow({
    required this.item,
    required this.onCancel,
    required this.onPause,
    required this.onResume,
    required this.onPlay,
    this.onRefresh,
  });

  final SavedMedia item;
  final VoidCallback onCancel;
  final VoidCallback onPause;
  final VoidCallback onResume;
  final VoidCallback onPlay;
  final VoidCallback? onRefresh;

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
    if (!item.refreshing && item.speedBps > 0) parts.add(formatSpeed(item.speedBps));
    if (!item.refreshing && item.etaSeconds > 0) parts.add('tersisa ${formatDuration(item.etaSeconds * 1000)}');
    return parts.join(' · ');
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final detail = _detail();
    final largeText = MediaQuery.textScalerOf(context).scale(1) >= 1.5;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 8, 12),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          _Thumb(
            thumbPath: item.thumbPath,
            networkUrl: item.thumbnailUrl,
            durationMs: 0,
            title: item.title,
            width: largeText ? 88 : 120,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(item.title, maxLines: largeText ? 3 : 2, overflow: TextOverflow.ellipsis,
                style: theme.textTheme.titleMedium),
              const SizedBox(height: 2),
              Text(item.statusLabel, style: theme.textTheme.bodySmall),
            ]),
          ),
        ]),
        if (detail.isNotEmpty) ...[
          const SizedBox(height: 4),
          Text(detail, style: theme.textTheme.bodySmall?.copyWith(
            fontFeatures: const [FontFeature.tabularFigures()])),
        ],
        const SizedBox(height: 6),
        LinearProgressIndicator(
          value: !item.refreshing && item.active && item.progress > 0 ? item.progress : null,
          semanticsLabel: item.statusLabel,
        ),
        Wrap(spacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
          IconButton(
            icon: Icon(item.paused ? Icons.play_arrow : Icons.pause),
            tooltip: item.paused ? 'Lanjutkan unduhan' : 'Jeda unduhan',
            onPressed: item.paused ? onResume : onPause,
          ),
          IconButton(
            icon: const Icon(Icons.close),
            tooltip: 'Batalkan unduhan',
            onPressed: onCancel,
          ),
          if (item.canPlay)
            TextButton.icon(
              onPressed: onPlay,
              icon: const Icon(Icons.play_arrow),
              label: const Text('Tonton sekarang'),
            ),
          if (onRefresh != null)
            TextButton(onPressed: onRefresh, child: const Text('Refresh link')),
        ]),
      ]),
    );
  }
}

class _TerminalRow extends StatelessWidget {
  const _TerminalRow({
    required this.item,
    required this.onRetry,
    required this.onDelete,
    this.onPlay,
    this.onRefresh,
  });

  final SavedMedia item;
  final VoidCallback? onRetry;
  final VoidCallback onDelete;
  final VoidCallback? onPlay;
  final VoidCallback? onRefresh;

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
            Wrap(spacing: 8, children: [
              if (onRetry != null)
                TextButton(
                  onPressed: onRetry,
                  child: Text(item.resumable ? 'Lanjutkan' : 'Coba lagi'),
                ),
              if (onPlay != null)
                TextButton.icon(
                  onPressed: onPlay,
                  icon: const Icon(Icons.play_arrow),
                  label: const Text('Tonton sekarang'),
                ),
              if (onRefresh != null)
                TextButton(onPressed: onRefresh, child: const Text('Refresh link')),
            ]),
          ]),
        ),
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
  int selectedHeight = 0;
  bool checkingQuality = false;
  bool selectionResolved = false;
  bool submitting = false;
  int _seq = 0;

  @override
  void initState() {
    super.initState();
    final seed = widget.initialText;
    if (seed != null && seed.isNotEmpty) link.text = seed;
  }

  void _invalidate() {
    _seq++;
    setState(() {
      preview = null;
      error = null;
      checking = false;
      checkingQuality = false;
      selectionResolved = false;
      selectedHeight = 0;
    });
  }

  Future<void> _paste() async {
    final ticket = ++_seq;
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    if (!mounted || ticket != _seq) return;
    final text = data?.text?.trim() ?? '';
    if (text.isEmpty) {
      setState(() {
        preview = null;
        checking = false;
        checkingQuality = false;
        selectionResolved = false;
        selectedHeight = 0;
        error = 'Papan klip kosong. Salin tautan lebih dulu, atau bagikan dari aplikasi lain.';
      });
      return;
    }
    setState(() {
      link.text = text;
      preview = null;
      error = null;
      checking = false;
      checkingQuality = false;
      selectionResolved = false;
      selectedHeight = 0;
    });
  }

  Future<void> _check() async {
    if (submitting) return;
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
      checkingQuality = false;
      selectionResolved = false;
    });
    try {
      final result = await widget.controller.probe(text);
      if (!mounted || ticket != _seq || text != link.text.trim()) return;
      if (result.platform != platformFor(sharedLink(text))) {
        throw const ExtractionException('Tautan berubah. Periksa ulang sebelum mengunduh.');
      }
      setState(() {
        preview = result;
        selectedHeight = result.height;
        selectionResolved = true;
      });
    } on ExtractionException catch (failure) {
      if (!mounted || ticket != _seq) return;
      setState(() => error = failure.message);
    } finally {
      if (mounted && ticket == _seq) setState(() => checking = false);
    }
  }

  Future<void> _selectHeight(int height) async {
    final previous = preview;
    if (previous == null || submitting ||
        (height == selectedHeight && selectionResolved)) {
      return;
    }
    final text = link.text.trim();
    final ticket = ++_seq;
    setState(() {
      selectedHeight = height;
      checkingQuality = true;
      selectionResolved = false;
      error = null;
    });
    try {
      final result = await widget.controller.probe(text, height: height);
      if (!mounted || ticket != _seq || text != link.text.trim()) return;
      if (result.id != previous.id || result.platform != previous.platform) {
        throw const ExtractionException('Video berubah. Periksa ulang tautan sebelum mengunduh.');
      }
      if (result.height != height) {
        throw const ExtractionException('Resolusi yang dipilih tidak tersedia lagi. Pilih resolusi lain.');
      }
      setState(() {
        preview = result;
        selectionResolved = true;
      });
    } on ExtractionException catch (failure) {
      if (mounted && ticket == _seq) setState(() => error = failure.message);
    } finally {
      if (mounted && ticket == _seq) setState(() => checkingQuality = false);
    }
  }

  Future<void> _download() async {
    final result = preview;
    if (result == null || !selectionResolved || checkingQuality || submitting) return;
    final text = link.text.trim();
    final height = selectedHeight;
    final ticket = _seq;
    setState(() {
      submitting = true;
      error = null;
    });
    try {
      if (!await _confirmMobileData(context)) return;
      if (!mounted || ticket != _seq || text != link.text.trim() ||
          preview != result || selectedHeight != height || !selectionResolved) {
        return;
      }
      final id = await widget.controller.enqueue(text, result, height: height);
      if (!mounted) return;
      if (id == null) {
        setState(() => error = widget.controller.error ?? 'Unduhan tidak dapat dimulai. Coba lagi.');
        return;
      }
      Navigator.pop(context, true);
    } on ExtractionException catch (failure) {
      if (mounted && ticket == _seq) setState(() => error = failure.message);
    } finally {
      if (mounted) setState(() => submitting = false);
    }
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
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text('Tambah video', style: Theme.of(context).textTheme.titleLarge)),
              IconButton(
                icon: const Icon(Icons.close),
                tooltip: 'Tutup',
                onPressed: () => Navigator.pop(context, false),
              ),
            ]),
            const SizedBox(height: 12),
            TextField(
              controller: link,
              enabled: !submitting,
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
                onPressed: checking || submitting ? null : () => unawaited(_check()),
                icon: checking
                    ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.search),
                label: Text(checking ? 'Memeriksa tautan' : 'Periksa'),
              ),
            ),
            if (preview != null) ...[
              const SizedBox(height: 16),
              _PreviewCard(
                result: preview!,
                selectedHeight: selectedHeight,
                checkingQuality: checkingQuality,
                selectionResolved: selectionResolved,
                onSelectHeight: submitting ? null : (height) => unawaited(_selectHeight(height)),
                onDownload: submitting ? null : () => unawaited(_download()),
              ),
            ],
          ]),
      ),
    );
  }
}

class _PreviewCard extends StatelessWidget {
  const _PreviewCard({
    required this.result,
    required this.selectedHeight,
    required this.checkingQuality,
    required this.selectionResolved,
    required this.onSelectHeight,
    required this.onDownload,
  });

  final ProbeResult result;
  final int selectedHeight;
  final bool checkingQuality;
  final bool selectionResolved;
  final ValueChanged<int>? onSelectHeight;
  final VoidCallback? onDownload;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
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
                if (selectedHeight > 0) '${selectedHeight}p',
                if (result.durationMs > 0) formatDuration(result.durationMs),
              ].join(' · '),
              style: theme.textTheme.bodySmall,
            ),
            const SizedBox(height: 4),
            Semantics(
              liveRegion: true,
              child: Text(
                checkingQuality
                    ? 'Memeriksa ukuran…'
                    : !selectionResolved
                        ? 'Ukuran belum terverifikasi'
                        : result.approxBytes > 0
                            ? 'sekitar ${formatBytes(result.approxBytes)}'
                            : 'Ukuran belum diketahui',
                style: theme.textTheme.bodySmall,
              ),
            ),
            if (heights.length > 1) ...[
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 4,
                children: [
                  for (final height in heights)
                    ChoiceChip(
                      label: Text('${height}p'),
                      selected: selectedHeight == height,
                      selectedColor: theme.colorScheme.primary,
                      labelStyle: TextStyle(color: selectedHeight == height
                          ? theme.colorScheme.onPrimary : theme.colorScheme.onSurface),
                      side: WidgetStateBorderSide.resolveWith((states) => BorderSide(
                        color: states.contains(WidgetState.focused)
                            ? theme.colorScheme.onSurface : theme.colorScheme.outline,
                        width: states.contains(WidgetState.focused) ? 2 : 1,
                      )),
                      showCheckmark: false,
                      materialTapTargetSize: MaterialTapTargetSize.padded,
                      onSelected: onSelectHeight == null ? null : (_) => onSelectHeight!(height),
                    ),
                ],
              ),
            ],
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: selectionResolved && !checkingQuality ? onDownload : null,
                child: Text(selectionResolved && !checkingQuality && result.approxBytes > 0
                    ? 'Unduh · ${formatBytes(result.approxBytes)}' : 'Unduh'),
              ),
            ),
          ]),
        ),
      ]),
    );
  }
}

Future<bool> _confirmMobileData(BuildContext context) async {
  String net;
  try {
    net = await networkType();
  } on ExtractionException {
    net = 'unknown';
  }
  if (!context.mounted) return false;
  if (net != 'cellular') return true;
  final go = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: const Text('Paket data seluler'),
      content: const Text(
        'Perangkat tidak memakai Wi-Fi. Unduhan dan verifikasi link memakai paket data seluler '
        'dan bisa menghabiskan kuota.'),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Batal')),
        FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Tetap unduh')),
      ],
    ),
  );
  return go == true;
}

class RefreshLinkSheet extends StatefulWidget {
  const RefreshLinkSheet({super.key, required this.controller, required this.item});

  final DownloadController controller;
  final SavedMedia item;

  @override
  State<RefreshLinkSheet> createState() => _RefreshLinkSheetState();
}

class _RefreshLinkSheetState extends State<RefreshLinkSheet> {
  final link = TextEditingController();
  bool useNewLink = false;
  bool submitting = false;
  String? error;

  Future<void> _refresh({required bool fromNewLink}) async {
    if (submitting) return;
    String? source;
    try {
      if (fromNewLink) {
        final text = link.text.trim();
        if (text.isEmpty) throw const ExtractionException('Tempel tautan pengganti lebih dulu.');
        final uri = sharedLink(text);
        if (platformFor(uri) != widget.item.platform) {
          throw const ExtractionException('Gunakan tautan dari platform yang sama.');
        }
        source = uri.toString();
      }
    } on ExtractionException catch (failure) {
      setState(() => error = failure.message);
      return;
    }
    setState(() {
      submitting = true;
      error = null;
    });
    try {
      if (!await _confirmMobileData(context) || !mounted) return;
      await widget.controller.refreshLink(widget.item.id, url: source);
      if (mounted) Navigator.pop(context);
    } on ExtractionException catch (failure) {
      if (mounted) setState(() => error = failure.message);
    } finally {
      if (mounted) setState(() => submitting = false);
    }
  }

  @override
  void dispose() {
    link.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final stored = formatBytes(widget.item.bytes);
    return SafeArea(
      child: SingleChildScrollView(
        padding: EdgeInsets.fromLTRB(20, 12, 20, MediaQuery.viewInsetsOf(context).bottom + 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Refresh link', style: theme.textTheme.titleLarge),
            const SizedBox(height: 8),
            Text(widget.item.title, style: theme.textTheme.titleMedium),
            const SizedBox(height: 12),
            Text(
              'Cache tetap disimpan. Verifikasi dapat membaca ulang '
              '${stored.isNotEmpty ? 'hingga $stored' : 'byte yang sudah tersimpan'} '
              'untuk mencocokkan sumber baru, lalu melanjutkan unduhan. '
              'Pembacaan ulang ini memakai waktu dan kuota.',
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: submitting ? null : () => unawaited(_refresh(fromNewLink: false)),
              child: const Text('Refresh dari link tersimpan'),
            ),
            const SizedBox(height: 8),
            if (!useNewLink)
              TextButton(
                onPressed: submitting ? null : () => setState(() {
                  useNewLink = true;
                  error = null;
                }),
                child: const Text('Gunakan link baru'),
              )
            else ...[
              TextField(
                controller: link,
                enabled: !submitting,
                minLines: 2,
                maxLines: 4,
                keyboardType: TextInputType.url,
                textInputAction: TextInputAction.done,
                onChanged: (_) {
                  if (error != null) setState(() => error = null);
                },
                onSubmitted: (_) => unawaited(_refresh(fromNewLink: true)),
                decoration: const InputDecoration(
                  labelText: 'Tautan pengganti',
                  hintText: 'Tautan posting atau video dari platform yang sama.',
                ),
              ),
              const SizedBox(height: 12),
              FilledButton(
                onPressed: submitting ? null : () => unawaited(_refresh(fromNewLink: true)),
                child: const Text('Verifikasi dan lanjutkan'),
              ),
            ],
            if (submitting) ...[
              const SizedBox(height: 12),
              Semantics(liveRegion: true, child: const Text('Menyiapkan refresh…')),
            ],
            if (error != null) ...[
              const SizedBox(height: 8),
              Semantics(
                liveRegion: true,
                child: Text(error!, style: TextStyle(color: theme.colorScheme.error)),
              ),
            ],
            const SizedBox(height: 8),
            TextButton(onPressed: () => Navigator.pop(context), child: const Text('Batal')),
          ],
        ),
      ),
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
