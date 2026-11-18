#!/usr/bin/env python3
"""Validate and summarize FrameTiming results from the isolated repro app.

The input is a JSON object with a ``runs`` array. Each run must carry explicit
fields ``backend``, ``tab``, and integer ``pass``. ``backend`` is accepted only
when a matching independent runtime log is supplied with ``--runtime-log``;
the requested launch flag is deliberately ignored. A run may either contain
``frames`` directly or ``frames_path`` relative to the result file.

Runtime log files must contain a line of the form ``RUNTIME_BACKEND=<value>``
where value is one of ``impeller_gles`` or ``skia``. They must also contain
independent engine evidence: the Impeller OpenGLES startup line for
``impeller_gles``, or the Skia opt-out-deprecated line (or an explicitly
verified native Skia marker) for ``skia``. A requested launch flag alone never
establishes the backend.
"""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import re
import sys
from collections import defaultdict
from typing import Any

BACKENDS = {"impeller_gles", "skia"}
TABS = {"Cards", "Text"}
BACKEND_RE = re.compile(r"^RUNTIME_BACKEND=(impeller_gles|skia)\s*$", re.MULTILINE)
ENGINE_EVIDENCE = {
    "impeller_gles": re.compile(
        r"Using the Impeller rendering backend \(OpenGLES\)", re.IGNORECASE
    ),
    "skia": re.compile(
        r"Impeller opt-out deprecated|RUNTIME_NATIVE_BACKEND=skia_verified",
        re.IGNORECASE,
    ),
}


def quantile(values: list[float], q: float) -> float:
    if not values:
        raise ValueError("cannot compute a quantile of an empty sequence")
    values = sorted(values)
    pos = (len(values) - 1) * q
    low = math.floor(pos)
    high = math.ceil(pos)
    if low == high:
        return values[low]
    return values[low] + (values[high] - values[low]) * (pos - low)


def runtime_backend(path: pathlib.Path, expected: str) -> str:
    text = path.read_text(errors="replace")
    matches = BACKEND_RE.findall(text)
    if len(matches) != 1:
        raise ValueError(
            f"{path}: expected exactly one independent RUNTIME_BACKEND marker, "
            f"found {len(matches)}"
        )
    if not ENGINE_EVIDENCE[expected].search(text):
        raise ValueError(
            f"{path}: missing independent engine evidence for {expected}; "
            "a requested launch flag or generic marker is insufficient"
        )
    return matches[0]


def load_frames(run: dict[str, Any], result_path: pathlib.Path) -> list[dict[str, Any]]:
    if "frames" in run:
        frames = run["frames"]
    elif "frames_path" in run:
        frames = json.loads((result_path.parent / run["frames_path"]).read_text())
    else:
        raise ValueError("run has neither frames nor frames_path")
    if not isinstance(frames, list) or not frames:
        raise ValueError("run has no frames")
    for frame in frames:
        if not isinstance(frame, dict):
            raise ValueError("every frame must be an object")
        for key in ("build_us", "raster_us"):
            value = frame.get(key)
            if not isinstance(value, (int, float)) or isinstance(value, bool):
                raise ValueError(f"every frame must contain numeric {key}")
            if not math.isfinite(float(value)) or float(value) < 0:
                raise ValueError(f"frame {key} must be finite and non-negative")
        if "vsync_us" in frame:
            value = frame["vsync_us"]
            if not isinstance(value, (int, float)) or isinstance(value, bool):
                raise ValueError("frame vsync_us must be numeric")
            if not math.isfinite(float(value)) or float(value) < 0:
                raise ValueError("frame vsync_us must be finite and non-negative")
    return frames


def parse_run_identity(run: dict[str, Any], index: int) -> tuple[str, str, int]:
    # Require structured fields. A free-form label such as "Cards" is rejected
    # because it cannot establish backend or pass identity without inference.
    missing = [key for key in ("backend", "tab", "pass") if key not in run]
    if missing:
        raise ValueError(f"run {index}: missing structured fields {missing}")
    backend = run["backend"]
    tab = run["tab"]
    run_pass = run["pass"]
    if backend not in BACKENDS:
        raise ValueError(f"run {index}: unsupported backend {backend!r}")
    if tab not in TABS:
        raise ValueError(f"run {index}: unsupported tab {tab!r}")
    if not isinstance(run_pass, int) or run_pass < 1:
        raise ValueError(f"run {index}: pass must be a positive integer")
    return backend, tab, run_pass


def summarize(frames: list[dict[str, Any]]) -> dict[str, float | int]:
    raster = [float(frame["raster_us"]) for frame in frames]
    build = [float(frame["build_us"]) for frame in frames]
    frame_budget = [max(build_us, raster_us) for build_us, raster_us in zip(build, raster)]
    slow = sum(value > 16_700 for value in frame_budget)
    return {
        "frames": len(frames),
        "build_p50_ms": quantile(build, 0.50) / 1000,
        "build_p90_ms": quantile(build, 0.90) / 1000,
        "build_p99_ms": quantile(build, 0.99) / 1000,
        "raster_p50_ms": quantile(raster, 0.50) / 1000,
        "raster_p90_ms": quantile(raster, 0.90) / 1000,
        "raster_p99_ms": quantile(raster, 0.99) / 1000,
        "frames_over_16_7ms_percent": 100 * slow / len(frames),
    }


def analyze(
    path: pathlib.Path,
    runtime_logs: dict[str, pathlib.Path],
    passes: int = 3,
) -> dict[str, Any]:
    if passes < 1:
        raise ValueError("passes must be positive")
    document = json.loads(path.read_text())
    runs = document.get("runs")
    if not isinstance(runs, list) or not runs:
        raise ValueError("results JSON must contain a non-empty runs array")
    observed = {backend: runtime_backend(log, backend) for backend, log in runtime_logs.items()}
    for backend in BACKENDS:
        if backend not in observed:
            raise ValueError(f"missing independent runtime log for {backend}")
        if observed[backend] != backend:
            raise ValueError(f"runtime log key {backend} disagrees with marker {observed[backend]}")

    groups: dict[tuple[str, str, int], list[float]] = defaultdict(list)
    pooled: dict[str, list[dict[str, Any]]] = defaultdict(list)
    pooled_by_tab: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    seen: set[tuple[str, str, int]] = set()
    for index, run in enumerate(runs):
        if not isinstance(run, dict):
            raise ValueError(f"run {index}: expected an object")
        backend, tab, run_pass = parse_run_identity(run, index)
        key = (backend, tab, run_pass)
        if key in seen:
            raise ValueError(f"duplicate run identity {key}")
        seen.add(key)
        frames = load_frames(run, path)
        groups[key].extend(frames)
        pooled[backend].extend(frames)
        pooled_by_tab[(backend, tab)].extend(frames)

    expected = {
        (backend, tab, run_pass)
        for backend in BACKENDS
        for tab in TABS
        for run_pass in range(1, passes + 1)
    }
    missing = sorted(expected - seen)
    if missing:
        raise ValueError(f"missing expected runs: {missing}")
    unexpected = sorted(seen - expected)
    if unexpected:
        raise ValueError(f"unexpected runs: {unexpected}")
    for index, run in enumerate(runs):
        begin = run.get("begin_us")
        end = run.get("end_us")
        timestamped = [frame.get("timestamp_us") for frame in load_frames(run, path) if "timestamp_us" in frame]
        if (begin is None) != (end is None):
            raise ValueError(f"run {index}: begin_us and end_us must be supplied together")
        if begin is not None:
            if not all(isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(float(value)) for value in (begin, end)):
                raise ValueError(f"run {index}: timestamp window must be finite numbers")
            if float(begin) > float(end):
                raise ValueError(f"run {index}: begin_us must not exceed end_us")
            if timestamped and any(
                not isinstance(value, (int, float))
                or isinstance(value, bool)
                or not math.isfinite(float(value))
                or float(value) < float(begin)
                or float(value) > float(end)
                for value in timestamped
            ):
                raise ValueError(f"run {index}: frame timestamp outside begin_us/end_us")
        elif timestamped:
            raise ValueError(f"run {index}: timestamp_us requires begin_us and end_us")
    result: dict[str, Any] = {
        "source": str(path),
        "independent_runtime_backends": observed,
        "runs": {f"{backend}/{tab}/pass-{run_pass}": summarize(frames) for (backend, tab, run_pass), frames in sorted(groups.items())},
        "pooled": {backend: summarize(frames) for backend, frames in sorted(pooled.items())},
        "pooled_by_tab": {
            f"{backend}/{tab}": summarize(frames)
            for (backend, tab), frames in sorted(pooled_by_tab.items())
        },
    }
    impeller = result["pooled"]["impeller_gles"]["raster_p50_ms"]
    skia = result["pooled"]["skia"]["raster_p50_ms"]
    result["pooled_raster_p50_ratio_impeller_over_skia"] = impeller / skia if skia else None
    return result


def self_test() -> None:
    frames = [
        {"build_us": 1_000, "raster_us": 1_000},
        {"build_us": 2_000, "raster_us": 2_000},
        {"build_us": 3_000, "raster_us": 3_000},
        {"build_us": 20_000, "raster_us": 20_000},
    ]
    got = summarize(frames)
    assert got["frames"] == 4
    assert got["build_p50_ms"] == 2.5
    assert got["raster_p50_ms"] == 2.5
    assert math.isclose(got["raster_p90_ms"], 14.9)
    assert got["frames_over_16_7ms_percent"] == 25.0
    try:
        analyze(pathlib.Path("/dev/null"), {})
    except (ValueError, json.JSONDecodeError):
        pass
    else:
        raise AssertionError("invalid input was accepted")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=pathlib.Path)
    parser.add_argument("--runtime-log", action="append", metavar="BACKEND=PATH", default=[])
    parser.add_argument("--passes", type=int, default=3)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        print("analyze_frames self-test passed")
        return 0
    logs: dict[str, pathlib.Path] = {}
    for item in args.runtime_log:
        try:
            backend, log_path = item.split("=", 1)
        except ValueError as exc:
            raise SystemExit(f"invalid --runtime-log {item!r}; use BACKEND=PATH") from exc
        if backend in logs:
            raise SystemExit(f"duplicate runtime log for {backend}")
        logs[backend] = pathlib.Path(log_path)
    try:
        print(json.dumps(analyze(args.results, logs, args.passes), indent=2, sort_keys=True))
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"analyze_frames: rejected input: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
