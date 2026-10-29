#!/usr/bin/env python3
"""Fail-closed coverage/fixture checks; no real baseline PNG is created or changed."""

import importlib.util
import contextlib
import io
import json
from pathlib import Path
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
} | {"features-01-manual-connection-release.png", "features-02-platform-settings.png", "features-03-history-export.png"}
CURRENT_LOOKS = ("dark", "light", "simple-dark", "simple-light", "combined-320dp-200pct")
# Hand-checked against the unchanged 14 producer methods and the committed capture manifest.
REDESIGN_NAMES = {
    f"{look}-{scene}.png" for look in CURRENT_LOOKS
    for scene in ("02b-editor", "02c-step-editor", "02d-add-step", "06a-feedback-bubble")
} | {
    f"{look}-{scene}.png" for look in CURRENT_LOOKS[:4]
    for scene in ("05a-settings-appearance", "05b-settings-night", "05c-settings-permissions",
                  "05d-settings-backups", "05e-settings-notifications", "05f-settings-about")
} | {
    f"gaps-{look}-{scene}.png" for look in GAP_LOOK_FIXTURES
    for scene in ("00-profile-menu", "07-setup-demo", "07-setup-title")
}
WINDOW_NAMES = {
    f"{look}-{scene}-window1.png" for look in CURRENT_LOOKS
    for scene in ("02c-step-editor", "02d-add-step")
} | {
    f"gaps-{look}-{scene}-window1.png" for look in GAP_LOOK_FIXTURES
    for scene in ("00-profile-menu", "01-slot-pin", "05-checkin-dialog", "06-checkin-suggestion")
}
CURRENT_NAMES = LEGACY_NAMES | GAP_NAMES | REDESIGN_NAMES | WINDOW_NAMES


class ScreenshotEvidenceTest(unittest.TestCase):
    def test_feature_inventory_matches_current_capture_without_reintroducing_removed_handover(self):
        source = (REPOSITORY / "app/src/test/kotlin/io/github/willzy12h/nocturne/ScreenshotCapture.kt").read_text()
        import re
        actual = {name + ".png" for name in re.findall(r'shot\("(features-[^"]+)"', source)}
        expected = {name for name in screenshots.EXPECTED if name.startswith("features-")}
        self.assertEqual(actual, expected)
        self.assertIn("features-01-manual-connection-release.png", expected)
        self.assertNotIn("features-01-idle-handover.png", expected)

    def test_complete_current_inventory_accepts_212_captures_and_184_unbaselined_views(self):
        self.assertEqual(64, len(GAP_NAMES))
        self.assertEqual(GAP_NAMES, {path.name for path in (REPOSITORY / "docs/screenshots/gaps").glob("*.png")})
        self.assertEqual(212, len(CURRENT_NAMES))
        self.assertEqual(CURRENT_NAMES, screenshots.EXPECTED)
        self.assertEqual(212, len(screenshots.EXPECTED))
        self.assertEqual(28, len(screenshots.EXPECTED_BASELINE))
        self.assertEqual(184, len(screenshots.EXPECTED - screenshots.EXPECTED_BASELINE))
        self.assertEqual([], screenshots.validate_inventory(CURRENT_NAMES, screenshots.EXPECTED_BASELINE))

    def test_inventory_accepts_the_independently_archived_producer_names(self):
        import csv
        manifest = REPOSITORY / "docs/redesign/evidence/remediation/visual/capture-manifest.csv"
        with manifest.open() as stream:
            recorded = {row["file"] for row in csv.DictReader(stream)
                        if not row["file"].startswith(("p8-", "u06/"))}
        self.assertEqual(212, len(recorded))
        self.assertEqual(CURRENT_NAMES, recorded)
        self.assertEqual([], screenshots.validate_inventory(recorded, screenshots.EXPECTED_BASELINE))

    def test_every_current_capture_including_each_secondary_window_is_required(self):
        for name in sorted(CURRENT_NAMES):
            with self.subTest(name=name):
                self.assertEqual([f"Missing after capture: {name}"],
                                 screenshots.validate_inventory(CURRENT_NAMES - {name}, screenshots.EXPECTED_BASELINE))

    def test_gap_captures_cannot_become_fabricated_historical_baselines(self):
        for name in sorted(GAP_NAMES):
            with self.subTest(name=name):
                self.assertEqual([f"Unexpected historical baseline: {name}"],
                                 screenshots.validate_inventory(CURRENT_NAMES, screenshots.EXPECTED_BASELINE | {name}))

    def test_unexpected_window_index_is_rejected_even_when_its_parent_is_known(self):
        name = "dark-02c-step-editor-window2.png"
        self.assertEqual([f"Unexpected after capture (possibly stale): {name}"],
                         screenshots.validate_inventory(CURRENT_NAMES | {name}, screenshots.EXPECTED_BASELINE))

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
        # Current producer: the primary is the Activity, except the explicitly selected wake picker.
        # Other dialogs are separate window1 PNGs, rather than the historical primary-only views.
        size = (width * 3, 2673)
        if scene == "03-wake-picker":
            size = (960, 1305) if width == 320 else (1171, 1302)
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
                    Image.new("RGB", (data["pixelWidth"], data["pixelHeight"]), "#20252a").save(after / name)
                    # Test-only sidecars, never release evidence or historical baselines.
                    (after / name).with_suffix(".json").write_text(json.dumps(data))
            with patch("sys.argv", ["release_screenshots.py", "--after", str(after), "--baseline", str(baseline),
                                    "--output", str(output)]), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, screenshots.main())  # All other required current views are deliberately absent.
            report = json.loads((output / "report.json").read_text())
            self.assertEqual([f"Missing after capture: {name}" for name in sorted(CURRENT_NAMES - GAP_NAMES)], report["errors"])
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

    def test_scheduled_dark_appearance_capture_preserves_its_actual_theme_state(self):
        data = self.metadata(screenWidthDp=411, fontScale=1.0, pixelWidth=1233, theme="SCHEDULED")
        self.assertEqual([], self.validate_fixture(data, (1233, 2673), "dark-05a-settings-appearance.json"))
        for wrong in ("DARK", "LIGHT"):
            with self.subTest(theme=wrong):
                self.assertTrue(any("theme" in error for error in
                                    self.validate_fixture({**data, "theme": wrong}, (1233, 2673),
                                                          "dark-05a-settings-appearance.json")))

    def test_scheduled_theme_does_not_leak_into_other_settings_capture_states(self):
        for name, theme in (("dark-05b-settings-night.json", "DARK"),
                            ("light-05a-settings-appearance.json", "LIGHT")):
            data = self.metadata(screenWidthDp=411, fontScale=1.0, pixelWidth=1233, theme=theme)
            with self.subTest(name=name):
                self.assertEqual([], self.validate_fixture(data, (1233, 2673), name))
                self.assertTrue(any("theme" in error for error in
                                    self.validate_fixture({**data, "theme": "SCHEDULED"}, (1233, 2673), name)))

    def test_feature_captures_retain_the_current_default_simple_view_state(self):
        for scene in ("01-manual-connection-release", "02-platform-settings", "03-history-export"):
            name = f"features-{scene}.json"
            data = self.metadata(screenWidthDp=411, fontScale=1.0, pixelWidth=1233, simpleView=True)
            with self.subTest(name=name):
                self.assertEqual([], self.validate_fixture(data, (1233, 2673), name))
                self.assertTrue(any("simpleView" in error for error in
                                    self.validate_fixture({**data, "simpleView": False}, (1233, 2673), name)))

    def test_new_setup_views_require_real_mode_after_leaving_demo(self):
        for scene in ("07-setup-demo", "07-setup-title"):
            name = f"gaps-compact-light-{scene}.json"
            data = self.metadata(theme="LIGHT", demoMode=False)
            with self.subTest(name=name):
                self.assertEqual([], self.validate_fixture(data, name=name))
                self.assertTrue(any("demoMode" in error for error in
                                    self.validate_fixture({**data, "demoMode": True}, name=name)))

    def test_requested_native_wake_picker_window_can_be_narrower_than_activity(self):
        data = self.metadata(screenWidthDp=411, fontScale=1.0, pixelWidth=1171, pixelHeight=1302)
        self.assertEqual([], self.validate_fixture(data, (1171, 1302), "gaps-dark-03-wake-picker.json"))
        for size in ((1234, 1302), (1171, 2674), (0, 1302), (1171, 0)):
            with self.subTest(size=size):
                changed = {**data, "pixelWidth": size[0], "pixelHeight": size[1]}
                self.assertTrue(any("image size" in error for error in
                                    self.validate_fixture(changed, size, "gaps-dark-03-wake-picker.json")))

    def report_subset(self, images, sidecars):
        with tempfile.TemporaryDirectory(prefix="nocturne-current-capture-contract-") as directory:
            after, output = Path(directory) / "after", Path(directory) / "report"
            after.mkdir()
            for name, size in images.items():
                Image.new("RGB", size, "#20252a").save(after / name)
            for name, data in sidecars.items():
                (after / name).write_text(json.dumps(data))
            with patch("sys.argv", ["release_screenshots.py", "--after", str(after),
                                    "--baseline", str(REPOSITORY / "docs/screenshots/baseline"),
                                    "--output", str(output)]), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, screenshots.main())  # Other explicitly required captures are intentionally absent.
            return json.loads((output / "report.json").read_text())

    def test_secondary_window_uses_parent_scene_metadata_without_fabricated_sidecar(self):
        parent, child = "gaps-dark-00-profile-menu.png", "gaps-dark-00-profile-menu-window1.png"
        data = self.metadata(screenWidthDp=411, fontScale=1.0, pixelWidth=1233)
        report = self.report_subset({parent: (1233, 2673), child: (691, 624)},
                                    {"gaps-dark-00-profile-menu.json": data})
        self.assertEqual({parent, child}, {row["name"] for row in report["captures"]})
        self.assertEqual([f"Missing after capture: {name}" for name in sorted(CURRENT_NAMES - {parent, child})],
                         report["errors"])
        child_row = next(row for row in report["captures"] if row["name"] == child)
        self.assertEqual("gaps-dark-00-profile-menu.json", child_row["metadata_source"])
        self.assertEqual([691, 624], child_row["after_size"])
        self.assertEqual(1233, child_row["fixture"]["pixelWidth"])

    def test_secondary_window_cannot_bypass_parent_state_or_dimension_validation(self):
        parent, child = "gaps-dark-00-profile-menu.png", "gaps-dark-00-profile-menu-window1.png"
        valid = self.metadata(screenWidthDp=411, fontScale=1.0, pixelWidth=1233)
        for changes in ({"tag": "remediation"}, {"theme": "LIGHT"}, {"pixelWidth": 1232}):
            with self.subTest(changes=changes):
                report = self.report_subset({parent: (1233, 2673), child: (691, 624)},
                                            {"gaps-dark-00-profile-menu.json": {**valid, **changes}})
                relevant = [error for error in report["errors"] if not error.startswith("Missing after capture:")]
                self.assertTrue(any(next(iter(changes)) in error for error in relevant), relevant)

    def test_secondary_window_requires_its_actual_primary_image(self):
        child = "gaps-dark-00-profile-menu-window1.png"
        data = self.metadata(screenWidthDp=411, fontScale=1.0, pixelWidth=1233)
        report = self.report_subset({child: (691, 624)}, {"gaps-dark-00-profile-menu.json": data})
        self.assertTrue(any("primary" in error and child in error for error in report["errors"]), report["errors"])

    def test_secondary_popup_bounds_and_fullscreen_sheet_dimensions_remain_enforced(self):
        for stem, size in (("gaps-dark-00-profile-menu", (1234, 624)),
                           ("gaps-dark-01-slot-pin", (960, 2674)),
                           ("dark-02c-step-editor", (960, 1728))):
            parent, child = stem + ".png", stem + "-window1.png"
            data = self.metadata(screenWidthDp=411, fontScale=1.0, pixelWidth=1233)
            report = self.report_subset({parent: (1233, 2673), child: size}, {stem + ".json": data})
            with self.subTest(child=child, size=size):
                self.assertTrue(any(child in error and "image size" in error for error in report["errors"]),
                                report["errors"])

    def test_truncated_png_is_rejected_even_when_all_pixels_can_be_decoded(self):
        with tempfile.TemporaryDirectory(prefix="nocturne-broken-capture-") as directory:
            after, output = Path(directory) / "after", Path(directory) / "report"
            after.mkdir()
            name = "features-03-history-export.png"
            image = after / name
            Image.new("RGB", (1233, 2673), "#20252a").save(image)
            image.write_bytes(image.read_bytes()[:-12])  # Complete pixel data, missing terminal IEND.
            data = self.metadata(screenWidthDp=411, fontScale=1.0, pixelWidth=1233)
            image.with_suffix(".json").write_text(json.dumps(data))
            with patch("sys.argv", ["release_screenshots.py", "--after", str(after),
                                    "--baseline", str(REPOSITORY / "docs/screenshots/baseline"),
                                    "--output", str(output)]), contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises((OSError, SyntaxError)):
                    screenshots.main()


if __name__ == "__main__":
    unittest.main()
