// Minimal repro: Impeller OpenGLES raster time vs Skia on a low-end
// Android device without Vulkan. No packages, no images.
import 'dart:ui' as ui;
import 'dart:developer' as developer;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

part 'card_image.dart';

const MethodChannel _controlChannel = MethodChannel('mali_repro/control');

void main() => runApp(const ReproApp());

class ReproApp extends StatelessWidget {
  const ReproApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: Colors.teal),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  int _tab = 0;
  final List<Map<String, int>> _timings = <Map<String, int>>[];
  int _generation = 0;
  int? _beginUs;
  String _activeLabel = 'unknown';
  String _activeBackend = 'skia';
  String _activeTab = 'Cards';
  int _activePass = 1;
  String _activeToken = '';
  _CardImages? _cardImages;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addTimingsCallback(_recordTimings);
    _controlChannel.setMethodCallHandler(_handleControl);
    _controlChannel.invokeMethod<void>('ready', <String, Object?>{
      'protocol': 1,
      'package': 'com.example.flutter_mali_repro',
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeTimingsCallback(_recordTimings);
    super.dispose();
  }

  void _recordTimings(List<ui.FrameTiming> frames) {
    for (final ui.FrameTiming frame in frames) {
      _timings.add(<String, int>{
        'build_us': frame.buildDuration.inMicroseconds,
        'raster_us': frame.rasterDuration.inMicroseconds,
        'vsync_us': frame.vsyncOverhead.inMicroseconds,
        'timestamp_us': frame.timestampInMicroseconds(ui.FramePhase.rasterStart),
      });
    }
    if (_timings.length > 10000) {
      _timings.removeRange(0, _timings.length - 10000);
    }
  }

  Future<Object?> _handleControl(MethodCall call) async {
    if (call.method == 'reset') {
      final Map<Object?, Object?> args = call.arguments as Map<Object?, Object?>;
      final String tabName = args['tab'] as String? ?? 'Cards';
      final int tab = tabName == 'Text' ? 1 : 0;
      _activeLabel = args['label'] as String? ?? 'unknown';
      _activeToken = args['token'] as String? ?? '';
      _activeBackend = args['backend'] as String? ?? 'skia';
      _activeTab = tabName;
      _activePass = (args['pass'] as num?)?.toInt() ?? 1;
      _cardImages = tabName == 'Cards' && args['reuseCardImages'] == true
          ? _CardImages()
          : null;
      _beginUs = null;
      setState(() {
        _tab = tab;
        _generation++;
        _timings.clear();
      });
      await WidgetsBinding.instance.endOfFrame;
      return <String, Object?>{'state': 'reset', 'generation': _generation};
    }
    if (call.method == 'begin') {
      _timings.clear();
      _cardImages?.beginMeasurement();
      _beginUs = developer.Timeline.now;
      return <String, Object?>{'state': 'begin', 'begin_us': _beginUs};
    }
    if (call.method == 'end') {
      final int endUs = developer.Timeline.now;
      _cardImages?.endMeasurement();
      await Future<void>.delayed(const Duration(milliseconds: 600));
      final int beginUs = _beginUs ?? endUs;
      final List<Map<String, int>> result = _timings
          .where((Map<String, int> frame) {
            final int timestamp = frame['timestamp_us'] ?? 0;
            return timestamp >= beginUs && timestamp <= endUs;
          })
          .map((Map<String, int> frame) => Map<String, int>.from(frame))
          .toList(growable: false);
      await _controlChannel.invokeMethod<void>('writeResult', <String, Object?>{
        'label': _activeLabel,
        'token': _activeToken,
        'backend': _activeBackend,
        'tab': _activeTab,
        'pass': _activePass,
        'begin_us': beginUs,
        'end_us': endUs,
        'frames': result,
        if (_cardImages != null) 'card_images': _cardImages!.toJson(),
      });
      _timings.clear();
      _beginUs = null;
      return <String, Object?>{'state': 'end', 'frames': result.length, 'end_us': endUs};
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF2F3F5),
      appBar: AppBar(title: Text(_tab == 0 ? 'Cards' : 'Text')),
      body: KeyedSubtree(
        key: ValueKey<String>('tab-$_tab-generation-$_generation'),
        child: _tab == 0 ? CardsTab._(_cardImages) : const TextTab(),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (i) => setState(() => _tab = i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.view_agenda), label: 'Cards'),
          NavigationDestination(icon: Icon(Icons.notes), label: 'Text'),
        ],
      ),
    );
  }
}

const _thumbColors = [
  Color(0xFF4DB6AC),
  Color(0xFF7986CB),
  Color(0xFFFFB74D),
  Color(0xFFE57373),
  Color(0xFF81C784),
  Color(0xFF64B5F6),
];

const _thumbIcons = [
  Icons.shopping_bag,
  Icons.local_shipping,
  Icons.flight,
  Icons.inventory_2,
  Icons.watch,
  Icons.headphones,
];

class CardsTab extends StatelessWidget {
  const CardsTab({super.key}) : _images = null;
  const CardsTab._(this._images);

  final _CardImages? _images;

  @override
  Widget build(BuildContext context) {
    return ListView.builder(
      padding: const EdgeInsets.symmetric(vertical: 8),
      itemCount: 60,
      itemBuilder: (context, i) {
        final card = ItemCard(index: i);
        final images = _images;
        return images == null
            ? card
            : _StaticCardImage(
                key: ValueKey<int>(i),
                stats: images,
                pixelRatio: MediaQuery.devicePixelRatioOf(context),
                child: card,
              );
      },
    );
  }
}

class ItemCard extends StatelessWidget {
  const ItemCard({super.key, required this.index});

  final int index;

  @override
  Widget build(BuildContext context) {
    final color = _thumbColors[index % _thumbColors.length];
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(14),
          boxShadow: const [
            BoxShadow(
              color: Color(0x14000000),
              blurRadius: 6,
              offset: Offset(0, 2),
            ),
            BoxShadow(
              color: Color(0x0A000000),
              blurRadius: 16,
              offset: Offset(0, 6),
            ),
          ],
        ),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(10),
                child: Container(
                  width: 64,
                  height: 64,
                  color: color,
                  child: Icon(
                    _thumbIcons[index % _thumbIcons.length],
                    color: Colors.white,
                    size: 30,
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Item number $index with a fairly long title that '
                      'wraps on two lines',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '${(index * 7 % 90) + 10}.00 EUR',
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                        color: Color(0xFF00796B),
                      ),
                    ),
                    const SizedBox(height: 2),
                    const Text(
                      'Secondary line in grey',
                      style: TextStyle(fontSize: 13, color: Colors.grey),
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        const _Chip(label: 'Tag one'),
                        const SizedBox(width: 6),
                        const _Chip(label: 'Tag two'),
                        const Spacer(),
                        Icon(Icons.thumb_up_outlined,
                            size: 18, color: Colors.grey.shade600),
                        const SizedBox(width: 10),
                        Icon(Icons.thumb_down_outlined,
                            size: 18, color: Colors.grey.shade600),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Chip extends StatelessWidget {
  const _Chip({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: const Color(0xFFE0F2F1),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        child: Text(
          label,
          style: const TextStyle(fontSize: 12, color: Color(0xFF00695C)),
        ),
      ),
    );
  }
}

const _lorem =
    'Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod '
    'tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim '
    'veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea '
    'commodo consequat. Duis aute irure dolor in reprehenderit in voluptate '
    'velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint '
    'occaecat cupidatat non proident, sunt in culpa qui officia deserunt '
    'mollit anim id est laborum.';

class TextTab extends StatelessWidget {
  const TextTab({super.key});

  @override
  Widget build(BuildContext context) {
    return ListView.builder(
      padding: const EdgeInsets.all(16),
      itemCount: 40,
      itemBuilder: (context, i) => Padding(
        padding: const EdgeInsets.only(bottom: 14),
        child: Text(
          '${i + 1}. $_lorem',
          style: const TextStyle(fontSize: 15, height: 1.4),
        ),
      ),
    );
  }
}
