#!/usr/bin/env python3
"""Export final ASR utterances from a locally copied Android diagnostic snapshot.

Output is compatible with score_asr.py. It is NOT a recording replay and
cannot recover utterances lost to the app's bounded in-memory trace.
"""
import argparse
import json
import re
from pathlib import Path

LINE = re.compile(r"^t\+(\d+)(ms|s) \[ASR 片語 #(\d+) (final|預覽)\] (.*)$")


def export(text, recording_id):
    finals = {}
    timestamps = {}
    seen_ids = set()
    for line in text.splitlines():
        m = LINE.match(line.strip())
        if not m:
            continue
        amount, unit, number, state, words = m.groups()
        ident = int(number)
        seen_ids.add(ident)
        if state != "final":
            continue
        timestamp = int(amount) * (1000 if unit == "s" else 1)
        if ident not in finals or timestamp >= timestamps[ident]:
            finals[ident] = words.strip()
            timestamps[ident] = timestamp
    ordered = sorted(finals, key=lambda ident: (timestamps[ident], ident))
    return {"recording_id": recording_id,
            "hypothesis": " ".join(finals[ident] for ident in ordered if finals[ident]),
            "utterance_count": len(ordered),
            "utterance_ids": ordered,
            "diagnostic_only": True,
            "warning": "Bounded in-memory snapshot; missing earlier utterances cannot be recovered"}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("diagnostic_text", type=Path)
    p.add_argument("output_json", type=Path)
    p.add_argument("--recording-id", required=True)
    args = p.parse_args()
    result = export(args.diagnostic_text.read_text(encoding="utf-8"), args.recording_id)
    if not result["utterance_count"]:
        raise SystemExit("No final ASR utterances found; diagnostic snapshot may not contain final events")
    args.output_json.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Exported {result['utterance_count']} final ASR utterances")


if __name__ == "__main__":
    main()
