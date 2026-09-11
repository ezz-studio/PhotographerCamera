#!/usr/bin/env python3
"""Offline Profile Builder driver — closes the PC pipeline.

Runs the full offline chain on a photographer dataset:
  analyze (Phase 3-13) -> AI generate (Phase 15) -> optimize (Phase 16, Loss Phase 17)
  -> validate on held-out test set (Phase 18) -> export profile_final.json

Usage:
  python tools/build_profile.py <images_root> --name "Photographer A" --out profiles
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from dataset_loader import discover_images, read_image  # noqa: E402
import style_analyzer  # noqa: E402
import ai_profile_generator  # noqa: E402
import profile_optimizer  # noqa: E402
import profile_validator  # noqa: E402


def load_images(root, limit=None, size=256):
    """Load dataset images. By default ALL discovered images are loaded — the
    old hard cap of 40 silently threw away everything past the first 40 files
    (alphabetical), which both starved the optimiser and shrank the held-out
    set to a token handful (400 photos -> n_test=6)."""
    paths = discover_images(root)
    n_total = len(paths)
    if limit:
        paths = paths[:limit]
    imgs = []
    n_load = len(paths)
    step = max(1, n_load // 10) if n_load >= 20 else n_load  # tiny sets: report only at completion
    for i, p in enumerate(paths, 1):
        im = Image.open(p).convert("RGB")
        arr = np.asarray(im, dtype=np.float32) / 255.0
        h, w = arr.shape[:2]
        if max(h, w) > size:
            s = size / max(h, w)
            arr = np.asarray(Image.fromarray((arr * 255).astype("uint8")).resize(
                (max(1, int(w * s)), max(1, int(h * s))), Image.Resampling.LANCZOS), dtype=np.float32) / 255.0
        imgs.append(arr)
        if i % step == 0 or i == n_load:
            print(f"  优化集载入: {i}/{n_load} 张 ({i * 100 // n_load}%)", flush=True)
    print(f"  载入照片: {len(imgs)} 张（目录共发现 {n_total} 张"
          + (f"，上限 limit={limit}" if limit else "，全部载入") + "）")
    return imgs, n_total


def build(images_root: str, name: str, out_dir: str = "profiles", analysis_dir: str = "dataset/analysis",
          validation_root: str | None = None, strength: float = 0.8,
          rounds: int | None = None,
          progress=None) -> dict:
    """Fit a profile with the stylefit v3 (unpaired distribution-transfer) pipeline.

    Roles (per the project brief):
        images_root     = 已调色参考片（风格目标分布 T —— 摄影师交付的成片）
        validation_root = 同一相机/未调色的普通照片（输入分布 S —— 算法的基准侧）

    风格 = T ÷ S。缺少未调色这一侧就只剩一个分布，只能用假设去猜，
    与「算法禁止猜测」的原则冲突，因此未提供普通照片目录时直接报错。
    """
    os.makedirs(out_dir, exist_ok=True)

    def say(msg):
        (progress or (lambda m: print(m, flush=True)))(msg)

    say("[1/4] 扫描参考片 …")
    from stylefit import fit as sfit
    from dataset_loader import discover_images as _disc

    refs = _disc(images_root)
    if len(refs) < 3:
        raise ValueError(f"参考片不足 3 张（在 {images_root} 只找到 {len(refs)} 张）")
    plains = None
    if validation_root and os.path.isdir(validation_root):
        plains = _disc(validation_root)
        say(f"  参考片 {len(refs)} 张 | 未调色普通照片 {len(plains)} 张（输入分布基准 + 验证）")
    else:
        raise ValueError(
            "未提供「未调色普通照片」目录（validation_root）。\n"
            "风格 = 已调色分布 ÷ 未调色分布：未调色这一侧是测量的基准，不是可选项。\n"
            "请在 Studio 中填写「普通照片目录（验证集·未调色）」，或在命令行传入 validation_root。")

    say("[2/4] 学习分布迁移 3D LUT（CIELAB：亮度分位 + 色度最优传输 + 分色相/亮度残差）…")
    # sfit 是 `from stylefit import fit` 导入的函数（不是模块），直接调用
    _kw = {} if rounds is None else {"rounds": int(rounds)}
    profile, report = sfit(refs, name=name, plain_paths=plains,
                           strength=strength, progress=lambda m: say("  " + m), **_kw)
    if report.get("iterative"):
        it = report["iterative"]
        say(f"  迭代训练：请求 {it.get('rounds_requested')} 轮 / 接受 "
            f"{it.get('accepted_rounds')} 轮（门控={it.get('gate')}）")

    say("[3/4] 写入结果 …")
    with open(os.path.join(out_dir, "profile_final.json"), "w", encoding="utf-8") as f:
        json.dump(profile, f, indent=2, ensure_ascii=False)
    with open(os.path.join(out_dir, "stylefit_report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)

    # ---- compatibility view: the Studio report panel and the regression
    # baseline read these keys from validation_report.json
    cf = report.get("content_fidelity") or {}
    hold = report.get("holdout") or {}
    cv = report.get("cv") or {}
    status = "validated" if cf.get("verdict", "ok") == "ok" else "pending"
    profile["validation_status"] = status

    # ---- intuitive style metrics (honest K-fold when available) ----
    sd_after = cv.get("style_distance_after", report.get("style_distance"))
    sd_before = cv.get("style_distance_before", report.get("style_distance_before"))
    improvement = cv.get("style_improvement", report.get("style_improvement"))
    similarity = cv.get("style_similarity")
    if similarity is None and sd_after is not None:
        similarity = round(max(0.0, 100.0 - float(sd_after)), 2)
    gap_reduction = None
    if sd_before and sd_after is not None and sd_before > 0:
        gap_reduction = round((1.0 - float(sd_after) / float(sd_before)) * 100.0, 1)

    val_report = {
        "schema": "validation_report/v2",
        "validation_mode": "stylefit",
        "validation_status": status,
        "honest_cv": bool(cv),
        # content fidelity — how much of the ORIGINAL photo survives grading
        "overall_test_loss": cf.get("deltaE_mean", hold.get("deltaE_mean")),
        "n_test": cf.get("n_images", hold.get("n_images", 0)),
        "loss_threshold": 30.0,
        # ---- intuitive style-transfer metrics ----
        # 与源数据集的风格近似度（%）：应用风格后，色彩分布与摄影师成片的接近程度
        "style_similarity": similarity,
        # 与源数据集的差距：style_distance 即"归一化平均 |Δ|"，越小越接近源
        "style_distance_after": sd_after,
        "style_distance_before": sd_before,
        "style_improvement": improvement,           # 应用前差距 / 应用后差距
        "style_gap_reduction_pct": gap_reduction,  # 相比未调色，差距缩小 %
        "components": {k: v for k, v in (report.get("style_match") or {}).items()},
        # 3-column per-key table: 输入分布 S / 应用后 / 目标 T
        "plain_stats": report.get("plain_stats"),
        "rendered_stats": report.get("rendered_stats"),
        "reference_stats": report.get("reference_stats", {}),
        "dataset": report.get("dataset", {}),
        "content_fidelity": cf,
        "holdout": hold,
        "cv": cv,
        "iterative": report.get("iterative"),
        "lut": report.get("lut", {}),
    }
    with open(os.path.join(out_dir, "validation_report.json"), "w", encoding="utf-8") as f:
        json.dump(val_report, f, indent=2, ensure_ascii=False)
    with open(os.path.join(out_dir, "regression_baseline.json"), "w", encoding="utf-8") as f:
        json.dump({"overall_test_loss": val_report["overall_test_loss"],
                   "components": val_report["components"]}, f, indent=2, ensure_ascii=False)

    say("[4/4] 完成")
    say(f"  ΔE(应用到普通照片) = {cf.get('deltaE_mean')} | 判定 {cf.get('verdict')}")
    say(f"  留出集重建 ΔE = {hold.get('deltaE_mean')}")
    say(f"  输出目录: {out_dir}")
    return profile


def build_legacy(images_root: str, name: str, out_dir: str = "profiles", analysis_dir: str = "dataset/analysis",
                 validation_root: str | None = None) -> dict:
    style_analyzer.run(images_root, out_dir=analysis_dir)

    print("[2/5] AI profile generation (Phase 15) ...")
    prof_v1, gen_report = ai_profile_generator.generate_profile(analysis_dir, name)
    with open(os.path.join(out_dir, "profile_v1.json"), "w", encoding="utf-8") as f:
        json.dump(prof_v1, f, indent=2, ensure_ascii=False)
    with open(os.path.join(out_dir, "profile_generation_report.json"), "w", encoding="utf-8") as f:
        json.dump(gen_report, f, indent=2, ensure_ascii=False)

    print("[3/5] Split + optimize (Phase 16, Loss 17) ...")
    imgs, n_total = load_images(images_root)
    split = profile_validator.split_dataset(len(imgs), seed=42)
    train_val = [imgs[i] for i in (split["train"] + split["validation"])]
    if validation_root and os.path.isdir(validation_root):
        _vpaths = discover_images(validation_root)
        if _vpaths:
            print(f"  验证集：使用独立「未调色普通照片」目录 -> {validation_root}（{len(_vpaths)} 张）")
            test = [read_image(p) for p in _vpaths]
            val_mode = "ungraded-photos"
        else:
            print("  普通照片目录为空，回退到旧逻辑（从参考片随机留出）")
            test = [imgs[i] for i in split["test"]]
            val_mode = "legacy-split"
    else:
        print("  验证集：未提供普通照片目录，沿用旧逻辑（从参考片随机留出；"
              "已调色图作验证对象会把风格二次叠加，偏差被放大）")
        test = [imgs[i] for i in split["test"]]
        val_mode = "legacy-split"
    # NOTE: "验证集" and "对照集（留出）" are the SAME list — two logs, one
    # variable (`test`). The second line only re-reports the count, which read
    # like a second, separate set. State the source explicitly instead.
    _src = ("独立未调色目录" if val_mode == "ungraded-photos"
            else "参考片随机留出（旧逻辑）")
    print(f"  对照集（= 上方{_src}的 {len(test)} 张，不参与拟合）: 模式={val_mode}")
    prof_final, opt_report = profile_optimizer.optimize(prof_v1, train_val)
    with open(os.path.join(out_dir, "optimization_report.json"), "w", encoding="utf-8") as f:
        json.dump(opt_report, f, indent=2, ensure_ascii=False)

    print("[4/5] Validate on held-out test set (Phase 18) ...")
    # NOTE: validate() stamps profile["validation_status"] in place, so the
    # profile MUST be written to disk AFTER this call, never before.
    # Style centroid from the GRADED reference set (train_val) — the TARGET the
    # profile should reproduce on neutral content. Comparing render(neutral) to a
    # FIXED centroid (instead of to each test image's own already-graded features)
    # removes the double-grading artifact that previously inflated the loss: applying
    # the look onto an already-graded photo and comparing it back to that same graded
    # photo is meaningless. Now validation measures "does render(neutral) land near
    # the photographer's graded style?" — the real generalization check.
    from loss_function import extract_features
    style_target = profile_validator.average_features(
        [extract_features(im) for im in train_val])
    val_report = profile_validator.validate(prof_final, test, target_features=style_target)
    val_report["validation_mode"] = val_mode
    # Surface the dataset accounting in the report so the Studio UI (and any
    # downstream audit) can see exactly how many photos did what.
    val_report["dataset"] = {
        "discovered": n_total,
        "loaded": len(imgs),
        "train": len(split["train"]),
        "validation": len(split["validation"]),
        "optimize_images": len(train_val),
        "test": len(test),
    }
    print(f"  参与优化的照片: {len(train_val)} 张 | 对照集: {len(test)} 张")
    with open(os.path.join(out_dir, "profile_final.json"), "w", encoding="utf-8") as f:
        json.dump(prof_final, f, indent=2, ensure_ascii=False)
    with open(os.path.join(out_dir, "validation_report.json"), "w", encoding="utf-8") as f:
        json.dump(val_report, f, indent=2, ensure_ascii=False)
    with open(os.path.join(out_dir, "regression_baseline.json"), "w", encoding="utf-8") as f:
        json.dump({"overall_test_loss": val_report["overall_test_loss"],
                   "components": val_report["components"]}, f, indent=2, ensure_ascii=False)

    print("[5/5] Done.")
    print(f"  profile_final.json test_loss = {val_report['overall_test_loss']} (n_test={val_report['n_test']})")
    print(f"  validation_status = {prof_final.get('validation_status')}")
    print(f"  outputs in: {out_dir}")
    return prof_final


def main(argv=None) -> int:
    import argparse

    p = argparse.ArgumentParser(description="Offline Photographer Profile builder")
    p.add_argument("images_root")
    p.add_argument("--name", default="Photographer (auto)")
    p.add_argument("--out", default="profiles")
    p.add_argument("--analysis", default="dataset/analysis")
    p.add_argument("--validation", default=None,
                   help="directory of ordinary UN-GRADED photos for held-out validation "
                        "(avoids double-grading the already-graded reference set)")
    args = p.parse_args(argv)
    build(args.images_root, args.name, args.out, args.analysis,
          validation_root=args.validation)
    return 0


if __name__ == "__main__":
    sys.exit(main())
