#!/usr/bin/env python3
"""Analyze unprocessed tail after each *actual* Android segmentation strategy.

The analysis is local and never uploads the WAV. Tail audio may be speech,
music, noise, or silence: energy cannot distinguish those categories.
"""
import argparse
import json
from pathlib import Path
from audio_windows import load_wav
from legacy_v143 import legacy_v143_windows
from legacy_v144 import legacy_v144_windows


def tail_report(rate, samples, windows, threshold=650):
    end_ms = windows[-1][1] if windows else 0
    start = end_ms * rate // 1000
    remaining = samples[start:]
    duration_ms = len(remaining) * 1000 / rate
    voiced = sum(abs(x) > threshold for x in remaining)
    return {"completed_windows": len(windows), "last_window_end_ms": end_ms,
            "tail_duration_ms": round(duration_ms, 2),
            "tail_mean_abs_amplitude": round(sum(abs(x) for x in remaining) / len(remaining), 2) if remaining else 0,
            "tail_samples_above_650": voiced,
            "tail_samples": len(remaining),
            "warning": "High energy is NOT proof of speech or ASR omissions"}


def compare(rate, samples):
    return {
        "audio_duration_ms": round(len(samples) * 1000 / rate, 2),
        "v1.0.43": tail_report(rate, samples, legacy_v143_windows(rate, samples)),
        "v1.0.44": tail_report(rate, samples, legacy_v144_windows(rate, samples)),
        "limitation": "Audio segmentation only; no model decoding; stopping capture discards buffered tail"
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("wav", type=Path, help="Local mono 16kHz signed 16-bit PCM WAV")
    args = parser.parse_args()
    rate, samples = load_wav(args.wav)
    print(json.dumps(compare(rate, samples), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
