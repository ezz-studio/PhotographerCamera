"""Tests for tools/dataset_loader.py (uses synthetic JPG, no real dataset needed)."""
import os
import sys
import tempfile

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from dataset_loader import (  # noqa: E402
    discover_images,
    generate_image_id,
    read_image,
    read_exif,
    build_metadata,
)


def _make_tmp_jpg(path: str) -> None:
    arr = (np.random.rand(64, 64, 3) * 255).astype("uint8")
    Image.fromarray(arr, "RGB").save(path, "JPEG", quality=90)


def test_discover_and_read():
    d = tempfile.mkdtemp()
    p1 = os.path.join(d, "a.jpg")
    _make_tmp_jpg(p1)

    found = discover_images(d)
    assert len(found) == 1, found

    img = read_image(p1)
    assert img.shape == (64, 64, 3)
    assert img.max() <= 1.0 and img.min() >= 0.0

    ex = read_exif(p1)
    assert ex["width"] == 64 and ex["height"] == 64

    iid = generate_image_id(p1)
    assert len(iid) == 16

    meta = build_metadata([p1])
    assert meta["count"] == 1
    assert meta["images"][0]["id"] == iid


if __name__ == "__main__":
    test_discover_and_read()
    print("test_dataset_loader: PASS")
