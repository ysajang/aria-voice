#!/usr/bin/env python3
"""
SpeechBrain ECAPA-TDNN → ONNX export
Input  : waveform (1, N) float32, raw PCM short→float (NOT normalized)
Output : embedding (1, 192) float32, L2-normalized

Usage:
    python export_ecapa.py
    # Output: models/ecapa_speaker.onnx
"""
import os
import sys
import struct
import numpy as np

def check_deps():
    missing = []
    for pkg in ["torch", "speechbrain", "onnx", "onnxruntime"]:
        try:
            __import__(pkg)
        except ImportError:
            missing.append(pkg)
    if missing:
        print(f"[ERROR] Missing: {', '.join(missing)}")
        print("        Run: pip install -r requirements.txt")
        sys.exit(1)

check_deps()

import torch
import torch.nn as nn
import torch.nn.functional as F
import onnx
import onnxruntime as ort
from speechbrain.pretrained import EncoderClassifier


# ─────────────────────────────────────────────────────────────────────────────
# Wrapper: raw PCM float → L2-normalized speaker embedding
# Includes SpeechBrain Fbank preprocessing inside the traced graph.
# ─────────────────────────────────────────────────────────────────────────────
class EcapaOnnxWrapper(nn.Module):
    """
    Full pipeline: normalize waveform → FBANK → ECAPA-TDNN → L2 embedding
    """
    def __init__(self, ec: EncoderClassifier):
        super().__init__()
        self.compute_features = ec.mods.compute_features
        self.mean_var_norm    = ec.mods.mean_var_norm
        self.embedding_model  = ec.mods.embedding_model

    def forward(self, waveform_raw: torch.Tensor) -> torch.Tensor:
        # 1. PCM raw short→float normalization: [-32768, 32767] → [-1, 1]
        wav = waveform_raw / 32768.0                       # (1, T)

        # 2. Log-mel FBANK  (SpeechBrain Fbank: 80-dim, 25ms/10ms)
        feats = self.compute_features(wav)                 # (1, time_frames, 80)

        # 3. Mean-var normalization (per utterance, lengths=1.0)
        lengths = torch.ones(feats.shape[0], device=feats.device)
        feats = self.mean_var_norm(feats, lengths)         # (1, time_frames, 80)

        # 4. ECAPA-TDNN expects (batch, n_mels, time)
        feats = feats.permute(0, 2, 1).contiguous()       # (1, 80, time_frames)

        # 5. Speaker embedding
        emb = self.embedding_model(feats)                  # (1, 192)

        # 6. L2 normalize
        emb = F.normalize(emb, p=2, dim=-1)               # (1, 192)
        return emb


def main():
    print("=" * 60)
    print("ECAPA-TDNN ONNX Export")
    print("=" * 60)

    # ── 1. Load pretrained model ────────────────────────────────
    print("[1/5] Loading speechbrain/spkrec-ecapa-voxceleb ...")
    ec = EncoderClassifier.from_hparams(
        source="speechbrain/spkrec-ecapa-voxceleb",
        savedir="tmp_spkrec",
        run_opts={"device": "cpu"},
    )
    ec.eval()
    for p in ec.parameters():
        p.requires_grad_(False)

    # ── 2. Warm-up (triggers lazy inits) ────────────────────────
    print("[2/5] Building wrapper + warm-up ...")
    wrapper = EcapaOnnxWrapper(ec)
    wrapper.eval()
    dummy = torch.zeros(1, 24000)   # 1.5 sec at 16kHz
    with torch.no_grad():
        _ = wrapper(dummy)

    # ── 3. Export ───────────────────────────────────────────────
    os.makedirs("models", exist_ok=True)
    out_path = "models/ecapa_speaker.onnx"
    print(f"[3/5] Exporting to {out_path} ...")

    torch.onnx.export(
        wrapper,
        dummy,
        out_path,
        opset_version=17,           # torch.stft needs ≥17
        input_names=["waveform"],
        output_names=["embedding"],
        dynamic_axes={
            "waveform": {1: "n_samples"},   # variable-length audio
        },
        do_constant_folding=True,
        verbose=False,
    )

    # ── 4. Validate ONNX graph ──────────────────────────────────
    print("[4/5] Validating ONNX graph ...")
    onnx_model = onnx.load(out_path)
    onnx.checker.check_model(onnx_model)
    print(f"      ONNX opset: {onnx_model.opset_import[0].version}")

    # ── 5. Run-time correctness check ──────────────────────────
    print("[5/5] Running ORT inference test ...")
    sess = ort.InferenceSession(out_path, providers=["CPUExecutionProvider"])

    # Test 1: silence (1.5 sec)
    silence = np.zeros((1, 24000), dtype=np.float32)
    emb_silence = sess.run(["embedding"], {"waveform": silence})[0]

    # Test 2: random noise as "speech" (2.0 sec)
    noise = (np.random.randn(1, 32000) * 8000).astype(np.float32)
    emb_noise = sess.run(["embedding"], {"waveform": noise})[0]

    # Same input twice → identical output
    emb_noise2 = sess.run(["embedding"], {"waveform": noise})[0]

    assert emb_silence.shape == (1, 192), f"Bad shape: {emb_silence.shape}"
    assert abs(np.linalg.norm(emb_noise) - 1.0) < 0.01, "Not L2-normalized!"
    assert np.allclose(emb_noise, emb_noise2), "Non-deterministic output!"

    # Cosine sim between silence and noise should be low-ish
    cos = float(np.dot(emb_silence[0], emb_noise[0]))
    print(f"      cos(silence, noise) = {cos:.4f}  (expected: low)")
    print(f"      L2 norm             = {np.linalg.norm(emb_noise):.6f}  (expected: ~1.0)")

    file_mb = os.path.getsize(out_path) / 1024 / 1024
    print(f"\n✅  Saved: {out_path}  ({file_mb:.1f} MB)")
    print(f"    Copy to Android: app/src/main/assets/ecapa_speaker.onnx")


if __name__ == "__main__":
    main()
