"""Integration test for the offline Profile Builder (Phase 14-18)."""
import json
import os
import sys
import tempfile

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
import style_analyzer  # noqa: E402
import ai_profile_generator  # noqa: E402
import profile_optimizer  # noqa: E402
import profile_validator  # noqa: E402


def _make_dataset(d, n=8):
    rng = np.random.default_rng(11)
    paths = []
    for i in range(n):
        w, h = 256, 256
        yy, xx = np.mgrid[0:h, 0:w].astype("float32")
        base = (xx / w * 0.6 + yy / h * 0.3)
        img = np.stack([base * 0.95, base * 0.9, base * 0.85], -1)
        for _ in range(rng.integers(3, 7)):
            cy, cx = rng.integers(0, h), rng.integers(0, w)
            rad = rng.integers(w // 12, w // 4)
            blob = np.exp(-(((yy - cy) ** 2 + (xx - cx) ** 2) / (2 * rad ** 2)))
            ch = rng.integers(0, 3)
            img[..., ch] = np.clip(img[..., ch] + blob * rng.uniform(0.1, 0.35), 0, 0.98)
        p = os.path.join(d, f"p{i}.jpg")
        Image.fromarray((np.clip(img, 0.02, 0.98) * 255).astype("uint8"), "RGB").save(p, "JPEG", quality=90)
        paths.append(p)
    return paths


def test_offline_builder_closes_loop():
    d = tempfile.mkdtemp()
    paths = _make_dataset(d)
    analysis = os.path.join(d, "analysis")
    style_analyzer.run(d, out_dir=analysis)

    prof_v1, gen = ai_profile_generator.generate_profile(analysis, "Test P")
    assert gen["schema_valid"]

    imgs = [np.asarray(Image.open(p).convert("RGB"), dtype=np.float32) / 255.0 for p in paths]
    split = profile_validator.split_dataset(len(imgs), seed=42)
    train_val = [imgs[i] for i in (split["train"] + split["validation"])]
    test = [imgs[i] for i in split["test"]]

    prof_final, opt = profile_optimizer.optimize(prof_v1, train_val, maxiter=25, size=64)
    assert opt["final_schema_valid"]

    val = profile_validator.validate(prof_final, test)
    assert isinstance(val["overall_test_loss"], float)
    # held-out test loss must be finite and small (style reproduced on unseen data)
    assert val["overall_test_loss"] < 1.0

    # regression baseline stored
    base = {"overall_test_loss": val["overall_test_loss"], "components": val["components"]}
    rt = profile_validator.regression_test(prof_final, test, base, tolerance=0.05)
    assert rt["passed"]


if __name__ == "__main__":
    test_offline_builder_closes_loop()
    print("test_offline_pipeline: PASS")
