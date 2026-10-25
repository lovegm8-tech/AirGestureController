#!/usr/bin/env python3
"""Reproduce the v1.0.44 SenseVoice chunk-boundary heuristic for comparison.

Faithful to SubtitleSenseVoiceSpeech.kt: per-sample abs threshold 650,
quiet >= 8000 samples, >= 12800 total, max 48000, voiced >= 1600.
Simulates the actual 3200-byte capture blocks (1600 samples).
No ASR inference occurs here.
"""


def legacy_v144_windows(rate, samples):
    if rate != 16000:
        raise ValueError("v1.0.44 capture runs at 16 kHz")
    out = []
    start = 0
    quiet = 0
    voiced = 0
    audio_count = 0
    for offset in range(0, len(samples), 1600):
        block = samples[offset:offset + 1600]
        for sample in block:
            audio_count += 1
            if abs(sample) > 650:
                voiced += 1
                quiet = 0
            else:
                quiet += 1
        natural_pause = quiet >= 8000 and audio_count >= 12800
        max_window = audio_count >= 48000
        if not natural_pause and not max_window:
            continue
        if voiced >= 1600:
            out.append([start * 1000 // rate, (offset + len(block)) * 1000 // rate])
        start = offset + len(block)
        quiet = 0
        voiced = 0
        audio_count = 0
    return out
