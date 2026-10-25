#!/usr/bin/env python3
"""Generate deterministic artificial WAV solely to verify benchmark mechanics."""
import json
import math
import struct
import tempfile
import wave
from pathlib import Path

from audio_windows import load_wav, energy_windows, assess

rate = 16000
duration_ms = 4000
samples = []
for i in range(rate * duration_ms // 1000):
    t = i * 1000 / rate
    voiced = (200 <= t < 1400) or (1800 <= t < 3100)
    samples.append(int(2500 * math.sin(2 * math.pi * 220 * i / rate)) if voiced else 0)

with tempfile.TemporaryDirectory() as directory:
    path = Path(directory) / "synthetic.wav"
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(rate)
        f.writeframes(struct.pack("<" + "h" * len(samples), *samples))
    loaded_rate, loaded_samples = load_wav(path)
    assert loaded_rate == rate
    reference = [[200, 1400], [1800, 3100]]
    result = assess(energy_windows(loaded_rate, loaded_samples, threshold=700,
                                  silence_ms=300, min_speech_ms=800), reference)
    assert result["window_count"] == 2, result
    assert result["split_reference_intervals"] == 0, result
    assert result["uncovered_speech_ms"] <= 40, result
    print(json.dumps(result, ensure_ascii=False))
