#!/usr/bin/env python3
"""Deterministic integration check for same-audio algorithm comparison."""
import math
from compare_audio import compare_audio

rate = 16000
samples = []
for i in range(64000):
    ms = i * 1000 / rate
    active = (200 <= ms < 1400) or (1900 <= ms < 3100)
    samples.append(int(3000 * math.sin(2 * math.pi * 220 * i / rate)) if active else 0)
reference = [[200, 1400], [1900, 3100]]
report = compare_audio(rate, samples, reference)
assert report["baseline"]["reference_speech_ms"] == 2400
assert report["candidate"]["reference_speech_ms"] == 2400
assert isinstance(report["pass"], bool)
assert set(report["non_regression"]) == {"uncovered_speech_ms", "split_reference_intervals"}
print(report)
