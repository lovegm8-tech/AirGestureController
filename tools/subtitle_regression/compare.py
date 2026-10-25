#!/usr/bin/env python3
"""Compare timestamped ASR/translation traces across versions.

Input JSON: {"events":[{"timestamp_ms":100,"kind":"asr","id":1,"text":"..."},
{"timestamp_ms":400,"kind":"translation","id":1,"text":"..."}]}.
These are trace-level indicators, NOT speech-recognition accuracy or actual
audio-capture-to-display latency. Supply the same recording to both engines.
"""
import argparse
import json
from pathlib import Path
from replay import replay


def stats(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    result = replay(data["events"])
    return result["metrics"]


def compare(base, candidate):
    b, c = stats(base), stats(candidate)
    checks = {
        "short_asr_fragments": c["short_asr_fragments"] <= b["short_asr_fragments"],
        "flicker_changes": c["flicker_changes"] <= b["flicker_changes"],
        "translation_latency_p95_ms": c["translation_latency_p95_ms"] <= b["translation_latency_p95_ms"],
    }
    return {"baseline": b, "candidate": c, "non_regression": checks, "pass": all(checks.values())}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline")
    parser.add_argument("candidate")
    args = parser.parse_args()
    report = compare(args.baseline, args.candidate)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not report["pass"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
