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
