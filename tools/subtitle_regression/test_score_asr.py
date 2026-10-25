#!/usr/bin/env python3
from score_asr import evaluate, score, tokenize, edit_counts

assert tokenize("Hello, WORLD! It's OK.", "word") == ["hello", "world", "it's", "ok"]
assert tokenize("你好，世界！", "character") == ["你", "好", "世", "界"]
assert score("one two three", "one too three", "word")["substitutions"] == 1
assert score("one two three", "one three", "word")["deletions"] == 1
assert score("one two three", "one two two three", "word")["insertions"] == 1
assert score("one two", "", "word")["error_rate"] == 1
assert score("你好世界", "你好世", "character")["deletions"] == 1
report = evaluate("we are testing this system", "we testing system",
                  "we are testing this system", "word")
assert report["non_regression"]
assert report["error_reduction"] == 2
worse = evaluate("one two three", "one two three", "one too", "word")
assert not worse["non_regression"]
print("ASR scoring unit tests passed")
