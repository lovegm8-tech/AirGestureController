# v1.0.53 — Android offline translation engine evaluation

Status: research branch; **NLLB is not integrated or runnable in Floating Control yet**.

## Candidate verified
- Upstream: https://github.com/QuantumBreakz/Offline-Translator (Apache-2.0 application code, verify NOTICE and dependencies before reuse).
- This repository identifies itself as a fork/rebrand of niedev/RTranslator ("SL").
- Uses ONNX Runtime, SentencePiece, NLLB-Distilled-600M with KV cache, partly INT8 quantized and split graphs.
- Upstream reports ~1.3 GB translation-model RAM and ~2 seconds for 75 output tokens **on their test configuration**, not an S26 Ultra benchmark.
- NLLB model weights have CC-BY-NC-4.0/non-commercial restrictions distinct from the application code license.
- Upstream README also describes an upcoming change away from NLLB to Bergamot, MADLAD and HY-MT. Do not assume NLLB is best without comparative tests.

## Required integration checkpoints
1. Identify upstream model assets, checksums, ONNX graph input/output names, SentencePiece and language tags.
2. Reuse only license-compatible application code with attribution; keep model downloads optional, never embed multi-GB weights in the APK.
3. Implement isolated translation-engine interface and explicit install/ready/error states; do not claim installed from a downloaded archive alone.
4. Benchmark EN->ZH and JA->ZH on fixed test sentences, including 火をつけます and fragmented ASR output; measure accuracy, latency, RAM and thermal behavior on S26 Ultra.
5. Only then switch final captions to NLLB; keep ML Kit as fallback and Qwen disabled by default. Preserve existing gesture/NFC/file-transfer behavior and signing key.

## Acceptance criteria
- No regressions in current v1.0.52 workflows.
- Model installation validated using required files and a successful inference smoke test.
- Actual translated caption and diagnostic source identify which engine produced it.
- Native inference failure falls back without crashing or blocking subtitles.
- User-approved license conditions before distributing NLLB weights.
