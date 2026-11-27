# Flutter #195767 Android reproduction

This app compares Impeller OpenGLES and Skia on the same Android device using the Cards and Text scenes from [njemouipro's report in flutter/flutter#195767](https://github.com/flutter/flutter/issues/195767). It adds a self-targeting Android instrumentation runner and records Flutter FrameTiming for each scroll pass.

## Build and run

Use Flutter framework and engine revision [`327cc39c4d52f581e74adbb5fd28835b2df19041`](https://github.com/flutter/flutter/commit/327cc39c4d52f581e74adbb5fd28835b2df19041), an Android SDK and JDK suitable for that revision, ADB, and Python 3. Use a physical device that can run `armeabi-v7a` APKs. Connect one device, unlock it, and keep it in portrait. The fixed swipe endpoints require a display wider than 300 px and taller than 820 px, with both endpoints inside the scrollable content.

```sh
flutter --version
flutter build apk --profile --target-platform android-arm --no-shrink
(cd android && ./gradlew :runner:assembleDebug)

adb install -r build/app/outputs/flutter-apk/app-profile.apk
adb install -r build/runner/outputs/apk/debug/runner-debug.apk
adb shell am instrument -w -e passes 3 -e backendOrder skia:gles \
  com.example.flutter_mali_repro.runner/.MaliInstrumentation
adb pull /sdcard/Download/mali195767 ./device-results

python3 tools/analyze_frames.py device-results/runner-manifest.json \
  --runtime-log impeller_gles=device-results/gles-runtime.log \
  --runtime-log skia=device-results/skia-runtime.log
```

The first Flutter build generates `android/local.properties`, which the runner's Gradle build also reads. The app manifest selects `ImpellerBackend=opengles` for the profile build. The runner restarts the app with Impeller enabled and disabled and checks startup messages for the selected renderer.

Each pass uses a fresh scene mount, two warm-up swipes, another fresh mount, and eight measured swipes (four in each direction, spaced 1.1 seconds apart). The runner temporarily sets the three Android animation scales to 1 and restores the previous numeric values. It writes per-frame timings, renderer logs, device environment, and thermal snapshots to the pull directory. GPU and driver strings come from an auxiliary EGL context in the app process.

Check for `OK (1 test)` in the instrumentation output and use a fresh local output directory for each run. Use `backendOrder gles:skia` on a separate run to check order effects. Short runs with `-e passes 1` require `--passes 1` when running the analyzer.

## Compare drawing cards with reusing their images

Run the baseline command above and pull its results first. With the same installed APKs, repeat the run with `reuseCardImages` enabled. The runner resets each scene and performs the same warm-up and measured swipes.

```sh
adb shell am instrument -w -e passes 3 -e backendOrder skia:gles -e reuseCardImages true \
  com.example.flutter_mali_repro.runner/.MaliInstrumentation
adb pull /sdcard/Download/mali195767 ./card-image-results
python3 tools/analyze_frames.py card-image-results/runner-manifest.json \
  --runtime-log impeller_gles=card-image-results/gles-runtime.log \
  --runtime-log skia=card-image-results/skia-runtime.log
```

In this mode the app captures each static card, including its shadow, at device pixel ratio and draws the image at the current scroll position. It releases the image when the card detaches. Both renderers use the option on the Cards tab. The Text tab provides a live-rendered control. Cards records include the number of captures during the measured scroll, fallbacks and an estimate of peak retained RGBA image bytes.

In the original experiment on a Moto e20 (Mali-G57 r28p0, Android 11, ARM32 profile), both modes used the same experimental engine with prepared glyph geometry. Across 36 trials, Cards raster p50 was:

| Rendering path | Impeller GLES | Skia |
| --- | ---: | ---: |
| Draw card contents | 6.956 ms | 3.627 ms |
| Reuse card images with medium filtering | 3.922 ms | 3.469 ms |

Image reuse lowered the Impeller median by 43.6%. The gap was 0.295 ms against live Skia and about 0.45 ms when both renderers reused images. The peak retained image pixel estimate was 9.75 MiB. Medium filtering softened some text edges at fractional scroll positions. These figures apply to this static-card scene on the G57. The stock SDK build above lets you measure the same comparison on your own engine and device. [Original experiment report](https://github.com/flutter/flutter/issues/195767#issuecomment-7208107195)

### Related Flutter work

- The closest match is #91648, which describes list items whose contents stay the same while their scroll offsets change. An earlier PR proposed making raster caching opt-in on `RepaintBoundary` for complex lists and was closed without merging. [Issue #91648](https://github.com/flutter/flutter/issues/91648) · [PR #101754](https://github.com/flutter/flutter/pull/101754)
- Flutter's `SnapshotWidget` provides a framework precedent for displaying a captured child. This benchmark captures cards explicitly to include the shadow outside each card's layout bounds and to count captures during the measured scroll. [PR #109485](https://github.com/flutter/flutter/pull/109485) · [SnapshotWidget API](https://api.flutter.dev/flutter/widgets/SnapshotWidget-class.html)
- The `SceneImage` proposal explores letting the engine manage the lifetime and partial updates of a rendered scene used as an image. Here the app owns the image until the card detaches or needs repainting. [Issue #106839](https://github.com/flutter/flutter/issues/106839)
- An iOS Impeller report measures texture allocation during repeated `Picture.toImageSync` calls. It is relevant to the cost of capturing a new card. The G57 scroll also includes subsequent frames that draw an already captured image. [Issue #193941](https://github.com/flutter/flutter/issues/193941)

## Measured raster p50

These are two physical-device runs with the same ARM32 profile APK and runner. Each run covered both scenes, both renderers, and three passes per combination. The runs used the Skia → Impeller GLES order.

| Device | Driver | Scene | Impeller GLES | Skia | Ratio |
|---|---|---|---:|---:|---:|
| Galaxy A10 (Android 10) | Mali-G71 r19p0 | Cards | 9.330 ms | 3.817 ms | 2.44× |
| Galaxy A10 (Android 10) | Mali-G71 r19p0 | Text | 8.251 ms | 3.7305 ms | 2.21× |
| Moto e20 (Android 11) | Mali-G57 r28p0 | Cards | 7.919 ms | 3.751 ms | 2.11× |
| Moto e20 (Android 11) | Mali-G57 r28p0 | Text | 7.318 ms | 3.8685 ms | 1.89× |

The A10 run captured 5,173 frames. Its build p50 ranged from 1.06 to 1.21 ms across the four conditions. These measurements establish the raster gap on the G71 and G57. The G31 in the original issue is a separate device. FrameTiming measures raster duration and requires a separate trace to attribute CPU execution or waits.

## Experimental engine changes

I tested two changes with local ARM32 profile engine builds at `327cc39c4d` on the Moto e20 (Mali-G57 r28p0). Each A/B run alternated engine builds, with three passes per scene and variant. The local engine builds in this table are a separate set from the stock profile APK above.

- **Glyph-key hashing.** In `SubpixelGlyph::AbslHashValue`, pack the common unstroked tone key into one 64-bit value before mixing it. This reduces hashing work on ARM32 and preserves key equality.
- **Glyph vertex preparation.** In `TextContents::ComputeVertexData`, compute the glyph origin or scaled bounds once before emitting the four vertices. Per-vertex rounding and texture coordinates keep the original calculations. A standalone comparison of the position and UV calculations matched bit for bit across 200,027 cases on ARMv7.

| A/B comparison | Cards raster p50 | Text raster p50 |
|---|---:|---:|
| Unmodified local engine → hash change | 8.405 → 7.688 ms (8.53% lower) | 8.528 → 7.612 ms (10.74% lower) |
| Hash change → hash + vertex changes | 7.718 → 7.598 ms (1.56% lower) | 7.586 → 7.033 ms (7.29% lower) |
| Hash change → hash + vertex changes, repeat | 7.722 → 7.567 ms (2.01% lower) | 7.564 → 7.031 ms (7.05% lower) |

Each percentage uses its own A/B baseline. Raster p99 moved in both directions across the experiments. The changes were measured on the G57 only.

A substantial raster gap with Skia remained. In a separate instrumented Text run, Impeller's atlas preparation visited about 1,583 glyphs per frame while adding 23 new glyphs across 414 frames. Skia can reuse prepared text data and skip individual glyph checks when the atlas generation is unchanged. Similar reuse may help close more of the gap in Impeller. The engine experiments stopped here, and this repository records the repro and results for future investigation.
