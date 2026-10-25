#!/usr/bin/env python3
"""Score paired ASR transcripts against the same human-verified reference.

Inputs are local JSON, not uploaded recordings. Each file:
{"language":"en","reference":"...","hypothesis":"..."}
For Chinese/Japanese, specify tokenization="character" explicitly.
WER/CER are recognition metrics only, not translation or audio latency.
"""
import argparse
import json
import re
from pathlib import Path
from validate_coverage import validate


def tokenize(text, mode):
    if mode == "character":
        return [c for c in text.casefold() if not c.isspace() and not re.match(r"\W", c)]
    if mode == "word":
        return re.findall(r"[^\W_]+(?:'[^\W_]+)*", text.casefold(), re.UNICODE)
    raise ValueError("tokenization must be word or character")


def edit_counts(reference, hypothesis):
    # (edit distance, substitutions, deletions, insertions), stable tie breaking.
    prev = [(i, 0, i, 0) for i in range(len(hypothesis) + 1)]
    for i, word in enumerate(reference, 1):
        cur = [(i, 0, i, 0)]
        for j, guessed in enumerate(hypothesis, 1):
            if word == guessed:
                diagonal = prev[j - 1]
            else:
                d = prev[j - 1]
                diagonal = (d[0] + 1, d[1] + 1, d[2], d[3])
            d = prev[j]
            deletion = (d[0] + 1, d[1], d[2] + 1, d[3])
            ins = cur[j - 1]
            insertion = (ins[0] + 1, ins[1], ins[2], ins[3] + 1)
            cur.append(min((diagonal, deletion, insertion)))
        prev = cur
    return prev[-1]


def score(reference, hypothesis, mode):
    ref = tokenize(reference, mode)
    hyp = tokenize(hypothesis, mode)
    if not ref:
        raise ValueError("Reference transcript has no tokens")
    distance, substitutions, deletions, insertions = edit_counts(ref, hyp)
    return {"reference_tokens": len(ref), "hypothesis_tokens": len(hyp),
            "substitutions": substitutions, "deletions": deletions,
            "insertions": insertions, "errors": distance,
            "error_rate": round(distance / len(ref), 6)}


def evaluate(reference, baseline, candidate, mode):
    old = score(reference, baseline, mode)
    new = score(reference, candidate, mode)
    return {"metric": "CER" if mode == "character" else "WER",
            "baseline": old, "candidate": new,
            "non_regression": new["errors"] <= old["errors"],
            "error_reduction": old["errors"] - new["errors"],
            "warning": "Requires the SAME human-verified reference and audio; does not measure translation or latency"}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("reference", type=Path, help="JSON containing reference and tokenization")
    p.add_argument("baseline", type=Path, help="JSON containing hypothesis")
    p.add_argument("candidate", type=Path, help="JSON containing hypothesis")
    p.add_argument("--fail-on-regression", action="store_true")
    args = p.parse_args()
    reference = json.loads(args.reference.read_text(encoding="utf-8"))
    old = json.loads(args.baseline.read_text(encoding="utf-8"))
    new = json.loads(args.candidate.read_text(encoding="utf-8"))
    for label, item in (("baseline", old), ("candidate", new)):
        if item.get("recording_id") != reference.get("recording_id"):
            raise ValueError(f"{label} recording_id mismatch")
    if old.get("diagnostic_only") or new.get("diagnostic_only"):
        coverage = validate(reference, old, new)
        if not coverage["pass"]:
            raise SystemExit("Diagnostic coverage gate failed: " + "; ".join(coverage["problems"]))
    report = evaluate(reference["reference"], old["hypothesis"], new["hypothesis"],
                      reference.get("tokenization", "word"))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.fail_on_regression and not report["non_regression"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
