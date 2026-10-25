#!/usr/bin/env python3
"""Synthetic tests demonstrate differences, not real-world model accuracy."""
import math
from audio_windows import energy_windows, assess
from legacy_v144 import legacy_v144_windows

RATE = 16000

def tone(duration_ms, amplitude):
    count = duration_ms * RATE // 1000
    return [int(amplitude * math.sin(2 * math.pi * 220 * i / RATE)) for i in range(count)]

# 1.4s speech, 0.7s silence, 1.4s speech. v1.0.44 should
# stop after a long silence and may split across its hard 3s limit.
samples = tone(1400, 3000) + [0] * (700 * RATE // 1000) + tone(1400, 3000)
legacy = legacy_v144_windows(RATE, samples)
proposed = energy_windows(RATE, samples, threshold=700, silence_ms=500,
                          min_speech_ms=800, max_window_ms=3000)
assert len(legacy) >= 1
assert len(proposed) >= 1

# v1.0.44 sample threshold sees high-frequency spikes as speech,
# whereas frame-average energy can reject sufficiently sparse spikes.
spikes = [2000 if i % 40 == 0 else 0 for i in range(3 * RATE)]
legacy_spikes = legacy_v144_windows(RATE, spikes)
proposed_spikes = energy_windows(RATE, spikes, threshold=700)
assert legacy_spikes == [], legacy_spikes
assert proposed_spikes == [], proposed_spikes

# Both algorithms are explicitly bounded; neither proves ASR quality.
print({"legacy_windows": legacy, "frame_energy_windows": proposed,
       "legacy_spike_windows": legacy_spikes})
