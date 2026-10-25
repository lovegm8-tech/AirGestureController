#!/usr/bin/env python3
"""Reproducible WAV audio-window benchmark (stdlib only).

Does NOT run SenseVoice or evaluate transcription quality. It compares proposed
segmentation windows against human-annotated speech intervals using the same WAV.
Input mono 16-bit PCM WAV and reference JSON:
{"speech_intervals_ms":[[100,1400],[1700,3000]]}
All recordings stay local; do not upload recordings to a public repository.
"""
import argparse
import json
import struct
import wave
from pathlib import Path


def load_wav(path):
    with wave.open(str(path), "rb") as f:
        if f.getnchannels() != 1 or f.getsampwidth() != 2 or f.getcomptype() != "NONE":
            raise ValueError("Expected mono 16-bit PCM WAV")
        rate = f.getframerate()
        samples = struct.unpack("<" + "h" * f.getnframes(), f.readframes(f.getnframes()))
    return rate, samples


def energy_windows(rate, samples, frame_ms=20, threshold=700, silence_ms=500,
                   min_speech_ms=800, max_window_ms=3000):
    if not (rate > 0 and frame_ms > 0 and threshold >= 0 and silence_ms > 0 and min_speech_ms >= 0 and max_window_ms > 0):
        raise ValueError("Invalid segmenter parameters")
    size = max(1, rate * frame_ms // 1000)
    active_start = None
    last_voice_end = 0
    out = []
    for offset in range(0, len(samples), size):
        frame = samples[offset:offset + size]
        energy = sum(abs(v) for v in frame) / len(frame)
        start_ms = offset * 1000 // rate
        end_ms = min(len(samples), offset + len(frame)) * 1000 // rate
        if energy >= threshold:
            if active_start is None:
                active_start = start_ms
            last_voice_end = end_ms
        if active_start is None:
            continue
        enough_silence = end_ms - last_voice_end >= silence_ms
        maxed = end_ms - active_start >= max_window_ms
        if enough_silence or maxed:
            if last_voice_end - active_start >= min_speech_ms:
                out.append([active_start, end_ms if maxed else last_voice_end])
            active_start = None
    if active_start is not None and last_voice_end - active_start >= min_speech_ms:
        out.append([active_start, last_voice_end])
    return out


def interval_overlap(a, b):
    return max(0, min(a[1], b[1]) - max(a[0], b[0]))


def assess(predicted, reference):
    if any(a < 0 or b <= a for a, b in reference):
        raise ValueError("Invalid reference speech intervals")
    reference_ms = sum(b - a for a, b in reference)
    covered_ms = sum(sum(interval_overlap(r, p) for p in predicted) for r in reference)
    fragmented_intervals = sum(sum(interval_overlap(r, p) > 0 for p in predicted) > 1 for r in reference)
    missed = max(0, reference_ms - covered_ms)
    return {"windows": predicted, "window_count": len(predicted),
            "reference_intervals": len(reference),
            "reference_speech_ms": reference_ms,
            "uncovered_speech_ms": missed,
            "split_reference_intervals": fragmented_intervals}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("wav", type=Path)
    p.add_argument("reference", type=Path)
    p.add_argument("--threshold", type=int, default=700)
    p.add_argument("--silence-ms", type=int, default=500)
    p.add_argument("--min-speech-ms", type=int, default=800)
    p.add_argument("--max-window-ms", type=int, default=3000)
    args = p.parse_args()
    rate, samples = load_wav(args.wav)
    ref = json.loads(args.reference.read_text(encoding="utf-8"))["speech_intervals_ms"]
    windows = energy_windows(rate, samples, threshold=args.threshold,
                             silence_ms=args.silence_ms,
                             min_speech_ms=args.min_speech_ms,
                             max_window_ms=args.max_window_ms)
    print(json.dumps(assess(windows, ref), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
