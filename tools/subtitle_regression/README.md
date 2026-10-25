# Subtitle regression harness (phase 1)

Run: `python3 tools/subtitle_regression/replay.py tools/subtitle_regression/fixtures/*.json`

This deterministic event replay checks **caption lifecycle only** (stale AI rejection, two-row rotation, repeated updates). It is not connected to the Android runtime yet and cannot prove SenseVoice accuracy, speech segmentation, ML Kit quality, latency or thermal stability.

Next steps before using this as an APK release gate:
1. Capture consented local test audio/video and manually verified reference transcripts/translations; do not commit private recordings to a public repository.
2. Export timestamped ASR/translation/AI/caption events from the app and replay real traces, comparing v1.0.43 with candidate versions.
3. Measure word error rate (WER), phrase split count, median and P95 subtitle delay, revision frequency, repeated/expired captions.
4. Add thresholds to CI and run on-device playback capture/thermal checks before signed APK release.
5. Preserve the v1.0.43 baseline until measured improvements are demonstrated.

## Import real device diagnostic snapshots (without changing APK)

Copy the in-app subtitle diagnostics text into a local UTF-8 file, then run:

```bash
python3 tools/subtitle_regression/import_diagnostics.py diagnosis.txt trace.json
python3 tools/subtitle_regression/replay.py trace.json
python3 tools/subtitle_regression/compare.py baseline.json candidate.json
```

Use the **same video/audio source and settings** for baseline and candidate.
Do not publish recordings or transcriptions containing private information.
Older builds keep only the most recent 60 events with one-second timestamps;
the diagnostic instrumentation branch keeps up to 300 events with millisecond timestamps, so imported traces can check segmentation and caption
lifecycle, but latency comparisons are coarse and cannot measure end-to-end
speech latency. The comparator currently treats coarse timestamps as if they
were precise; do **not** use its latency gate for release decisions with these
imports. This importer is an interim bridge, not a complete real-audio harness.

## Same-audio endpoint comparison (local WAV)

Prepare a **mono 16-bit PCM, 16 kHz WAV** from a recording you are allowed to use. Create a local JSON file:

```json
{"speech_intervals_ms": [[200, 1400], [1900, 3100]]}
```

The intervals must be manually annotated against that recording. Run:

```bash
python3 tools/subtitle_regression/compare_audio.py recording.wav intervals.json
```

The report includes uncovered speech and how often a reference speech interval is
split into multiple windows. This compares the **v1.0.44 endpoint heuristic**
with an experimental frame-energy heuristic. It does **not** reproduce v1.0.43,
run SenseVoice inference, or measure WER/translation quality. A passing report
is not sufficient for an APK release. Keep private audio and transcripts off
public GitHub; use locally sourced files only.

## ASR accuracy gate (WER/CER)

This does not execute SenseVoice. It evaluates text emitted by two ASR runs
on the **same** consented recording against one verified transcript.

```json
// reference.json (remove this comment before saving)
{"recording_id":"sample-001","tokenization":"word","reference":"we are testing the subtitle engine"}
```

```json
// baseline.json
{"recording_id":"sample-001","hypothesis":"we testing subtitle engine"}
```

```json
// candidate.json
{"recording_id":"sample-001","hypothesis":"we are testing the subtitle engine"}
```

```bash
python3 tools/subtitle_regression/score_asr.py reference.json baseline.json candidate.json --fail-on-regression
```

Use `"tokenization":"character"` for Chinese/Japanese character error rate
(CER); English defaults to word error rate (WER). This gate does not measure
translation correctness, endpoint timing, or device thermals. **No real model
outputs have yet been collected or scored.**

## Bridge Android diagnostics to WER/CER

When a tested build includes `ASR 片語 #N final` in its diagnostic panel,
copy the diagnostic text to a local file and run:

```bash
python3 tools/subtitle_regression/export_asr.py baseline-diagnostics.txt baseline.json --recording-id fixed-001
python3 tools/subtitle_regression/export_asr.py candidate-diagnostics.txt candidate.json --recording-id fixed-001
python3 tools/subtitle_regression/score_asr.py reference.json baseline.json candidate.json --fail-on-regression
```

`reference.json` must contain `recording_id`, `reference`, and `tokenization`.
Both runs must play the same audio from the beginning with matching language and
audio settings. The exporter uses **final** ASR utterances only, ordered by
their timestamps, and deduplicates repeated IDs. This is a convenience bridge:
bounded diagnostic snapshots may omit earlier speech; verify coverage before
interpreting WER/CER. Older versions without this diagnostic label cannot
be scored with this exporter. No APK is built by this workflow.
