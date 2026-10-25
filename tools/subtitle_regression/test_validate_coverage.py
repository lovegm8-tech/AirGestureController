#!/usr/bin/env python3
from validate_coverage import validate

ref = {"recording_id": "audio-1"}
def trace(ids, recording="audio-1"):
    return {"recording_id": recording, "hypothesis": "hello world",
            "utterance_ids": ids, "utterance_count": len(ids)}

assert validate(ref, trace([1, 2]), trace([1, 2, 3]))["pass"]
assert not validate(ref, trace([2, 3]), trace([1]))["pass"]
assert not validate(ref, trace([1, 3]), trace([1]))["pass"]
assert not validate(ref, trace([1, 1]), trace([1]))["pass"]
assert not validate(ref, trace([1]), trace([1], "other"))["pass"]
assert not validate(ref, trace([1]), trace([]))["pass"]
print("Coverage gate tests passed")
