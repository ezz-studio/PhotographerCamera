"""Tests for Phase 3-13 StyleAnalyzer + CPU renderer integration."""
import json
import os
import sys
import tempfile

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
import style_analyzer  # noqa: E402


def _make_color_images(d, n=6):
    rng = np.random.default_rng(1)
    paths = []
    for i in range(n):
        h, w = 480, 640
        arr = (rng.random((h, w, 3)) * 255).astype("uint8")
        p = os.path.join(d, f"img{i}.jpg")
        Image.fromarray(arr, "RGB").save(p, "JPEG", quality=90)
        paths.append(p)
    return paths


def test_style_analysis_emits_all_phase_files():
    d = tempfile.mkdtemp()
    paths = _make_color_images(d)
    out = os.path.join(d, "analysis")
    style_analyzer.run(d, out_dir=out)
    expected = [
        "rgb_statistics.json", "luminance_statistics.json", "color_statistics.json",
        "tone_statistics.json", "tone_curve_initial.json", "exposure_statistics.json",
        "photographer_color_model.json", "color_matrix.json", "hsl_mapping.json",
        "highlight_rolloff.json", "shadow_model.json", "lens_model.json", "texture_model.json",
    ]
    for name in expected:
        path = os.path.join(out, name)
        assert os.path.exists(path), name
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        assert "schema" in data
    # color matrix has 3x3
    cm = json.load(open(os.path.join(out, "color_matrix.json")))
    assert len(cm["matrix_3x3"]) == 3 and len(cm["matrix_3x3"][0]) == 3


if __name__ == "__main__":
    test_style_analysis_emits_all_phase_files()
    print("test_style: PASS")
