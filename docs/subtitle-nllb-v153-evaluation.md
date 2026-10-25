# v1.0.53 — Android offline translation evaluation

Status: research and integration baseline; NLLB is NOT yet wired into the subtitle service or shipped as an APK.

## Goal
Replace ML Kit as the authoritative English/Japanese -> Traditional Chinese translator only after an offline Android candidate beats the current ML Kit baseline on accuracy without unacceptable latency or memory pressure. Preserve all existing gesture, NFC, Nearby, OCR and dual-ASR behavior.

## Candidates
1. ONNX Runtime Mobile with a quantized NLLB-200 distilled 600M encoder-decoder model, tokenizer and language tags (eng_Latn, jpn_Jpan, zho_Hans). Requires validation of compatible ONNX decoder generation, tokenizer parity, Android ABI and CPU/RAM usage. Output Chinese must be converted to Traditional Chinese.
2. Bergamot/Marian compatible bilingual translation models as fallback. Verify Japanese-to-Chinese availability and any English pivot.
3. ML Kit remains operational fallback until a new engine passes tests.

## Licensing
NLLB-200 distilled 600M model weights are CC-BY-NC 4.0: no commercial distribution without separate permission. Independently verify source code, tokenizer, ONNX runtime and model conversion licenses. Do not copy third-party Android app code without license review.

## Test corpus
- ja: 火をつけます。 => 點火。 (not 打火機)
- ja: どこに行きますか？ => 你要去哪裡？ (not 你去哪兒了？)
- ja: 学校に行きます。 => 我要去學校。
- ja: 気をつけてね。 => 小心一點。
- en: You have to suffer. => 你必須受苦。
- en: Have to struggle. => 必須努力奮鬥／必須掙扎（依語境）
- en: I wish upon you greatness. => 祝你成就非凡。
- Incomplete ASR: And youve to youve. => defer; do not hallucinate.

## Acceptance gates
- Side-by-side translation logs: raw ASR, stabilized sentence, model, Chinese output, queue time, model inference time, peak RSS.
- Accuracy judged on at least 50 English and 50 Japanese real utterances, not just isolated examples.
- Run with dual ASR and overlay enabled on S26 Ultra, record thermal and memory impact.
- Preserve signed update identity and increment versionCode when an APK is actually produced.
- If NLLB fails quality/performance/licensing gates, retain ML Kit and evaluate Bergamot rather than shipping a regression.
