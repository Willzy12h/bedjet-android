#!/usr/bin/env python3
"""Fail-closed coverage/fixture checks; no real baseline PNG is created or changed."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("release_screenshots", Path(__file__).with_name("release_screenshots.py"))
screenshots = importlib.util.module_from_spec(spec)
spec.loader.exec_module(screenshots)


class ScreenshotEvidenceTest(unittest.TestCase):
    def test_complete_known_inventory_accepts_exactly_ten_unbaselined_views(self):
        self.assertEqual(38, len(screenshots.EXPECTED))
        self.assertEqual(28, len(screenshots.EXPECTED_BASELINE))
        self.assertEqual(10, len(screenshots.EXPECTED - screenshots.EXPECTED_BASELINE))
        self.assertEqual([], screenshots.validate_inventory(screenshots.EXPECTED, screenshots.EXPECTED_BASELINE))

    def test_deleted_historical_baseline_fails_even_when_all_after_images_exist(self):
        baseline = screenshots.EXPECTED_BASELINE - {"dark-05-settings.png"}
        self.assertEqual(["Missing historical baseline: dark-05-settings.png"],
                         screenshots.validate_inventory(screenshots.EXPECTED, baseline))

    def test_new_capture_cannot_become_a_fabricated_historical_baseline(self):
        baseline = screenshots.EXPECTED_BASELINE | {"combined-320dp-200pct-01-tonight.png"}
        self.assertEqual(["Unexpected historical baseline: combined-320dp-200pct-01-tonight.png"],
                         screenshots.validate_inventory(screenshots.EXPECTED, baseline))

    def test_missing_after_capture_and_stale_capture_both_fail(self):
        actual = (screenshots.EXPECTED - {"light-04-history.png"}) | {"old-run.png"}
        self.assertEqual(["Missing after capture: light-04-history.png",
                          "Unexpected after capture (possibly stale): old-run.png"],
                         screenshots.validate_inventory(actual, screenshots.EXPECTED_BASELINE))

    def metadata(self, **changes):
        data = {
            "renderer": "Robolectric native graphics", "sdk": 34, "tag": "after",
            "clockInstant": "2026-10-22T22:30:00Z", "clockZone": "Europe/London",
            "screenWidthDp": 320, "screenHeightDp": 891, "fontScale": 2.0,
            "densityDpi": 480, "pixelWidth": 960, "pixelHeight": 2673,
            "demoMode": True, "theme": "DARK", "simpleView": False,
        }
        data.update(changes)
        return data

    def validate_fixture(self, data, size=(960, 2673)):
        with tempfile.TemporaryDirectory(prefix="nocturne-screenshot-helper-test-") as directory:
            path = Path(directory) / "combined-320dp-200pct-01-tonight.json"
            path.write_text(json.dumps(data))
            return screenshots.validate_metadata(path, size)[1]

    def test_actual_combined_width_font_and_pinned_clock_are_accepted(self):
        self.assertEqual([], self.validate_fixture(self.metadata()))

    def test_wrong_clock_zone_and_tag_are_rejected(self):
        for key, value in (("clockInstant", "2026-10-23T06:34:00Z"), ("clockZone", "UTC"), ("tag", "baseline")):
            with self.subTest(key=key):
                self.assertTrue(any(key in error for error in self.validate_fixture(self.metadata(**{key: value}))))

    def test_combined_label_does_not_substitute_for_actual_width_or_large_font(self):
        for key, value in (("screenWidthDp", 411), ("fontScale", 1.0), ("demoMode", False)):
            with self.subTest(key=key):
                self.assertTrue(any(key in error for error in self.validate_fixture(self.metadata(**{key: value}))))

    def test_matching_metadata_cannot_hide_wrong_png_dimensions(self):
        errors = self.validate_fixture(self.metadata(pixelWidth=1233), size=(1233, 2673))
        self.assertTrue(any("image size expected (960, 2673)" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
