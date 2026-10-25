#!/usr/bin/env python3
"""Exact v1.0.43 SenseVoice fixed 2.8s chunks (from PR #24 diff).

Android consumes 3200-byte capture reads: 1600 PCM samples per block.
When audio.size >= 44800, it decodes the entire accumulated block and
clears it. No amplitude gate, no silence endpoint, no overlap.
Only complete windows are decoded; remaining audio is discarded on stop.
"""


def legacy_v143_windows(rate, samples):
    if rate != 16000:
        raise ValueError("v1.0.43 capture runs at 16 kHz")
    windows = []
    start = 0
    buffered = 0
    for offset in range(0, len(samples), 1600):
        block = samples[offset:offset + 1600]
        buffered += len(block)
        if buffered >= 44800:
            end = offset + len(block)
            windows.append([start * 1000 // rate, end * 1000 // rate])
            start = end
            buffered = 0
    return windows
