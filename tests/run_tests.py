#!/usr/bin/env python3
"""Run the PhotographerCamera smoke + pipeline tests.

Usage:
    python tests/run_tests.py               # full suite, incl. Studio HTTP smoke
    python tests/run_tests.py --no-studio   # skip the Studio HTTP smoke
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))

import tests.test_profile_schema as t_schema  # noqa: E402
import tests.test_dataset_loader as t_dataset  # noqa: E402
import tests.test_dataset as t_dataset_phase12  # noqa: E402
import tests.test_style as t_style  # noqa: E402
import tests.test_renderer as t_renderer  # noqa: E402
import tests.test_offline_pipeline as t_offline  # noqa: E402
import tests.test_shader_equivalence as t_eq  # noqa: E402


def main() -> int:
    # Phase 0
    t_schema.test_default_profile_valid()
    t_schema.test_out_of_range_rejected()
    t_schema.test_missing_required_rejected()
    t_dataset.test_discover_and_read()
    # Phase 1-2
    t_dataset_phase12.test_analyzer_writes_metadata_and_manifest()
    t_dataset_phase12.test_cleaner_classifies_problem_images()
    # Phase 3-13
    t_style.test_style_analysis_emits_all_phase_files()
    # Phase 22 (renderer)
    t_renderer.test_identity_profile_is_nearly_passthrough()
    t_renderer.test_exposure_bias_brightens()
    t_renderer.test_vignette_darkens_corners()
    # Phase 14-18 (offline builder closure)
    t_offline.test_offline_builder_closes_loop()
    # Phase 25 (GLSL) equivalence vs validated CPU reference
    t_eq.test_glsl_matches_cpu_reference()
    t_eq.test_non_square_and_extreme_images()
    t_eq.test_identity_passthrough()
    t_eq.test_demo_profile_equivalence()
    # Android static cross-checks (JSON<->Kotlin, uniforms, shader assets, ranges)
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "static_cross_check",
        os.path.join(os.path.dirname(__file__), "..", "tools", "static_cross_check.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)  # exits non-zero and raises SystemExit on failure

    # Studio workbench end-to-end HTTP smoke (subprocess; ~1 min)
    if "--no-studio" not in sys.argv:
        subprocess.run([sys.executable, os.path.join(HERE, "test_studio_smoke.py")],
                       check=True)
    else:
        print("  (studio smoke skipped: --no-studio)")

    print("\nALL PHOTOGRAPHERCAMERA TESTS PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
