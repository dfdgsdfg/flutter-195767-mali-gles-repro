part of 'main.dart';

// The benchmark cards have immutable content for the duration of a pass.
class _CardImages {
  int measuredCaptures = 0;
  int fallbacks = 0;
  int retainedBytes = 0;
  int peakBytes = 0;
  bool measuring = false;

  void beginMeasurement() {
    measuredCaptures = 0;
    measuring = true;
  }

  void endMeasurement() {
    measuring = false;
  }

  void captured(ui.Image image) {
    if (measuring) measuredCaptures++;
    retainedBytes += image.width * image.height * 4;
    if (retainedBytes > peakBytes) peakBytes = retainedBytes;
  }

  Map<String, int> toJson() => <String, int>{
    'measured_captures': measuredCaptures,
    'fallbacks': fallbacks,
    'peak_image_bytes_estimate': peakBytes,
  };
}

class _StaticCardImage extends SingleChildRenderObjectWidget {
  const _StaticCardImage({
    super.key,
    required this.stats,
    required this.pixelRatio,
    required super.child,
  });

  final _CardImages stats;
  final double pixelRatio;

  @override
  _RenderStaticCardImage createRenderObject(BuildContext context) =>
      _RenderStaticCardImage(stats, pixelRatio);

  @override
  void updateRenderObject(
    BuildContext context,
    _RenderStaticCardImage renderObject,
  ) {
    renderObject.configure(stats, pixelRatio);
  }
}

class _RenderStaticCardImage extends RenderProxyBox {
  _RenderStaticCardImage(this._stats, this._pixelRatio);

  // Capture the shadow outside the card's layout bounds.
  static const double _padding = 32;
  _CardImages _stats;
  double _pixelRatio;
  ui.Image? _image;

  void configure(_CardImages stats, double pixelRatio) {
    if (identical(stats, _stats) && pixelRatio == _pixelRatio) return;
    _discardImage();
    _stats = stats;
    _pixelRatio = pixelRatio;
    markNeedsPaint();
  }

  void _discardImage() {
    final image = _image;
    if (image != null) {
      _stats.retainedBytes -= image.width * image.height * 4;
      image.dispose();
      _image = null;
    }
  }

  @override
  void markNeedsPaint() {
    _discardImage();
    super.markNeedsPaint();
  }

  @override
  void detach() {
    _discardImage();
    super.detach();
  }

  ui.Image? _capture(Rect bounds) {
    final layer = OffsetLayer();
    try {
      final context = PaintingContext(layer, bounds);
      super.paint(context, const Offset(_padding, _padding));
      // End the private recording before taking the layer snapshot.
      // ignore: invalid_use_of_protected_member
      context.stopRecordingIfNeeded();
      if (!layer.supportsRasterization()) return null;
      return layer.toImageSync(bounds, pixelRatio: _pixelRatio);
    } finally {
      layer.dispose();
    }
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    if (size.isEmpty) return;
    final bounds = Rect.fromLTWH(
      0,
      0,
      size.width + 2 * _padding,
      size.height + 2 * _padding,
    );
    var image = _image;
    if (image == null) {
      image = _capture(bounds);
      if (image == null) {
        _stats.fallbacks++;
        super.paint(context, offset);
        return;
      }
      _image = image;
      _stats.captured(image);
    }
    context.canvas.drawImageRect(
      image,
      Rect.fromLTWH(
        0,
        0,
        bounds.width * _pixelRatio,
        bounds.height * _pixelRatio,
      ),
      bounds.shift(offset - const Offset(_padding, _padding)),
      Paint()..filterQuality = FilterQuality.medium,
    );
  }
}
