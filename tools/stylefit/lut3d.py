#!/usr/bin/env python3
"""3-D LUT learning, smoothing and sampling (stylefit v2).

The colour layer of a PhotographerProfile is a 33^3 3-D LUT: it can express
everything the parametric chain (exposure / WB / matrix / tone curve / HSL)
can, plus the hue-dependent, non-linear cross-talk that chain cannot.

Learning is *supervised and paired*: for every reference photo A we build its
de-styled twin A' (see color.neutralize) and store (A'_px -> A_px) pairs.
A'->A is by construction the mapping "neutral photo -> photographer's look",
which is exactly what must be applied to an ordinary photo.

Pipeline (every step is bounded and auditable):

  accumulate   bin input colours into the n^3 grid, average the outputs
  fill         empty cells by diffusion from filled neighbours (never invented)
  smooth       separable [1,2,1] passes — suppresses per-bin sampling noise
  monotone     PAVA on output luminance vs input luminance — guarantees the
               LUT can never *invert* tonal order (the #1 cause of "why does
               my shadow look brighter than my midtone")
  shrink       LUT = (1-s)*identity + s*LUT — `s` is the user-facing
               "style strength", linear and fully reversible
"""
from __future__ import annotations

import numpy as np


# ------------------------------------------------------------------ sampling
def _bin_index(rgb, n):
    rgb = np.clip(np.asarray(rgb, dtype=np.float32), 0.0, 1.0)
    idx = np.rint(rgb * (n - 1)).astype(np.int64)
    idx = np.clip(idx, 0, n - 1)
    # .cube stores RED varying fastest
    return (idx[..., 0] + n * (idx[..., 1] + n * idx[..., 2])).ravel()


def accumulate(src_rgb, dst_rgb, n=33):
    """Bin-accumulate paired pixels. Returns (lut, count) with NaN where empty."""
    src = np.asarray(src_rgb, dtype=np.float32).reshape(-1, 3)
    dst = np.asarray(dst_rgb, dtype=np.float32).reshape(-1, 3)
    idx = _bin_index(src, n)
    size = n * n * n
    cnt = np.bincount(idx, minlength=size).astype(np.float64)
    acc = np.zeros((size, 3), np.float64)
    for c in range(3):
        acc[:, c] = np.bincount(idx, weights=dst[:, c].astype(np.float64), minlength=size)
    with np.errstate(invalid="ignore", divide="ignore"):
        lut = acc / np.maximum(cnt, 1)[:, None]
    lut[cnt == 0] = np.nan
    return lut.astype(np.float32).reshape(n, n, n, 3), cnt.reshape(n, n, n)


# ---------------------------------------------------------------------- fill
def fill_empty(lut, identity, iters=12):
    """Diffuse real data into empty cells; fall back to identity for the rest."""
    n = lut.shape[0]
    out = lut.astype(np.float32).copy()
    known = ~np.isnan(out).any(axis=-1)
    if known.all():
        return out
    for _ in range(iters):
        if known.all():
            break
        acc = np.zeros_like(out)
        w = np.zeros(out.shape[:3] + (1,), np.float32)
        for ax in range(3):
            for sh in (1, -1):
                src = np.roll(known, sh, axis=ax)
                val = np.roll(out, sh, axis=ax)
                m = src & ~known
                if not m.any():
                    continue
                acc[m] += val[m]
                w[..., 0][m] += 1.0
        new = np.where(w > 0, acc / np.maximum(w, 1e-6), out)
        filled = (~known) & (w[..., 0] > 0)
        out = np.where(filled[..., None], new, out)
        known = known | filled
    still = ~known
    if still.any():
        out[still] = identity[still]
    return out


# ---------------------------------------------------------------------- smooth
def smooth(lut, passes=1):
    """Separable [1,2,1]/4 smoothing along each axis (boundaries mirrored)."""
    out = lut.astype(np.float32)
    for _ in range(max(0, passes)):
        for ax in range(3):
            out = _blur_axis(out, ax)
    return out


def _blur_axis(x, ax):
    a = np.moveaxis(x, ax, 0)
    pad = np.pad(a, ((1, 1), (0, 0), (0, 0), (0, 0)), mode="edge")
    out = 0.25 * pad[:-2] + 0.5 * pad[1:-1] + 0.25 * pad[2:]
    return np.moveaxis(out, 0, ax).astype(np.float32)


# -------------------------------------------------------------------- monotone
def monotone_luminance(lut, group_size=64):
    """Force output luminance to be non-decreasing in input luminance (PAVA).

    Cells are ordered by their input luminance; tied cells are averaged first
    (they belong to the same iso-luminance shell), then an isotonic regression
    is run on the sequence. The RGB triple is rescaled so chroma (the ratio
    between channels) survives the correction.
    """
    n = lut.shape[0]
    axis = np.linspace(0.0, 1.0, n, dtype=np.float32)
    B, G, R = np.meshgrid(axis, axis, axis, indexing="ij")     # r fastest
    lin_in = (0.2126 * R + 0.7152 * G + 0.0722 * B).astype(np.float32).ravel()

    flat = lut.reshape(-1, 3).astype(np.float32)
    lin_out = (0.2126 * flat[:, 0] + 0.7152 * flat[:, 1] + 0.0722 * flat[:, 2])

    order = np.argsort(lin_in, kind="stable")
    lo_sorted = lin_out[order]
    in_sorted = lin_in[order]

    # group cells whose input luminance is effectively identical
    keys = np.round(in_sorted / (1.0 / 1024.0)).astype(np.int64)
    out_iso = _pava_groups(lo_sorted, keys)

    fixed = np.empty_like(out_iso)
    fixed[order] = out_iso

    delta = fixed - lin_out
    scale = np.where(lin_out > 1e-4, 1.0 + delta / np.maximum(lin_out, 1e-4), 1.0)
    scale = np.clip(scale, 0.0, 4.0)
    return np.clip(flat * scale[:, None], 0.0, 1.0).reshape(n, n, n, 3).astype(np.float32)


def _pava_groups(values, keys):
    """Pool-adjacent-violators on blocks defined by *keys* (already sorted)."""
    bounds = np.flatnonzero(np.r_[True, keys[1:] != keys[:-1], True])
    out = values.astype(np.float64).copy()
    blocks = []          # [mean, weight, start, end]
    for i in range(len(bounds) - 1):
        s, e = bounds[i], bounds[i + 1]
        blocks.append([out[s:e].mean(), float(e - s), int(s), int(e)])
    stack = []
    for blk in blocks:
        stack.append(blk)
        while len(stack) > 1 and stack[-2][0] > stack[-1][0]:
            a, b = stack.pop(), stack.pop()      # a = later block, b = earlier
            w = a[1] + b[1]
            stack.append([(a[0] * a[1] + b[0] * b[1]) / w, w, b[2], a[3]])
    res = np.empty(len(values), np.float64)
    for mean, _w, s, e in stack:                 # blocks keep their span after pooling
        res[s:e] = mean
    return res.astype(np.float32)


# -------------------------------------------------------------------- identity
def identity_lut(n=33):
    axis = np.linspace(0.0, 1.0, n, dtype=np.float32)
    B, G, R = np.meshgrid(axis, axis, axis, indexing="ij")
    return np.stack([R, G, B], axis=-1).astype(np.float32)


def shrink(lut, strength, identity=None):
    """Blend towards identity — the single, interpretable "style strength" knob."""
    n = lut.shape[0]
    ident = identity if identity is not None else identity_lut(n)
    s = float(np.clip(strength, 0.0, 1.0))
    return np.clip(ident * (1.0 - s) + lut * s, 0.0, 1.0).astype(np.float32)


# ---------------------------------------------------------------------- learn
def learn(src_rgb, dst_rgb, n=33, strength=1.0, smooth_passes=1,
          monotone=True, min_count=1):
    """Learn an n^3 LUT from paired pixels. Returns (lut, info)."""
    ident = identity_lut(n)
    lut, cnt = accumulate(src_rgb, dst_rgb, n)
    filled_frac = float(np.mean(cnt > 0))
    lut[cnt < max(1, min_count)] = np.nan
    lut = fill_empty(lut, ident)
    lut = smooth(lut, passes=smooth_passes)
    if monotone:
        lut = monotone_luminance(lut)
    lut = shrink(lut, strength, ident)
    info = {
        "size": n,
        "cells": int(n ** 3),
        "observed_cells": int((cnt > 0).sum()),
        "coverage": round(filled_frac, 4),
        "smooth_passes": int(smooth_passes),
        "monotone": bool(monotone),
        "strength": float(strength),
    }
    return lut.astype(np.float32), info


# -------------------------------------------------------------------- sampling
def sample(lut, rgb):
    """Trilinear lookup with clamp-to-edge (matches GL_LINEAR / CLAMP)."""
    n = lut.shape[0]
    rgb = np.asarray(rgb, dtype=np.float32)
    shape = rgb.shape
    flat = np.clip(rgb.reshape(-1, 3), 0.0, 1.0) * (n - 1)
    i0 = np.floor(flat).astype(np.int32)
    f = (flat - i0).astype(np.float32)
    i1 = np.minimum(i0 + 1, n - 1)
    i0 = np.minimum(i0, n - 1)

    def g(ix, iy, iz):
        return lut[iz, iy, ix]           # lut indexed [b, g, r]

    x0, x1 = i0[:, 0], i1[:, 0]
    y0, y1 = i0[:, 1], i1[:, 1]
    z0, z1 = i0[:, 2], i1[:, 2]
    fx, fy, fz = f[:, 0], f[:, 1], f[:, 2]

    c000 = g(x0, y0, z0); c100 = g(x1, y0, z0)
    c010 = g(x0, y1, z0); c110 = g(x1, y1, z0)
    c001 = g(x0, y0, z1); c101 = g(x1, y0, z1)
    c011 = g(x0, y1, z1); c111 = g(x1, y1, z1)

    c00 = c000 * (1 - fx[:, None]) + c100 * fx[:, None]
    c10 = c010 * (1 - fx[:, None]) + c110 * fx[:, None]
    c01 = c001 * (1 - fx[:, None]) + c101 * fx[:, None]
    c11 = c011 * (1 - fx[:, None]) + c111 * fx[:, None]
    c0 = c00 * (1 - fy[:, None]) + c10 * fy[:, None]
    c1 = c01 * (1 - fy[:, None]) + c11 * fy[:, None]
    out = c0 * (1 - fz[:, None]) + c1 * fz[:, None]
    return np.clip(out, 0.0, 1.0).astype(np.float32).reshape(shape)


# ------------------------------------------------------------------ .cube I/O
def write_cube(path, lut, title="PhotographerCamera stylefit LUT"):
    n = lut.shape[0]
    flat = lut.reshape(-1, 3)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(f'TITLE "{title}"\n')
        f.write("# generated by tools/stylefit — neutral->style paired fit\n")
        f.write(f"LUT_3D_SIZE {n}\n")
        f.write("DOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n\n")
        for v in flat:
            f.write(f"{v[0]:.6f} {v[1]:.6f} {v[2]:.6f}\n")
    return path


def read_cube(path):
    n = None
    vals = []
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            s = line.strip()
            if not s or s.startswith("#"):
                continue
            if s.upper().startswith("LUT_3D_SIZE"):
                n = int(s.split()[-1])
                continue
            if s[0].isdigit() or s[0] in "+-.":
                parts = s.split()
                if len(parts) >= 3:
                    vals.append([float(parts[0]), float(parts[1]), float(parts[2])])
    if n is None:
        raise ValueError(f"{path}: no LUT_3D_SIZE")
    arr = np.asarray(vals, dtype=np.float32)
    if arr.shape[0] != n ** 3:
        raise ValueError(f"{path}: expected {n ** 3} entries, got {arr.shape[0]}")
    return np.clip(arr, 0, 1).reshape(n, n, n, 3)


# ------------------------------------------------------- compact JSON payload
def to_payload(lut):
    """uint8-quantised + base64 payload for embedding in profile JSON.

    A 33^3 LUT at 8-bit output precision is 108 KB raw / 144 KB base64 — small
    enough to ship inside the profile and 4x smaller than float32.
    """
    import base64

    n = lut.shape[0]
    q = np.rint(np.clip(lut, 0.0, 1.0) * 255.0).astype(np.uint8)
    return {
        "size": int(n),
        "dtype": "uint8",
        "scale": 255,
        "order": "r_fastest",
        "data": base64.b64encode(q.tobytes()).decode("ascii"),
    }


def from_payload(payload):
    import base64

    n = int(payload.get("size", 33))
    scale = float(payload.get("scale", 255) or 255)
    raw = base64.b64decode(payload["data"])
    q = np.frombuffer(raw, dtype=np.uint8)
    arr = q.astype(np.float32) / scale
    if arr.size != n * n * n * 3:
        raise ValueError("color_lut payload size mismatch")
    return np.clip(arr, 0.0, 1.0).reshape(n, n, n, 3)
