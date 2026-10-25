#!/usr/bin/env python3
from analyze_tail import compare, tail_report

rate = 16000
samples = [0] * (rate * 6)
for i in range(rate * 5, len(samples)):
    samples[i] = 4000
result = compare(rate, samples)
assert result["v1.0.43"]["completed_windows"] == 2, result
assert result["v1.0.43"]["tail_duration_ms"] == 400, result
assert result["v1.0.43"]["tail_samples_above_650"] == 6400, result
assert result["v1.0.44"]["tail_duration_ms"] == 0, result
assert tail_report(rate, [], [])["tail_samples"] == 0
print("Tail analyzer tests passed")
