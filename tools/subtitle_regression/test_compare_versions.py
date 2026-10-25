#!/usr/bin/env python3
"""Deterministic regression scenarios for actual v1.0.43/44 chunkers."""
import math

from compare_versions import compare_versions
from legacy_v143 import legacy_v143_windows
from legacy_v144 import legacy_v144_windows

RATE = 16000


def waveform(duration_ms, intervals):
    samples = []
    for i in range(duration_ms * 16):
        t = i / 16
        voiced = any(a <= t < b for a, b in intervals)
        samples.append(int(3000 * math.sin(2 * math.pi * 220 * i / RATE)) if voiced else 0)
    return samples


# The previous version decodes 2.8-second fixed chunks, regardless of
# silence; v1.0.44's silence gate creates a different set of boundaries.
audio = waveform(6000, [[0, 1300], [2000, 3500], [4300, 5500]])
old = legacy_v143_windows(RATE, audio)
new = legacy_v144_windows(RATE, audio)
assert old == [[0, 2800], [2800, 5600]], old
assert old != new, (old, new)
report = compare_versions(RATE, audio, [[0, 1300], [2000, 3500], [4300, 5500]])
assert report["v1.0.43"]["window_count"] == 2
assert report["v1.0.44"]["window_count"] >= 1

# At the end of short playback, fixed v1.0.43 never decodes a partial
# 2.8-second buffer; v1.0.44 can flush at a 0.5-second silence.
short = waveform(2400, [[0, 1100]])
assert legacy_v143_windows(RATE, short) == []
assert len(legacy_v144_windows(RATE, short)) == 1

# Silence-only 3s window is decoded in v1.0.43 but discarded by v1.0.44.
silence = [0] * (3 * RATE)
assert legacy_v143_windows(RATE, silence) == [[0, 2800]]
assert legacy_v144_windows(RATE, silence) == []
print({"v143": old, "v144": new, "comparison": report})
