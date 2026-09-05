"""Tests for tools/profile_schema.py (no third-party deps required)."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from profile_schema import validate_profile, default_profile  # noqa: E402


def test_default_profile_valid():
    prof = default_profile("Test")
    ok, errors = validate_profile(prof)
    assert ok, errors


def test_out_of_range_rejected():
    prof = default_profile("Test")
    prof["exposure"]["bias"] = 5.0  # exceeds max 2
    ok, errors = validate_profile(prof)
    assert not ok
    assert any("exposure.bias" in e for e in errors)


def test_missing_required_rejected():
    prof = default_profile("Test")
    del prof["shadow"]
    ok, errors = validate_profile(prof)
    assert not ok
    assert any(".shadow" in e for e in errors)


if __name__ == "__main__":
    test_default_profile_valid()
    test_out_of_range_rejected()
    test_missing_required_rejected()
    print("test_profile_schema: PASS")
