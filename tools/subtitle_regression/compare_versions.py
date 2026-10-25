#!/usr/bin/env python3
"""Compare actual v1.0.43 and v1.0.44 chunking against the same local WAV.

This models chunk boundaries, NOT neural recognition, translation, or runtime
latency. The v1.0.43 algorithm was verified from PR #24's actual Kotlin diff.
"""
import argparse
import json
from pathlib import Path

from audio_windows import assess, load_wav
from legacy_v143 import legacy_v143_windows
from legacy_v144 import legacy_v144_windows


def compare_versions(rate, samples, reference):
    before = assess(legacy_v143_windows(rate, samples), reference)
    after = assess(legacy_v144_windows(rate, samples), reference)
    metrics = ("uncovered_speech_ms", "split_reference_intervals")
    return {
        "v1.0.43": before, "v1.0.44": after,
        "deltas_v144_minus_v143": {key: after[key] - before[key] for key in metrics},
        "v144_non_regression": all(after[key] <= before[key] for key in metrics),
        "limitations": "Segmentation-only: no SenseVoice inference or translation; manual reference intervals required"
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("wav", type=Path)
    parser.add_argument("reference", type=Path)
    parser.add_argument("--fail-on-regression", action="store_true")
    args = parser.parse_args()
    rate, samples = load_wav(args.wav)
    reference = json.loads(args.reference.read_text(encoding="utf-8"))["speech_intervals_ms"]
    result = compare_versions(rate, samples, reference)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.fail_on_regression and not result["v144_non_regression"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
