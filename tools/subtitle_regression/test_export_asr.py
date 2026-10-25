#!/usr/bin/env python3
from export_asr import export

sample = """t+100ms [ASR 片語 #1 預覽] we ar
t+200ms [ASR 片語 #1 final] we are
t+400ms [ASR 片語 #2 final] testing
t+450ms [ASR 片語 #1 final] we are
t+600ms [ML Kit 語音 #2（30ms）] testing → 測試
"""
result = export(sample, "fixed-audio-001")
assert result["hypothesis"] == "we are testing", result
assert result["utterance_count"] == 2, result
assert result["utterance_ids"] == [1, 2], result
assert result["recording_id"] == "fixed-audio-001"
assert export("t+3s [ASR 片語 #5 final] Hello world", "old")["hypothesis"] == "Hello world"
assert export("t+3ms [ASR 片語 #5 預覽] Hello", "none")["utterance_count"] == 0
print("Diagnostic ASR export tests passed")
