#!/usr/bin/env python3
"""Fail-closed coverage/fixture checks; no real baseline PNG is created or changed."""

import importlib.util
import contextlib
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

from PIL import Image


spec = importlib.util.spec_from_file_location("release_screenshots", Path(__file__).with_name("release_screenshots.py"))
screenshots = importlib.util.module_from_spec(spec)
spec.loader.exec_module(screenshots)

REPOSITORY = Path(__file__).resolve().parents[2]
# These are the existing captureGaps invocations and shot names in ScreenshotCapture.kt.
GAP_LOOK_FIXTURES = {
    "dark": ("DARK", False, 411, 1.0),
    "light": ("LIGHT", False, 411, 1.0),
    "simple-dark": ("DARK", True, 411, 1.0),
    "simple-light": ("LIGHT", True, 411, 1.0),
    "compact-dark": ("DARK", False, 320, 2.0),
    "compact-light": ("LIGHT", False, 320, 2.0),
    "compact-simple-dark": ("DARK", True, 320, 2.0),
    "compact-simple-light": ("LIGHT", True, 320, 2.0),
}
GAP_SCENES = (
    "01-slot-pin", "02-wake-expiry", "03-wake-picker", "04-resume",
    "05-checkin-dialog", "06-checkin-suggestion", "07-setup-chooser", "08-unreadable-banner",
)
GAP_NAMES = {f"gaps-{look}-{scene}.png" for look in GAP_LOOK_FIXTURES for scene in GAP_SCENES}
LEGACY_NAMES = {
    f"{look}-{scene}.png"
    for look in ("dark", "light", "simple-dark", "simple-light", "combined-320dp-200pct")
    for scene in ("01-tonight", "01b-tonight-lower", "02-profiles", "03-remote",
                  "04-history", "05-settings", "06-tonight-running")
} | {"features-01-idle-handover.png", "features-02-platform-settings.png", "features-03-history-export.png"}


class ScreenshotEvidenceTest(unittest.TestCase):
    def test_complete_known_inventory_accepts_all_102_captures_and_74_unbaselined_views(self):
        self.assertEqual(64, len(GAP_NAMES))
        self.assertEqual(GAP_NAMES, {path.name for path in (REPOSITORY / "docs/screenshots/gaps").glob("*.png")})
        self.assertEqual(LEGACY_NAMES | GAP_NAMES, screenshots.EXPECTED)
        self.assertEqual(102, len(screenshots.EXPECTED))
        self.assertEqual(28, len(screenshots.EXPECTED_BASELINE))
        self.assertEqual(74, len(screenshots.EXPECTED - screenshots.EXPECTED_BASELINE))
        self.assertEqual([], screenshots.validate_inventory(screenshots.EXPECTED, screenshots.EXPECTED_BASELINE))

    def test_every_existing_gap_capture_is_required(self):
        for name in sorted(GAP_NAMES):
            with self.subTest(name=name):
                self.assertEqual([f"Missing after capture: {name}"],
                                 screenshots.validate_inventory((LEGACY_NAMES | GAP_NAMES) - {name}, screenshots.EXPECTED_BASELINE))

    def test_gap_captures_cannot_become_fabricated_historical_baselines(self):
        for name in sorted(GAP_NAMES):
            with self.subTest(name=name):
                self.assertEqual([f"Unexpected historical baseline: {name}"],
                                 screenshots.validate_inventory(LEGACY_NAMES | GAP_NAMES, screenshots.EXPECTED_BASELINE | {name}))

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

    def validate_fixture(self, data, size=(960, 2673), name="combined-320dp-200pct-01-tonight.json"):
        with tempfile.TemporaryDirectory(prefix="nocturne-screenshot-helper-test-") as directory:
            path = Path(directory) / name
            path.write_text(json.dumps(data))
            return screenshots.validate_metadata(path, size)[1]

    def gap_fixture(self, look, scene):
        theme, simple, width, scale = GAP_LOOK_FIXTURES[look]
        name = f"gaps-{look}-{scene}.png"
        # Real archived images establish that dialogs have their own window sizes. They remain untouched.
        with Image.open(REPOSITORY / "docs/screenshots/gaps" / name) as image:
            size = image.size
        data = self.metadata(theme=theme, simpleView=simple, screenWidthDp=width, fontScale=scale,
                             pixelWidth=size[0], pixelHeight=size[1],
                             demoMode=scene not in ("07-setup-chooser", "08-unreadable-banner"))
        return name, data, size

    def test_every_gap_look_and_scene_accepts_its_actual_fixture_and_window(self):
        for look in GAP_LOOK_FIXTURES:
            for scene in GAP_SCENES:
                name, data, size = self.gap_fixture(look, scene)
                with self.subTest(name=name):
                    self.assertEqual([], self.validate_fixture(data, size, name.replace(".png", ".json")))

    def test_gap_metadata_does_not_substitute_a_name_for_actual_state(self):
        for look in GAP_LOOK_FIXTURES:
            for scene in GAP_SCENES:
                name, data, size = self.gap_fixture(look, scene)
                changes = {"theme": "DARK" if data["theme"] == "LIGHT" else "LIGHT",
                           "simpleView": not data["simpleView"], "demoMode": not data["demoMode"],
                           "screenWidthDp": 411 if data["screenWidthDp"] == 320 else 320,
                           "fontScale": 1.0 if data["fontScale"] == 2.0 else 2.0,
                           "pixelWidth": size[0] + 1, "pixelHeight": size[1] + 1}
                for key, value in changes.items():
                    with self.subTest(name=name, key=key):
                        errors = self.validate_fixture({**data, key: value}, size, name.replace(".png", ".json"))
                        self.assertTrue(any(key in error for error in errors), errors)

    def test_gap_activity_dimensions_stay_exact_and_dialog_windows_stay_bounded(self):
        for scene, size in (("02-wake-expiry", (1233, 1668)), ("01-slot-pin", (1230, 1668)),
                            ("03-wake-picker", (1233, 2674)), ("05-checkin-dialog", (1233, 0))):
            name, data, _ = self.gap_fixture("dark", scene)
            data.update(pixelWidth=size[0], pixelHeight=size[1])
            with self.subTest(name=name, size=size):
                self.assertTrue(any("image size" in error for error in
                                    self.validate_fixture(data, size, name.replace(".png", ".json"))))

    def test_gap_report_labels_all_64_unbaselined_and_does_not_create_baselines(self):
        baseline = REPOSITORY / "docs/screenshots/baseline"
        original = {path.name: screenshots.sha256(path) for path in baseline.glob("*.png")}
        with tempfile.TemporaryDirectory(prefix="nocturne-screenshot-report-test-") as directory:
            after, output = Path(directory) / "after", Path(directory) / "report"
            after.mkdir()
            for look in GAP_LOOK_FIXTURES:
                for scene in GAP_SCENES:
                    name, data, _ = self.gap_fixture(look, scene)
                    shutil.copyfile(REPOSITORY / "docs/screenshots/gaps" / name, after / name)
                    # Test-only sidecars, never release evidence or historical baselines.
                    (after / name).with_suffix(".json").write_text(json.dumps(data))
            with patch("sys.argv", ["release_screenshots.py", "--after", str(after), "--baseline", str(baseline),
                                    "--output", str(output)]), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, screenshots.main())  # The 38 required legacy after views are deliberately absent.
            report = json.loads((output / "report.json").read_text())
            self.assertEqual([f"Missing after capture: {name}" for name in sorted(LEGACY_NAMES)], report["errors"])
            self.assertEqual(GAP_NAMES, {row["name"] for row in report["captures"]})
            self.assertEqual({"UNBASELINED"}, {row["status"] for row in report["captures"]})
            self.assertEqual(64, report["unbaselined"])
            self.assertEqual(0, report["compared"])
            self.assertEqual(0, report["missing_historical_baselines"])
        self.assertEqual(original, {path.name: screenshots.sha256(path) for path in baseline.glob("*.png")})

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
