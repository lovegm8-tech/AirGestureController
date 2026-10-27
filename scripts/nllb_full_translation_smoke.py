"""Standalone full NLLB inference probe. No Android libraries or sherpa involved."""
import hashlib
import json
import pathlib
import time
import urllib.request

import numpy as np
import onnxruntime as ort
import sentencepiece as spm

ROOT = pathlib.Path("models")
URL = "https://raw.githubusercontent.com/niedev/RTranslator/v2.00/app/src/main/assets/sentencepiece_bpe.model"
TOKENIZER = ROOT / "sentencepiece_bpe.model"
SIZE = 4852054
BLOB = "3fc376422b02050741c6aa6ffa9c9ba476167712"
if not TOKENIZER.exists():
    request = urllib.request.Request(URL, headers={"User-Agent": "NLLB-Translation-Smoke/1.0"})
    with urllib.request.urlopen(request, timeout=90) as response, TOKENIZER.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            output.write(chunk)
data = TOKENIZER.read_bytes()
assert len(data) == SIZE
assert hashlib.sha1(f"blob {SIZE}\0".encode() + data).hexdigest() == BLOB
sp = spm.SentencePieceProcessor(model_file=str(TOKENIZER))
print("Verified tokenizer", len(data), "bytes", flush=True)

opts = ort.SessionOptions()
opts.intra_op_num_threads = 2
opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
sessions = {
    key: ort.InferenceSession(str(ROOT / name), sess_options=opts, providers=["CPUExecutionProvider"])
    for key, name in [
        ("embed", "NLLB_embed_and_lm_head.onnx"),
        ("encoder", "NLLB_encoder.onnx"),
        ("cache", "NLLB_cache_initializer.onnx"),
        ("decoder", "NLLB_decoder.onnx"),
    ]
}

def run(key, inputs):
    session = sessions[key]
    return dict(zip([x.name for x in session.get_outputs()], session.run(None, inputs)))

def encode_ids(text, source, framing="prefix"):
    pieces = sp.encode(text, out_type=int)
    ids = [source] if framing == "prefix" else []
    for piece in pieces:
        val = piece + 1
        ids.append({1: 3, 2: 0, 3: 2}.get(val, val))
    if framing == "suffix":
        ids.extend([2, source])
    else:
        ids.append(2)
    return np.asarray([ids], dtype=np.int64)

def detokenize(tokens):
    pieces = []
    for val in tokens:
        if val in (0, 2):
            break
        if val >= 256001:
            continue
        pieces.append({3: 0, 1: 3}.get(val, val - 1) if val > 0 else 0)
    return sp.decode(pieces)

def translate(text, source, limit=32, target=256201, framing="prefix", start_token=2):
    ids = encode_ids(text, source, framing)
    mask = np.ones_like(ids)
    emb = run("embed", {
        "input_ids": ids,
        "pre_logits": np.zeros((1, 1, 1024), dtype=np.float32),
        "use_lm_head": np.array([False], dtype=np.bool_),
    })["embed_matrix"]
    hidden = run("encoder", {"input_ids": ids, "attention_mask": mask, "embed_matrix": emb})["last_hidden_state"]
    cache = run("cache", {"encoder_hidden_states": hidden})
    previous = None
    current_id = start_token
    output = []
    for step in range(limit):
        token = np.asarray([[current_id]], dtype=np.int64)
        token_embedding = run("embed", {
            "input_ids": token,
            "pre_logits": np.zeros((1, 1, 1024), dtype=np.float32),
            "use_lm_head": np.array([False], dtype=np.bool_),
        })["embed_matrix"]
        inputs = {"input_ids": token, "encoder_attention_mask": mask, "embed_matrix": token_embedding}
        for layer in range(12):
            for kind in ("key", "value"):
                inputs[f"past_key_values.{layer}.encoder.{kind}"] = cache[f"present.{layer}.encoder.{kind}"]
                inputs[f"past_key_values.{layer}.decoder.{kind}"] = (
                    np.empty((1, 16, 0, 64), dtype=np.float32) if previous is None
                    else previous[f"present.{layer}.decoder.{kind}"]
                )
        previous = run("decoder", inputs)
        logits = run("embed", {
            "input_ids": np.zeros((1, 2), dtype=np.int64),
            "pre_logits": previous["pre_logits"],
            "use_lm_head": np.array([True], dtype=np.bool_),
        })["logits"]
        predicted = int(np.argmax(logits[0, -1]))
        if step == 0:
            current_id = target  # forced target-language token
        elif predicted == 2:
            break
        else:
            output.append(predicted)
            current_id = predicted
        if step < 3:
            print("generation step", step, "predicted", predicted, flush=True)
    return {"input": text, "source_id": source, "target_id": target, "framing": framing, "start_token": start_token, "output": detokenize(output), "token_ids": output, "steps": step + 1}

cases = [
    ("You have to suffer.", 256047),
    ("Where are you going?", 256047),
    ("気をつけてね。", 256079),
    ("学校に行きます。", 256079),
]
results = []
# Compare prefix vs suffix source framing and Chinese target variants.
# Do not silently count a nonempty mistranslation as quality success.
for text, language in cases:
    for framing in ("prefix", "suffix"):
        for target in (256201, 256200):
            start = time.monotonic()
            item = translate(text, language, target=target, framing=framing)
            item["seconds"] = round(time.monotonic() - start, 2)
            results.append(item)
            print("TRANSLATION", json.dumps(item, ensure_ascii=False), flush=True)
            assert item["output"].strip(), "Empty translation: " + text
pathlib.Path("nllb-translation-report.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + chr(10))
print("PASS: 16 NLLB decoding configurations executed; translation quality requires manual comparison", flush=True)
