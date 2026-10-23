#!/usr/bin/env python3
"""Verify release JVM round trips and the actual APK's R8 serialization structure.

The JVM test does not execute DEX. This gate reports that behavioural evidence
separately from its inspection of the actual, unmodified APK. It never adds roots
or keep rules to the production R8 invocation.
"""

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile


TEST_CLASS = "io.github.willzy12h.nocturne.releasegate.ReleaseSerializationTest"
RECORD_PREFIX = "NOCTURNE_SERIALIZATION "


class VerificationError(Exception):
    pass


@dataclass(frozen=True)
class Model:
    name: str
    kind: str
    sha256: str
    path: str


class Reader:
    def __init__(self, data):
        self.data = data
        self.offset = 0

    def take(self, size):
        if size < 0 or self.offset + size > len(self.data):
            raise VerificationError("Truncated binary input")
        result = self.data[self.offset:self.offset + size]
        self.offset += size
        return result

    def u1(self):
        return self.take(1)[0]

    def u2(self):
        return struct.unpack(">H", self.take(2))[0]

    def u4(self):
        return struct.unpack(">I", self.take(4))[0]


def classfile_model(data, path):
    """Inventory compiler output, including private/nested and sealed models.

    Annotations may have either runtime or binary retention. Reading the JVM
    attributes avoids source-regex omissions and never loads production code.
    """
    reader = Reader(data)
    if reader.u4() != 0xCAFEBABE:
        raise VerificationError(f"Not a JVM class: {path}")
    reader.take(4)  # minor and major versions
    count = reader.u2()
    pool = [None] * count
    index = 1
    while index < count:
        tag = reader.u1()
        if tag == 1:
            pool[index] = reader.take(reader.u2()).decode("utf-8", "replace")
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = reader.u2()
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            reader.take(4)
        elif tag in (5, 6):
            reader.take(8)
            index += 1
        elif tag == 15:
            reader.take(3)
        else:
            raise VerificationError(f"Unknown constant-pool tag {tag}: {path}")
        index += 1
    flags = reader.u2()
    name = pool[pool[reader.u2()]].replace("/", ".")
    reader.u2()  # superclass
    reader.take(2 * reader.u2())  # interfaces

    def attributes():
        for _ in range(reader.u2()):
            attribute_name = pool[reader.u2()]
            yield attribute_name, reader.take(reader.u4())

    for _ in range(2):  # fields and methods
        for _ in range(reader.u2()):
            reader.take(6)
            list(attributes())

    def skip_value(annotation):
        tag = chr(annotation.u1())
        if tag in "BCDFIJSZsc":
            annotation.u2()
        elif tag == "e":
            annotation.take(4)
        elif tag == "@":
            read_annotation(annotation)
        elif tag == "[":
            for _ in range(annotation.u2()):
                skip_value(annotation)
        else:
            raise VerificationError(f"Unknown annotation value {tag}: {path}")

    def read_annotation(annotation):
        annotation_name = pool[annotation.u2()]
        for _ in range(annotation.u2()):
            annotation.u2()
            skip_value(annotation)
        return annotation_name

    annotated = False
    for attribute_name, payload in attributes():
        if attribute_name in ("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"):
            annotation = Reader(payload)
            for _ in range(annotation.u2()):
                annotated |= read_annotation(annotation) == "Lkotlinx/serialization/Serializable;"
    if not annotated:
        return None
    kind = "enum" if flags & 0x4000 else "abstract" if flags & 0x600 else "class"
    return Model(name, kind, hashlib.sha256(data).hexdigest(), str(path))


def inventory_classes(roots):
    models = {}
    for root in roots:
        classes = sorted(Path(root).rglob("*.class"))
        if not classes:
            raise VerificationError(f"No compiled production classes under {root}; compile release first")
        for path in classes:
            model = classfile_model(path.read_bytes(), path)
            if model is not None:
                if model.name in models:
                    raise VerificationError(f"Duplicate compiled model: {model.name}")
                models[model.name] = model
    if not models:
        raise VerificationError("No @Serializable production models found")
    return models


def check_fixture_coverage(models, fixtures):
    missing = set(models) - set(fixtures)
    unknown = set(fixtures) - set(models)
    if missing:
        raise VerificationError("Missing round-trip fixtures: " + ", ".join(sorted(missing)))
    if unknown:
        raise VerificationError("Unknown fixture models: " + ", ".join(sorted(unknown)))
    for name, cases in fixtures.items():
        if not isinstance(cases, list) or not cases:
            raise VerificationError(f"No fixture cases: {name}")


def parse_dex(data):
    """Read defined class_defs, never confuse string/type references with classes."""
    def word(offset):
        if offset < 0 or offset + 4 > len(data):
            raise VerificationError("Truncated DEX table")
        return struct.unpack_from("<I", data, offset)[0]

    if len(data) < 112 or not re.fullmatch(rb"dex\n0(?:3[5-9]|40)\x00", data[:8]):
        raise VerificationError("Unsupported or missing standard DEX header")
    if word(32) != len(data) or word(36) != 112 or word(40) != 0x12345678:
        raise VerificationError("Invalid DEX size/header/endianness")
    string_count, string_offset = word(56), word(60)
    type_count, type_offset = word(64), word(68)
    class_count, class_offset = word(96), word(100)
    for count, offset, size in ((string_count, string_offset, 4),
                                (type_count, type_offset, 4), (class_count, class_offset, 32)):
        if offset + count * size > len(data):
            raise VerificationError("Truncated DEX table")
    strings = []
    for index in range(string_count):
        offset = word(string_offset + index * 4)
        for _ in range(5):  # ULEB128 UTF-16 length; descriptor and marker content is ASCII.
            if offset >= len(data):
                raise VerificationError("Truncated DEX string")
            byte = data[offset]
            offset += 1
            if byte < 0x80:
                break
        else:
            raise VerificationError("Invalid DEX string length")
        end = data.find(b"\x00", offset)
        if end == -1:
            raise VerificationError("Unterminated DEX string")
        strings.append(data[offset:end].decode("utf-8", "replace"))
    defined = set()
    for index in range(class_count):
        type_index = word(class_offset + index * 32)
        if type_index >= type_count:
            raise VerificationError("Invalid DEX defined type index")
        string_index = word(type_offset + type_index * 4)
        if string_index >= len(strings):
            raise VerificationError("Invalid DEX class descriptor index")
        descriptor = strings[string_index]
        if not descriptor.startswith("L") or not descriptor.endswith(";"):
            raise VerificationError(f"Invalid DEX class descriptor: {descriptor}")
        defined.add(descriptor[1:-1].replace("/", "."))
    markers = [json.loads(value[4:]) for value in strings if value.startswith("~~R8{")]
    return defined, markers


def read_apk(path, mapping_text):
    ids = re.findall(r"^# pg_map_id: (\S+)\s*$", mapping_text, re.MULTILINE)
    if len(ids) != 1:
        raise VerificationError("Mapping must contain exactly one R8 pg_map_id")
    defined, reports = set(), []
    with zipfile.ZipFile(path) as archive:
        dex_files = sorted(name for name in archive.namelist() if re.fullmatch(r"classes(?:[1-9]\d*)?\.dex", name))
        if not dex_files or len(dex_files) != len(set(dex_files)):
            raise VerificationError("APK has no DEX, or duplicate DEX entries")
        for name in dex_files:
            data = archive.read(name)
            classes, markers = parse_dex(data)
            map_ids = {marker.get("pg-map-id") for marker in markers}
            if map_ids != {ids[0]}:
                raise VerificationError(f"APK {name} R8 mapping ID {sorted(str(x) for x in map_ids)} does not match {ids[0]}")
            if defined & classes:
                raise VerificationError(f"Duplicate class definitions across DEX files: {name}")
            defined.update(classes)
            reports.append({"file": name, "sha256": hashlib.sha256(data).hexdigest(),
                            "definedClasses": len(classes), "r8Markers": markers})
    return defined, reports


def verify_structure(models, records, mapping_text, usage_text, defined):
    classes, moved_members = {}, {}
    current = None
    for line in mapping_text.splitlines():
        match = re.fullmatch(r"([^ #].*) -> (\S+):", line)
        if match:
            original, current = match.groups()
            classes[original] = current
        elif current and line.startswith(" ") and " -> " in line and "(" in line:
            declaration = line.strip().split(" -> ", 1)[0].split("(", 1)[0]
            method = declaration.rsplit(" ", 1)[-1]
            # R8 qualifies an original member when it is moved or inlined into another owner.
            if "." in method:
                owner = method.rsplit(".", 1)[0]
                moved_members.setdefault(owner, []).append((current, method))
    removed = {line for line in usage_text.splitlines() if re.fullmatch(r"[\w.$]+", line)}

    def presence(original):
        target = classes.get(original)
        if target in defined:
            return {"originalClass": original, "definedClass": target,
                    "evidence": "mapped-class", "listedAsRemoved": original in removed}
        if original in defined:
            return {"originalClass": original, "definedClass": original,
                    "evidence": "unobfuscated-definition", "listedAsRemoved": original in removed}
        for owner, method in moved_members.get(original, []):
            if owner in defined:
                return {"originalClass": original, "definedClass": owner,
                        "evidence": "mapped-member", "member": method,
                        "listedAsRemoved": original in removed}
        note = " (listed as removed by R8)" if original in removed else ""
        raise VerificationError(f"No packaged implementation for {original}{note}; inspect R8 optimisation before considering any keep rule")

    return {name: {"model": presence(name), "serializer": presence(records[name]["serializer"])}
            for name in sorted(models)}


def read_round_trips(path, models, fixtures, fixture_hash):
    suite = ET.parse(path).getroot()
    test_names = {test.get("name") for test in suite.findall("testcase")}
    if (suite.tag != "testsuite" or suite.get("name") != TEST_CLASS
            or any(suite.get(attribute, "0") != "0" for attribute in ("failures", "errors", "skipped"))
            or any(suite.findall(f".//{tag}") for tag in ("failure", "error", "skipped"))
            or not {"allStoredModelsRoundTrip", "snapshotStorePersistsItsPrivateWrapper"} <= test_names):
        raise VerificationError(f"JVM release test did not pass completely: {path}")
    records = {}
    for output in suite.findall("system-out"):
        for line in (output.text or "").splitlines():
            if not line.startswith(RECORD_PREFIX):
                continue
            record = json.loads(line[len(RECORD_PREFIX):])
            name = record["model"]
            if name in records:
                raise VerificationError(f"Duplicate execution record: {name}")
            records[name] = record
    if set(records) != set(models):
        raise VerificationError("Executed model inventory differs from compiled release inventory; "
                                f"missing={sorted(set(models) - set(records))}, extra={sorted(set(records) - set(models))}")
    for name, record in records.items():
        if record.get("classSha256") != models[name].sha256:
            raise VerificationError(f"Stale JVM class evidence: {name}; rerun the release test")
        if record.get("fixtureSha256") != fixture_hash:
            raise VerificationError(f"Stale fixture evidence: {name}; rerun the release test")
        if type(record.get("cases")) is not int or record["cases"] != len(fixtures[name]):
            raise VerificationError(f"Fixture case count does not match: {name}")
        if not isinstance(record.get("serializer"), str) or not record["serializer"]:
            raise VerificationError(f"Missing executed serializer identity: {name}")
    return records


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise VerificationError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def artifact(path):
    data = path.read_bytes()
    return {"path": str(path), "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path, help="The exact release APK being reviewed/published")
    parser.add_argument("--mapping", type=Path, default=root / "app/build/outputs/mapping/release/mapping.txt")
    parser.add_argument("--usage", type=Path, default=root / "app/build/outputs/mapping/release/usage.txt")
    parser.add_argument("--junit", type=Path, default=root / f"app/build/test-results/testReleaseUnitTest/TEST-{TEST_CLASS}.xml")
    parser.add_argument("--fixtures", type=Path, default=Path(__file__).with_name("release-serialization-fixtures.json"))
    parser.add_argument("--classes", action="append", type=Path,
                        help="Compiled production class directory; repeat for app/core. Defaults to release app and core output.")
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    inputs = {name: getattr(args, name).resolve() for name in ("apk", "mapping", "usage", "junit", "fixtures")}
    if args.report.resolve() in inputs.values():
        parser.error("Report path must not overwrite an input")
    # A failed re-check must not leave an earlier PASS report behind.
    args.report.unlink(missing_ok=True)
    try:
        models = inventory_classes(args.classes or [root / "app/build/tmp/kotlin-classes/release",
                                                    root / "core/build/classes/kotlin/main"])
        fixture = json.loads(args.fixtures.read_text(), object_pairs_hook=unique_object)
        if fixture.get("format") != 1 or not isinstance(fixture.get("models"), dict):
            raise VerificationError("Unsupported fixture format")
        cases = fixture["models"]
        check_fixture_coverage(models, cases)
        artifacts = {name: artifact(path) for name, path in inputs.items()}
        records = read_round_trips(args.junit, models, cases, artifacts["fixtures"]["sha256"])
        mapping = args.mapping.read_text()
        defined, dex = read_apk(args.apk, mapping)
        structure = verify_structure(models, records, mapping, args.usage.read_text(), defined)
        report = {
            "status": "passed",
            "createdAt": datetime.now(timezone.utc).isoformat(),
            "modelCount": len(models),
            "caseCount": sum(record["cases"] for record in records.values()),
            "apkRuntimeExecuted": False,
            "evidence": {
                "releaseJvm": "Every compiled production @Serializable model has passing release-variant JVM JSON fixtures; all serialized fields and enum values are covered. Executed model classfile and fixture hashes are checked.",
                "apkStructure": "Every model and the serializer used by its JVM fixture has a defined class or a mapped merged/inlined implementation owner in the actual APK. Every classes*.dex is inspected and its R8 mapping ID matches the supplied mapping.",
                "limits": "JVM tests execute pre-R8 classfiles, not APK DEX. Mapped-member evidence proves an implementation location survives, not runtime JSON behaviour on Android. This does not establish device restoration, widgets, or arbitrary historical schemas.",
            },
            "artifacts": artifacts,
            "dex": dex,
            "models": [{"class": name, "kind": models[name].kind, "classSha256": models[name].sha256,
                        "roundTrips": records[name], "apk": structure[name]} for name in sorted(models)],
        }
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
        print(f"PASS: {len(models)} release JVM models / {report['caseCount']} round trips; "
              f"{len(dex)} actual APK DEX files structurally verified. APK runtime NOT executed. Report: {args.report}")
        return 0
    except (VerificationError, OSError, ValueError, KeyError, IndexError, TypeError,
            struct.error, zipfile.BadZipFile, ET.ParseError) as error:
        print(f"Release serialization gate FAILED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
