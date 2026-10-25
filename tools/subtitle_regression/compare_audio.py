#!/usr/bin/env python3
"""Compare v1.0.44 chunking and candidate endpointing on the SAME local WAV.

This evaluates segmentation only, not SenseVoice inference or translation.
No recording is uploaded. Human-annotated speech intervals are required.
"""
import argparse
import json
from pathlib import Path

from audio_windows import assess, energy_windows, load_wav
from legacy_v144 import legacy_v144_windows


def compare_audio(rate, samples, intervals, *, threshold=700, silence_ms=500,
                  min_speech_ms=800, max_window_ms=3000):
    old = assess(legacy_v144_windows(rate, samples), intervals)
    new = assess(energy_windows(rate, samples, threshold=threshold,
                                silence_ms=silence_ms, min_speech_ms=min_speech_ms,
                                max_window_ms=max_window_ms), intervals)
    # A split is a speech reference interval covered by more than one output
    # window. Do not infer ASR accuracy from this count.
    metrics = ("uncovered_speech_ms", "split_reference_intervals")
    checks = {key: new[key] <= old[key] for key in metrics}
    return {
        "baseline": {"algorithm": "v1.0.44", **old},
        "candidate": {"algorithm": "frame-energy-prototype", **new},
        "non_regression": checks,
        "pass": all(checks.values()),
        "warning": "No ASR inference, WER, translation, or end-to-end latency measured"
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument("wav", type=Path)
    p.add_argument("reference", type=Path)
    p.add_argument("--threshold", type=int, default=700)
    p.add_argument("--silence-ms", type=int, default=500)
    p.add_argument("--min-speech-ms", type=int, default=800)
    p.add_argument("--max-window-ms", type=int, default=3000)
    p.add_argument("--fail-on-regression", action="store_true")
    args = p.parse_args()
    rate, samples = load_wav(args.wav)
    reference = json.loads(args.reference.read_text(encoding="utf-8"))
    intervals = reference["speech_intervals_ms"]
    report = compare_audio(rate, samples, intervals,
                           threshold=args.threshold,
                           silence_ms=args.silence_ms,
                           min_speech_ms=args.min_speech_ms,
                           max_window_ms=args.max_window_ms)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.fail_on_regression and not report["pass"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
