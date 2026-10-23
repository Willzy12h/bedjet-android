#!/usr/bin/env python3
"""Failure-path tests for the release serialization gate; no SDK is needed."""

import importlib.util
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location(
    "release_serialization", Path(__file__).with_name("release_serialization.py")
)
gate = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = gate
spec.loader.exec_module(gate)


def classfile(name, *, serializable=True, flags=0x21):
    """Minimal JVM class with an actual RuntimeInvisibleAnnotations attribute."""
    def utf8(value):
        data = value.encode()
        return b"\x01" + struct.pack(">H", len(data)) + data

    pool = (
        utf8(name.replace(".", "/")) + b"\x07\x00\x01"
        + utf8("java/lang/Object") + b"\x07\x00\x03"
        + utf8("RuntimeInvisibleAnnotations")
        + utf8("Lkotlinx/serialization/Serializable;")
    )
    header = struct.pack(">IHHH", 0xCAFEBABE, 0, 61, 7)
    body = struct.pack(">HHHHHHH", flags, 2, 4, 0, 0, 0, int(serializable))
    annotation = struct.pack(">HIHHH", 5, 6, 1, 6, 0) if serializable else b""
    return header + pool + body + annotation


def dexfile(defined, referenced=(), map_id="fixture"):
    """A small DEX table fixture: references deliberately differ from definitions."""
    descriptors = ["L" + name.replace(".", "/") + ";" for name in [*defined, *referenced]]
    marker = '~~R8' + json.dumps({"pg-map-id": map_id, "compilation-mode": "release"})
    strings = [*descriptors, marker]
    string_ids_offset = 112
    type_ids_offset = string_ids_offset + 4 * len(strings)
    class_defs_offset = type_ids_offset + 4 * len(descriptors)
    data_offset = class_defs_offset + 32 * len(defined)
    data = bytearray(data_offset)
    data[:8] = b"dex\n039\x00"
    struct.pack_into("<II", data, 36, 112, 0x12345678)
    struct.pack_into("<II", data, 56, len(strings), string_ids_offset)
    struct.pack_into("<II", data, 64, len(descriptors), type_ids_offset)
    struct.pack_into("<II", data, 96, len(defined), class_defs_offset)
    for index, value in enumerate(strings):
        struct.pack_into("<I", data, string_ids_offset + index * 4, len(data))
        # All fixture strings fit a one-byte ULEB128 UTF-16 length.
        encoded = value.encode()
        data += bytes((len(value),)) + encoded + b"\x00"
    for index in range(len(descriptors)):
        struct.pack_into("<I", data, type_ids_offset + index * 4, index)
    for index in range(len(defined)):
        struct.pack_into("<I", data, class_defs_offset + index * 32, index)
    struct.pack_into("<I", data, 32, len(data))
    return bytes(data)


class InventoryTest(unittest.TestCase):
    def test_inventories_private_nested_enum_and_abstract_models(self):
        # Omitting private, enum, or sealed-base classes would leave persisted data unchecked.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, flags in (("sample.Store$SnapshotFile", 0x21),
                                ("sample.Mode", 0x4031),
                                ("sample.Choice", 0x421)):
                (root / (name + ".class")).write_bytes(classfile(name, flags=flags))
            (root / "Unrelated.class").write_bytes(classfile("sample.Unrelated", serializable=False))
            models = gate.inventory_classes([root])
        self.assertEqual(set(models), {"sample.Store$SnapshotFile", "sample.Mode", "sample.Choice"})
        self.assertEqual(models["sample.Mode"].kind, "enum")
        self.assertEqual(models["sample.Choice"].kind, "abstract")

    def test_new_model_without_round_trip_fixture_fails(self):
        with self.assertRaisesRegex(gate.VerificationError, "Missing round-trip fixtures.*sample.New"):
            gate.check_fixture_coverage({"sample.New": object()}, {})

    def test_removed_model_with_stale_fixture_fails(self):
        with self.assertRaisesRegex(gate.VerificationError, "Unknown fixture models.*sample.Old"):
            gate.check_fixture_coverage({}, {"sample.Old": [{}]})

    def test_empty_fixture_is_not_coverage(self):
        with self.assertRaisesRegex(gate.VerificationError, "No fixture cases.*sample.New"):
            gate.check_fixture_coverage({"sample.New": object()}, {"sample.New": []})


class ApkStructureTest(unittest.TestCase):
    def test_dex_references_are_not_definitions(self):
        # Grepping descriptors would incorrectly accept a removed serializer.
        defined, _ = gate.parse_dex(dexfile(["a"], ["b"]))
        self.assertEqual(defined, {"a"})

    def test_checks_every_dex_and_the_r8_mapping_id(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "candidate.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", dexfile(["a"]))
                archive.writestr("classes2.dex", dexfile(["b"]))
            defined, _ = gate.read_apk(apk, "# pg_map_id: fixture\n")
        self.assertEqual(defined, {"a", "b"})

    def test_mapping_from_another_build_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "candidate.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", dexfile(["a"], map_id="different"))
            with self.assertRaisesRegex(gate.VerificationError, "mapping ID"):
                gate.read_apk(apk, "# pg_map_id: fixture\n")

    def test_mapping_entry_without_a_packaged_serializer_fails(self):
        with self.assertRaisesRegex(gate.VerificationError, "No packaged implementation.*Foo\\$\\$serializer"):
            gate.verify_structure(
                {"sample.Foo": object()}, {"sample.Foo": {"serializer": "sample.Foo$$serializer"}},
                "sample.Foo -> a:\nsample.Foo$$serializer -> b:\n", "sample.Foo$$serializer\n", {"a"},
            )

    def test_horizontal_merge_does_not_require_original_class_names(self):
        result = gate.verify_structure(
            {"sample.Foo": object()}, {"sample.Foo": {"serializer": "sample.Foo$$serializer"}},
            "sample.Foo -> a:\nsample.Foo$$serializer -> b:\nsample.Other$$serializer -> b:\n",
            "", {"a", "b"},
        )
        self.assertEqual(result["sample.Foo"]["serializer"]["definedClass"], "b")

    def test_inlined_serializer_member_has_a_defined_owner(self):
        result = gate.verify_structure(
            {"sample.Foo": object()}, {"sample.Foo": {"serializer": "sample.Foo$$serializer"}},
            "sample.Foo -> a:\nsample.Helper -> b:\n"
            "    1:2:void sample.Foo$$serializer.serialize(java.lang.Object):20:21 -> c\n",
            "sample.Foo$$serializer\n", {"a", "b"},
        )
        self.assertEqual(result["sample.Foo"]["serializer"]["evidence"], "mapped-member")

    def test_enum_uses_its_real_shared_serializer(self):
        result = gate.verify_structure(
            {"sample.Mode": object()},
            {"sample.Mode": {"serializer": "kotlinx.serialization.internal.EnumSerializer"}},
            "sample.Mode -> a:\nkotlinx.serialization.internal.EnumSerializer -> b:\n", "", {"a", "b"},
        )
        self.assertEqual(result["sample.Mode"]["serializer"]["definedClass"], "b")


class RoundTripEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "result.xml"
        self.models = {"sample.Foo": gate.Model("sample.Foo", "class", "a" * 64, "Foo.class")}
        self.fixtures = {"sample.Foo": [{"value": 17}]}
        self.record = {"model": "sample.Foo", "classSha256": "a" * 64, "fixtureSha256": "b" * 64,
                       "serializer": "sample.Foo$$serializer", "kind": "CLASS", "cases": 1}

    def write_result(self, records, failure=False):
        root = ET.Element("testsuite", name="io.github.willzy12h.nocturne.releasegate.ReleaseSerializationTest",
                          tests="2", failures=str(int(failure)), errors="0", skipped="0")
        case = ET.SubElement(root, "testcase", name="allStoredModelsRoundTrip")
        ET.SubElement(root, "testcase", name="snapshotStorePersistsItsPrivateWrapper")
        if failure:
            ET.SubElement(case, "failure").text = "Deliberate round-trip failure"
        ET.SubElement(root, "system-out").text = "\n".join("NOCTURNE_SERIALIZATION " + json.dumps(row) for row in records)
        ET.ElementTree(root).write(self.path)

    def verify(self):
        return gate.read_round_trips(self.path, self.models, self.fixtures, "b" * 64)

    def test_complete_passing_execution_is_accepted(self):
        self.write_result([self.record])
        self.assertEqual(self.verify(), {"sample.Foo": self.record})

    def test_failed_test_does_not_pass_because_it_printed_records(self):
        self.write_result([self.record], failure=True)
        with self.assertRaisesRegex(gate.VerificationError, "JVM release test did not pass"):
            self.verify()

    def test_missing_execution_record_fails(self):
        self.write_result([])
        with self.assertRaisesRegex(gate.VerificationError, "Executed model inventory"):
            self.verify()

    def test_result_from_older_compiled_model_fails(self):
        self.write_result([{**self.record, "classSha256": "c" * 64}])
        with self.assertRaisesRegex(gate.VerificationError, "Stale JVM class evidence"):
            self.verify()

    def test_result_from_older_fixture_fails(self):
        self.write_result([{**self.record, "fixtureSha256": "c" * 64}])
        with self.assertRaisesRegex(gate.VerificationError, "Stale fixture evidence"):
            self.verify()

    def test_partially_executed_fixture_set_fails(self):
        self.write_result([{**self.record, "cases": 0}])
        with self.assertRaisesRegex(gate.VerificationError, "Fixture case count"):
            self.verify()

    def test_duplicate_model_evidence_fails(self):
        self.write_result([self.record, self.record])
        with self.assertRaisesRegex(gate.VerificationError, "Duplicate execution record"):
            self.verify()

    def test_cli_produces_hash_bound_separate_evidence(self):
        root = Path(self.directory.name)
        classes = root / "classes"
        classes.mkdir()
        compiled = classfile("sample.Foo")
        (classes / "Foo.class").write_bytes(compiled)
        fixture = root / "fixtures.json"
        fixture.write_text(json.dumps({"format": 1, "models": self.fixtures}))
        self.write_result([{**self.record, "classSha256": hashlib.sha256(compiled).hexdigest(),
                            "fixtureSha256": hashlib.sha256(fixture.read_bytes()).hexdigest()}])
        apk = root / "candidate.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("classes.dex", dexfile(["a", "b"]))
        mapping = root / "mapping.txt"
        mapping.write_text("# pg_map_id: fixture\nsample.Foo -> a:\nsample.Foo$$serializer -> b:\n")
        usage = root / "usage.txt"
        usage.write_text("")
        report = root / "report.json"
        result = subprocess.run([sys.executable, "-B", str(Path(gate.__file__)),
            "--apk", str(apk), "--mapping", str(mapping), "--usage", str(usage),
            "--junit", str(self.path), "--fixtures", str(fixture), "--classes", str(classes),
            "--report", str(report)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(report.exists(), "Gate did not create the evidence report")
        evidence = json.loads(report.read_text())
        self.assertEqual(evidence["status"], "passed")
        self.assertEqual(evidence["modelCount"], 1)
        self.assertFalse(evidence["apkRuntimeExecuted"])
        self.assertEqual(evidence["artifacts"]["apk"]["sha256"], hashlib.sha256(apk.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
