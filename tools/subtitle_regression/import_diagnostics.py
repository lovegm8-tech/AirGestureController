#!/usr/bin/env python3
"""Convert copied Android SubtitlePipelineTrace diagnostic text to replay JSON.

Source: in-app diagnostic text containing t+123s [ASR 片語 #5 final] ...
Timestamp resolution is only one second; this is unsuitable for latency benchmarks.
"""
import argparse
import json
import re
from pathlib import Path

LINE = re.compile(r"^t\+(\d+)s \[([^]]+)\] (.*)$")
ASR = re.compile(r"^ASR 片語 #(\d+) (?:final|預覽)$")
MLKIT = re.compile(r"^ML Kit 語音 #(\d+)")
QWEN = re.compile(r"^Qwen 語音 #(\d+)$")


def convert(text):
    events = []
    translated = {}\n    precise = True
    for line in text.splitlines():
        match = LINE.match(line.strip())
        if not match:
            continue
        amount, unit, stage, payload = match.groups()
        timestamp_ms = int(amount) * (1000 if unit == "s" else 1)\n        if unit == "s":\n            precise = False
        a = ASR.match(stage)
        m = MLKIT.match(stage)
        q = QWEN.match(stage)
        if a:
            events.append({"timestamp_ms": timestamp_ms, "kind": "asr",
                           "id": int(a.group(1)), "text": payload})
        elif m:
            translation = payload.split(" → ", 1)
            if len(translation) != 2:
                continue
            key = int(m.group(1))
            translated[key] = translation[1]
            events.append({"timestamp_ms": timestamp_ms, "kind": "translation",
                           "id": key, "text": translation[1]})
        elif q:
            key = int(q.group(1))
            events.append({"timestamp_ms": timestamp_ms, "kind": "ai",
                           "id": key, "expected": translated.get(key, ""),
                           "text": payload})
    return {"events": events, "metadata": {
        "source": "in-app diagnostic snapshot",
        "timestamp_resolution_ms": 1 if precise else 1000,
        "limitations": "bounded in-memory trace; no true audio-to-caption latency"
    }}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("diagnostic_text", type=Path)
    parser.add_argument("output_json", type=Path)
    args = parser.parse_args()
    result = convert(args.diagnostic_text.read_text(encoding="utf-8"))
    args.output_json.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Converted {len(result['events'])} events to {args.output_json}")


if __name__ == "__main__":
    main()
