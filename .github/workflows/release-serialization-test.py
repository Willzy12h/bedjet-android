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

    def test_moved_serializer_member_is_not_a_packaged_serializer(self):
        # A constructor, descriptor or one inlined direction cannot prove a codec survives.
        for method in ("<init>", "getDescriptor", "serialize", "deserialize"):
            with self.subTest(method=method), self.assertRaisesRegex(
                    gate.VerificationError, "No packaged implementation.*Foo\\$\\$serializer"):
                gate.verify_structure(
                    {"sample.Foo": object()}, {"sample.Foo": {"serializer": "sample.Foo$$serializer"}},
                    "sample.Foo -> a:\nsample.Helper -> b:\n"
                    f"    1:2:void sample.Foo$$serializer.{method}(java.lang.Object):20:21 -> c\n",
                    "sample.Foo$$serializer\n", {"a", "b"},
                )

    def test_moved_members_in_both_directions_still_require_a_defined_serializer(self):
        with self.assertRaisesRegex(gate.VerificationError, "No packaged implementation.*Foo\\$\\$serializer"):
            gate.verify_structure(
                {"sample.Foo": object()}, {"sample.Foo": {"serializer": "sample.Foo$$serializer"}},
                "sample.Foo -> a:\nsample.Helper -> b:\n"
                "    1:2:void sample.Foo$$serializer.serialize(java.lang.Object):20:21 -> c\n"
                "    1:2:java.lang.Object sample.Foo$$serializer.deserialize(java.lang.Object):30:31 -> d\n",
                "sample.Foo$$serializer\n", {"a", "b"},
            )

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


class ShrunkRuntimeEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.path = self.root / "shrunk.json"
        self.input_bytes = classfile("sample.Foo")
        self.output_bytes = classfile("a")
        self.models = {"sample.Foo": gate.Model("sample.Foo", "class", hashlib.sha256(self.input_bytes).hexdigest(), "Foo.class")}
        self.fixtures = {"sample.Foo": [{"value": 17}]}
        self.jar = self.root / "shrunk.jar"
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("a.class", self.output_bytes)
        self.input_jar = self.root / "input.jar"
        with zipfile.ZipFile(self.input_jar, "w") as archive:
            archive.writestr("sample/Foo.class", self.input_bytes)
            archive.writestr("sample/Unused.class", classfile("sample.Unused", serializable=False))
        self.mapping = self.root / "mapping.txt"
        self.mapping.write_text("# compiler_version: 8.10.24\nsample.Foo -> a:\n")
        self.usage = self.root / "usage.txt"
        self.usage.write_text("sample.Unused\n")
        self.log = self.root / "runtime.log"
        self.record = {"model": "sample.Foo", "executedClass": "a",
                       "classSha256": hashlib.sha256(self.output_bytes).hexdigest(),
                       "fixtureSha256": "b" * 64, "serializer": "s", "cases": 1}
        self.log.write_text("NOCTURNE_SERIALIZATION " + json.dumps(self.record) + "\n"
                            "NOCTURNE_SERIALIZATION_COMPLETE both fixture methods passed\n")
        self.report = {
            "format": 1, "status": "passed", "execution": "r8-classfile-jvm",
            "apkRuntimeExecuted": False, "r8Version": "8.10.24", "fixtureSha256": "b" * 64,
            "inputModels": {"sample.Foo": self.models["sample.Foo"].sha256},
            "artifacts": {"outputJar": gate.artifact(self.jar), "mapping": gate.artifact(self.mapping),
                          "runtimeLog": gate.artifact(self.log), "inputJar": gate.artifact(self.input_jar),
                          "usage": gate.artifact(self.usage)},
            "dependencies": [],
            "bootClasspath": [],
            "roundTrips": {"sample.Foo": self.record},
            "optimisation": {"renamedModels": ["sample.Foo"], "removedProductionClasses": ["sample.Unused"]},
        }
        for name in ("rules", "r8", "inputsManifest"):
            path = self.root / name
            path.write_text(name)
            self.report["artifacts"][name] = gate.artifact(path)

    def verify(self):
        self.path.write_text(json.dumps(self.report))
        return gate.read_shrunk_round_trips(self.path, self.models, self.fixtures, "b" * 64, "8.10.24")

    def test_passing_shrunk_output_is_bound_to_loaded_class_bytes(self):
        self.assertEqual(self.verify()["roundTrips"], {"sample.Foo": self.record})

    def test_output_changed_after_execution_fails(self):
        self.jar.write_bytes(b"changed after execution")
        with self.assertRaisesRegex(gate.VerificationError, "Stale shrunk artifact.*outputJar"):
            self.verify()

    def test_pre_r8_bytes_cannot_be_reported_as_shrunk_execution(self):
        self.report["roundTrips"]["sample.Foo"]["classSha256"] = self.models["sample.Foo"].sha256
        with self.assertRaisesRegex(gate.VerificationError, "Loaded shrunk class hash"):
            self.verify()

    def test_missing_output_class_with_null_hash_is_not_execution(self):
        with zipfile.ZipFile(self.jar, "w"):
            pass
        self.report["artifacts"]["outputJar"] = gate.artifact(self.jar)
        self.record["classSha256"] = None
        self.log.write_text("NOCTURNE_SERIALIZATION " + json.dumps(self.record) + "\n"
                            "NOCTURNE_SERIALIZATION_COMPLETE both fixture methods passed\n")
        self.report["artifacts"]["runtimeLog"] = gate.artifact(self.log)
        with self.assertRaisesRegex(gate.VerificationError, "Loaded shrunk class hash"):
            self.verify()

    def test_changed_rules_or_shrinker_cannot_reuse_runtime_evidence(self):
        for name in ("rules", "r8"):
            with self.subTest(artifact=name):
                path = Path(self.report["artifacts"][name]["path"])
                original = path.read_bytes()
                path.write_bytes(b"replaced")
                with self.assertRaisesRegex(gate.VerificationError, f"Stale shrunk artifact: {name}"):
                    self.verify()
                path.write_bytes(original)

    def test_missing_library_inventory_fails_closed(self):
        for name in ("dependencies", "bootClasspath"):
            with self.subTest(inventory=name):
                value = self.report.pop(name)
                with self.assertRaisesRegex(gate.VerificationError, "Missing shrunk library inventory"):
                    self.verify()
                self.report[name] = value

    def test_unshrunk_model_on_runtime_classpath_is_rejected(self):
        self.report["dependencies"] = [gate.artifact(self.input_jar)]
        with self.assertRaisesRegex(gate.VerificationError, "Original model classes"):
            self.verify()

    def test_changed_compiler_input_fails(self):
        self.report["inputModels"]["sample.Foo"] = "c" * 64
        with self.assertRaisesRegex(gate.VerificationError, "Stale shrunk input model"):
            self.verify()

    def test_changed_fixtures_fail(self):
        self.report["fixtureSha256"] = "c" * 64
        with self.assertRaisesRegex(gate.VerificationError, "Stale shrunk fixture"):
            self.verify()

    def test_different_shrinker_version_to_apk_fails(self):
        self.report["r8Version"] = "other"
        with self.assertRaisesRegex(gate.VerificationError, "R8 version.*APK"):
            self.verify()

    def test_missing_case_after_shrinking_fails(self):
        self.report["roundTrips"]["sample.Foo"]["cases"] = 0
        with self.assertRaisesRegex(gate.VerificationError, "Shrunk fixture case count"):
            self.verify()

    def test_no_obfuscation_is_not_a_shrunk_runtime_gate(self):
        self.report["optimisation"]["renamedModels"] = []
        with self.assertRaisesRegex(gate.VerificationError, "obfuscation and shrinking"):
            self.verify()

    def test_no_shrinking_is_not_a_shrunk_runtime_gate(self):
        self.report["optimisation"]["removedProductionClasses"] = []
        with self.assertRaisesRegex(gate.VerificationError, "obfuscation and shrinking"):
            self.verify()

    def test_incomplete_harness_execution_fails(self):
        self.log.write_text("NOCTURNE_SERIALIZATION " + json.dumps(self.record) + "\n")
        self.report["artifacts"]["runtimeLog"] = gate.artifact(self.log)
        with self.assertRaisesRegex(gate.VerificationError, "completion marker"):
            self.verify()


class GateCliTest(unittest.TestCase):
    setUp = RoundTripEvidenceTest.setUp
    write_result = RoundTripEvidenceTest.write_result

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
        mapping.write_text("# pg_map_id: fixture\n# compiler_version: 8.10.24\nsample.Foo -> a:\nsample.Foo$$serializer -> b:\n")
        usage = root / "usage.txt"
        usage.write_text("")
        shrunk = ShrunkRuntimeEvidenceTest()
        shrunk.setUp()
        self.addCleanup(shrunk.doCleanups)
        fixture_hash = hashlib.sha256(fixture.read_bytes()).hexdigest()
        shrunk.report["fixtureSha256"] = fixture_hash
        shrunk.record["fixtureSha256"] = fixture_hash
        shrunk.log.write_text("NOCTURNE_SERIALIZATION " + json.dumps(shrunk.record) + "\n"
                              "NOCTURNE_SERIALIZATION_COMPLETE both fixture methods passed\n")
        shrunk.report["artifacts"]["runtimeLog"] = gate.artifact(shrunk.log)
        shrunk.path.write_text(json.dumps(shrunk.report))
        report = root / "report.json"
        result = subprocess.run([sys.executable, "-B", str(Path(gate.__file__)),
            "--apk", str(apk), "--mapping", str(mapping), "--usage", str(usage),
            "--junit", str(self.path), "--fixtures", str(fixture), "--classes", str(classes),
            "--shrunk-report", str(shrunk.path),
            "--report", str(report)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(report.exists(), "Gate did not create the evidence report")
        evidence = json.loads(report.read_text())
        self.assertEqual(evidence["status"], "passed")
        self.assertEqual(evidence["modelCount"], 1)
        self.assertFalse(evidence["apkRuntimeExecuted"])
        self.assertTrue(evidence["shrunkJvmRuntimeExecuted"])
        self.assertEqual(evidence["artifacts"]["apk"]["sha256"], hashlib.sha256(apk.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
