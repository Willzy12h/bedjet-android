#!/usr/bin/env python3
"""Exercise the exact release inventory with an independent merged-manifest fixture."""

import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET


SCRIPT = Path(__file__).with_name("release_manifest.py")
FIXTURE = Path(__file__).with_name("fixtures") / "release-manifest-reviewed.xml"
A = "{http://schemas.android.com/apk/res/android}"
PACKAGE = "io.github.willzy12h.nocturne"
spec = importlib.util.spec_from_file_location("release_manifest", SCRIPT)
manifest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manifest)


class ReleaseManifestTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="nocturne-manifest-test-")
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "AndroidManifest.xml"
        self.tree = ET.parse(FIXTURE)
        self.root = self.tree.getroot()
        self.application = self.root.find("application")

    def component(self, name):
        return next(node for node in self.application if node.get(A + "name") == name)

    def audit(self):
        self.tree.write(self.path, encoding="utf-8", xml_declaration=True)
        return manifest.audit(self.path)

    def assertRejected(self, message):
        report = self.audit()
        self.assertFalse(report["pass"], report)
        self.assertTrue(any(message in issue for issue in report["issues"]), report["issues"])

    def test_reviewed_automatic_start_and_health_inventory_passes(self):
        report = self.audit()
        self.assertTrue(report["pass"], report["issues"])
        self.assertEqual(41, len(report["components"]))
        self.assertEqual(2, sum(row["widget"] for row in report["components"]))
        clock = next(row for row in report["components"] if row["name"].endswith(".PlatformClockReceiver"))
        self.assertIn("android.app.action.NEXT_ALARM_CLOCK_CHANGED", clock["actions"])
        self.assertIn("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED", clock["actions"])

    def test_automatic_start_receiver_must_remain_private(self):
        self.component(".platform.AutoStartReceiver").set(A + "exported", "true")
        self.assertRejected("AutoStartReceiver: expected exported=False")

    def test_missing_automatic_start_receiver_is_not_silently_accepted(self):
        self.application.remove(self.component(".platform.AutoStartReceiver"))
        self.assertRejected("Expected audited component missing: receiver " + PACKAGE + ".platform.AutoStartReceiver")

    def test_retired_network_and_advertising_permissions_are_rejected(self):
        for permission in ("android.permission.INTERNET", "android.permission.BLUETOOTH_ADVERTISE"):
            with self.subTest(permission=permission):
                node = ET.SubElement(self.root, "uses-permission", {A + "name": permission})
                try:
                    self.assertRejected("added=['" + permission + "']")
                finally:
                    self.root.remove(node)

    def test_health_write_permission_is_required_by_the_reviewed_inventory(self):
        node = next(node for node in self.root if node.get(A + "name") == "android.permission.health.WRITE_SLEEP")
        self.root.remove(node)
        self.assertRejected("missing=['android.permission.health.WRITE_SLEEP']")

    def test_new_unreviewed_permission_or_component_still_fails(self):
        node = ET.SubElement(self.root, "uses-permission", {A + "name": "android.permission.CAMERA"})
        self.assertRejected("added=['android.permission.CAMERA']")
        self.root.remove(node)
        ET.SubElement(self.application, "receiver", {A + "name": ".UnreviewedReceiver", A + "exported": "false"})
        self.assertRejected("Unreviewed component: receiver " + PACKAGE + ".UnreviewedReceiver")

    def test_system_bind_permissions_remain_required(self):
        for name in (".platform.ChargerJobService", "androidx.glance.appwidget.GlanceRemoteViewsService",
                     "androidx.work.impl.background.systemjob.SystemJobService"):
            with self.subTest(name=name):
                node = self.component(name)
                permission = node.attrib.pop(A + "permission")
                try:
                    self.assertRejected("permission=" + repr(permission) + "; found exported=True, permission=None")
                finally:
                    node.set(A + "permission", permission)

    def test_provider_authority_and_uri_grants_remain_checked(self):
        node = self.component("androidx.startup.InitializationProvider")
        for attribute, value in (("authorities", "unreviewed.provider"), ("grantUriPermissions", "true")):
            with self.subTest(attribute=attribute):
                original = node.get(A + attribute)
                node.set(A + attribute, value)
                try:
                    self.assertRejected("AndroidX startup provider authorities/URI-grant policy changed")
                finally:
                    if original is None:
                        del node.attrib[A + attribute]
                    else:
                        node.set(A + attribute, original)

    def test_cli_reports_success_and_fails_closed_for_an_export_change(self):
        output = Path(self.directory.name) / "report"
        for exported, expected_code in (("false", 0), ("true", 1)):
            with self.subTest(exported=exported):
                self.component(".platform.AutoStartReceiver").set(A + "exported", exported)
                self.tree.write(self.path, encoding="utf-8", xml_declaration=True)
                result = subprocess.run([sys.executable, "-B", str(SCRIPT), "--manifest", str(self.path),
                                         "--output", str(output)], text=True, capture_output=True)
                self.assertEqual(expected_code, result.returncode, result.stdout + result.stderr)
                report = json.loads((output / "report.json").read_text())
                self.assertEqual(expected_code == 0, report["pass"])
                self.assertIn("41 components", (output / "report.md").read_text())


if __name__ == "__main__":
    unittest.main()
