"""Tests for Phase 1 (dataset_analyzer) and Phase 2 (dataset_cleaner)."""
import os
import sys
import tempfile

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
import dataset_analyzer  # noqa: E402
import dataset_cleaner  # noqa: E402


def _smooth(w=1200, h=800, seed=0):
    """Photo-like neutral image (mild gradient + small balanced color blobs)."""
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    base = (xx / w * 0.6 + yy / h * 0.3)  # 0..~0.9 gradient
    img = np.stack([base, base * 0.97, base * 0.94], axis=-1)  # mild cool-neutral
    for _ in range(3):
        cy, cx = rng.integers(0, h), rng.integers(0, w)
        rad = rng.integers(w // 10, w // 3)
        blob = np.exp(-(((yy - cy) ** 2 + (xx - cx) ** 2) / (2 * rad ** 2)))
        ch = rng.integers(0, 3)
        img[..., ch] = np.clip(img[..., ch] + blob * 0.15, 0, 0.98)
    return np.clip(img, 0.02, 0.98)


def _make(path, arr):
    Image.fromarray(np.clip(arr * 255, 0, 255).astype("uint8"), "RGB").save(path, "JPEG", quality=92)


def test_analyzer_writes_metadata_and_manifest():
    d = tempfile.mkdtemp()
    _make(os.path.join(d, "a.jpg"), _smooth())
    _make(os.path.join(d, "b.jpg"), _smooth(seed=1))
    meta = dataset_analyzer.analyze_dataset(d, out_dir=os.path.join(d, "out"))
    assert meta["count"] == 2
    assert os.path.exists(os.path.join(d, "out", "dataset_metadata.json"))
    assert os.path.exists(os.path.join(d, "out", "dataset_manifest.json"))


def test_cleaner_classifies_problem_images():
    d = tempfile.mkdtemp()
    _make(os.path.join(d, "normal.jpg"), _smooth(1600, 1200))         # accepted
    _make(os.path.join(d, "lowres.jpg"), np.full((100, 100, 3), 0.3))  # rejected: low res (flat, distinct)
    _make(os.path.join(d, "black.jpg"), np.full((200, 200, 3), 0.01))  # rejected: near black
    gray = np.full((1100, 1100, 3), 0.5)
    _make(os.path.join(d, "bw.jpg"), gray)                            # rejected: B&W
    _make(os.path.join(d, "zz_dup.jpg"), _smooth(1600, 1200))        # rejected: duplicate of normal
    report = dataset_cleaner.clean_dataset(d, out_dir=os.path.join(d, "out"), copy=False)
    statuses = [r["status"] for r in report["images"]]
    assert "accepted" in statuses
    assert statuses.count("rejected") >= 3
    normal = [r for r in report["images"] if r["path"].endswith("normal.jpg")][0]
    assert normal["status"] == "accepted"


if __name__ == "__main__":
    test_analyzer_writes_metadata_and_manifest()
    test_cleaner_classifies_problem_images()
    print("test_dataset: PASS")
