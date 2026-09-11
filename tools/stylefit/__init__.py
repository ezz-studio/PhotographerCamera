#!/usr/bin/env python3
"""stylefit — scientific photographer-style fitting (v2).

Public entry points:
    fit(reference_paths, ...)  -> (profile, report)
    fit_directory(ref_dir, ...)-> (profile, report)

Modules:
    color      Lab/sRGB conversions, quantile transfer, de-styling (neutralize)
    lut3d      3D LUT learning (binning -> fill -> smooth -> PAVA -> shrink)
    adaptive   image-adaptive routing over a bank of basis LUTs (RBF, no CNN)
    fit        end-to-end fitting + spatial-layer estimation + evaluation
"""
from __future__ import annotations

from . import adaptive, color, lut3d  # noqa: F401
from .fit import fit, fit_arrays, fit_from_arrays, fit_directory  # noqa: F401

__all__ = ["fit", "fit_arrays", "fit_from_arrays", "fit_directory",
           "color", "lut3d", "adaptive"]
__version__ = "2.0"
