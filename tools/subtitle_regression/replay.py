#!/usr/bin/env python3
"""Deterministic subtitle event replay; no Android device or ML model required.

Fixture schema: events with timestamp_ms, kind (asr|translation|ai), id, text,
and optional expected (the current quick translation when kind=ai).
All events are ordered by timestamp_ms. This tests caption lifecycle, not ASR quality.
"""
import argparse
import json
from pathlib import Path


def replay(events):
    rows = []
    latest_id = 0
    metrics = {"stale_rejected": 0, "updates": 0, "rotations": 0, "flicker_changes": 0}
    for e in sorted(events, key=lambda x: x["timestamp_ms"]):
        kind = e["kind"]
        if kind == "asr":
            continue
        key = str(e["id"])
        text = " ".join(e["text"].split())
        if not text:
            continue
        if kind == "translation":
            num = int(e["id"])
            if num < latest_id - 1:
                metrics["stale_rejected"] += 1
                continue
            latest_id = max(latest_id, num)
            matches = [r for r in rows if r["id"] == key]
            if matches:
                if matches[0]["text"] != text:
                    matches[0]["text"] = text
                    metrics["updates"] += 1
                    metrics["flicker_changes"] += 1
            else:
                rows.append({"id": key, "text": text})
                if len(rows) > 2:
                    rows.pop(0)
                    metrics["rotations"] += 1
        elif kind == "ai":
            matches = [r for r in rows if r["id"] == key]
            if not matches or matches[0]["text"] != e.get("expected"):
                metrics["stale_rejected"] += 1
                continue
            if matches[0]["text"] != text:
                matches[0]["text"] = text
                metrics["updates"] += 1
                metrics["flicker_changes"] += 1
        else:
            raise ValueError("unsupported event kind: " + kind)
    return {"rows": rows, "metrics": metrics}


def evaluate(fixture):
    result = replay(fixture["events"])
    expected = fixture.get("expected", {})
    for key, value in expected.items():
        if result[key] != value:
            raise AssertionError(f"{fixture.get('name', 'fixture')}: {key}: {result[key]} != {value}")
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("fixtures", nargs="+", type=Path)
    args = parser.parse_args()
    for path in args.fixtures:
        fixture = json.loads(path.read_text(encoding="utf-8"))
        result = evaluate(fixture)
        print(json.dumps({"fixture": str(path), **result}, ensure_ascii=False))


if __name__ == "__main__":
    main()
