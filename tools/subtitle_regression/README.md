# Subtitle regression harness (phase 1)

Run: `python3 tools/subtitle_regression/replay.py tools/subtitle_regression/fixtures/*.json`

This deterministic event replay checks **caption lifecycle only** (stale AI rejection, two-row rotation, repeated updates). It is not connected to the Android runtime yet and cannot prove SenseVoice accuracy, speech segmentation, ML Kit quality, latency or thermal stability.

Next steps before using this as an APK release gate:
1. Capture consented local test audio/video and manually verified reference transcripts/translations; do not commit private recordings to a public repository.
2. Export timestamped ASR/translation/AI/caption events from the app and replay real traces, comparing v1.0.43 with candidate versions.
3. Measure word error rate (WER), phrase split count, median and P95 subtitle delay, revision frequency, repeated/expired captions.
4. Add thresholds to CI and run on-device playback capture/thermal checks before signed APK release.
5. Preserve the v1.0.43 baseline until measured improvements are demonstrated.
