#!/usr/bin/env python3
"""Validate diagnostic coverage before using a transcript for WER/CER.

A bounded snapshot may contain only the tail of a recording. This gate refuses
comparisons with missing IDs, no final utterances, or nonmatching recording IDs.
A passing gate is necessary, NOT proof that all speech was captured.
"""
import argparse
import json
from pathlib import Path


def validate(reference, baseline, candidate):
    problems = []
    recording = reference.get("recording_id")
    if not recording:
        problems.append("reference missing recording_id")
    for label, item in (("baseline", baseline), ("candidate", candidate)):
        if item.get("recording_id") != recording:
            problems.append(f"{label}: recording_id mismatch")
        if not item.get("hypothesis", "").strip():
            problems.append(f"{label}: empty final ASR transcript")
        ids = item.get("utterance_ids")
        if not isinstance(ids, list) or not ids:
            problems.append(f"{label}: missing final utterance IDs")
            continue
        if any(not isinstance(i, int) or isinstance(i, bool) or i <= 0 for i in ids):
            problems.append(f"{label}: invalid utterance IDs")
        if len(set(ids)) != len(ids):
            problems.append(f"{label}: duplicate utterance IDs")
        if ids != sorted(ids):
            problems.append(f"{label}: out-of-order utterance IDs")
        if ids[0] != 1 or ids != list(range(1, ids[-1] + 1)):
            problems.append(f"{label}: incomplete utterance ID sequence")
        if item.get("utterance_count") != len(ids):
            problems.append(f"{label}: utterance count mismatch")
    # Different utterance counts are EXPECTED if chunking changed. Do not require
    # counts to match across versions.
    return {"pass": not problems, "problems": problems,
            "warning": "ID continuity cannot prove complete audio coverage; manually verify recording start/end and reference alignment"}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("reference", type=Path)
    p.add_argument("baseline", type=Path)
    p.add_argument("candidate", type=Path)
    args = p.parse_args()
    data = [json.loads(path.read_text(encoding="utf-8")) for path in
            (args.reference, args.baseline, args.candidate)]
    result = validate(*data)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if not result["pass"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
