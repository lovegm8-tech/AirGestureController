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
The current app keeps only the most recent 60 trace events in memory and uses
**one-second timestamps**, so imported traces can check segmentation and caption
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
