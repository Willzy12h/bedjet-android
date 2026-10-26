#!/usr/bin/env python3
"""Execute the shared release fixtures after a separate R8 classfile compilation.

This is deliberately separate from APK shrinking. It uses the AGP-resolved R8
compiler, release compiler output and kotlinx.serialization consumer rules, with
test entry points instead of Android application entry points. No production R8
task, source class or keep file is modified. Dependencies remain JVM libraries.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

from release_serialization import (
    TEST_CLASS, VerificationError, artifact, check_fixture_coverage, class_mapping,
    inventory_classes, jar_classes, read_round_trips, read_shrunk_round_trips,
    runtime_records, unique_object,
)


def program_classes(production_roots, test_roots):
    classes = {}
    for root in production_roots:
        for path in sorted(root.rglob("*.class")):
            name = path.relative_to(root).as_posix()
            if name in classes:
                raise VerificationError(f"Duplicate production classfile: {name}")
            classes[name] = path.read_bytes()
    harness_path = TEST_CLASS.replace(".", "/")
    for root in test_roots:
        for path in sorted(root.rglob("*.class")):
            name = path.relative_to(root).as_posix()
            if name == harness_path + ".class" or name.startswith(harness_path + "$"):
                if name in classes:
                    raise VerificationError(f"Duplicate harness classfile: {name}")
                classes[name] = path.read_bytes()
    if harness_path + ".class" not in classes:
        raise VerificationError("Compiled release serialization harness missing; run the JUnit/export tasks first")
    return classes


def input_jar(path, classes):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(classes.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)


def resolved_r8(inputs):
    candidates = []
    for candidate in inputs.get("buildClasspath", []):
        path = Path(candidate)
        with zipfile.ZipFile(path) as archive:
            if "com/android/tools/r8/R8.class" in archive.namelist():
                candidates.append(path)
    if len(candidates) != 1:
        raise VerificationError("Export must identify exactly one original AGP R8 jar; instrumented plugin copies cannot run standalone")
    return candidates[0]


def dependencies(paths, program_names):
    """Exclude compiler/test originals, so no classpath fallback can hide bad output."""
    libraries, consumers = [], {}
    for path in sorted(set(paths)):
        with zipfile.ZipFile(path) as archive:
            names = set(archive.namelist())
            if names & program_names:
                continue
            # Use the exported SDK boot classpath, not Android unit-test stubs.
            if "android/app/Activity.class" in names:
                continue
            libraries.append(path)
            # The library has duplicate ProGuard/R8 copies of the same rules.
            for name in sorted(names):
                if (name.startswith(("META-INF/proguard/", "META-INF/com.android.tools/proguard/"))
                        and "kotlinx-serialization" in name and name.endswith(".pro")):
                    data = archive.read(name)
                    digest = hashlib.sha256(data).hexdigest()
                    consumers.setdefault(digest, (path, name, data.decode("utf-8")))
    if not consumers:
        raise VerificationError("Resolved classpath has no kotlinx.serialization consumer rules")
    return libraries, consumers


def shrink_rules(records, consumers, project_rules, usage):
    # Only reflection factory entry points are rooted. Model constructors, fields,
    # equality methods and generated serializers remain eligible for optimisation.
    parts = ["# Separate JVM test roots; never applied to the APK.",
             "-allowaccessmodification", "-keepattributes InnerClasses,EnclosingMethod,Signature",
             f"-keep,allowoptimization class {TEST_CLASS} {{ public static void main(java.lang.String[]); }}",
             f"-printusage '{usage.as_posix()}'"]
    factories = sorted({record["factoryClass"] for record in records.values()})
    for name in factories:
        if not re.fullmatch(r"[A-Za-z_$][\w.$]*", name):
            raise VerificationError(f"Invalid discovered serializer factory: {name}")
        parts.append(f"-keep,allowoptimization,allowobfuscation class {name} "
                     "{ kotlinx.serialization.KSerializer serializer(); }")
    parts.extend(["# Existing application rules", project_rules.read_text()])
    for digest, (_, name, text) in sorted(consumers.items()):
        parts.extend([f"# Resolved consumer rules: {name}; sha256={digest}", text])
    return "\n".join(parts) + "\n"


def run(command, log):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, timeout=300, check=False)
    log.write_text(result.stdout)
    if result.returncode:
        print(result.stdout, file=sys.stderr)
        raise VerificationError(f"Command failed ({result.returncode}); see {log}")
    return result.stdout


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", type=Path, default=root / "app/build/reports/release-serialization/inputs.json")
    parser.add_argument("--junit", type=Path, default=root / f"app/build/test-results/testReleaseUnitTest/TEST-{TEST_CLASS}.xml")
    parser.add_argument("--fixtures", type=Path, default=Path(__file__).with_name("release-serialization-fixtures.json"))
    parser.add_argument("--project-rules", type=Path, default=root / "app/proguard-rules.pro")
    parser.add_argument("--output", type=Path, required=True, help="Evidence directory for separate R8 JVM output")
    args = parser.parse_args()
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    report_path = args.output / "shrunk-runtime.json"
    report_path.unlink(missing_ok=True)
    try:
        inputs = json.loads(args.inputs.read_text(), object_pairs_hook=unique_object)
        if inputs.get("format") != 1:
            raise VerificationError("Unsupported Gradle input export")
        roots = [Path(path) for path in inputs["productionClasses"]]
        model_roots = [path for path in roots if any(path.rglob("*.class"))]
        models = inventory_classes(model_roots)
        fixture = json.loads(args.fixtures.read_text(), object_pairs_hook=unique_object)
        if fixture.get("format") != 1:
            raise VerificationError("Unsupported fixture format")
        check_fixture_coverage(models, fixture["models"])
        fixture_hash = artifact(args.fixtures)["sha256"]
        records = read_round_trips(args.junit, models, fixture["models"], fixture_hash)
        classes = program_classes(roots, [Path(path) for path in inputs["testClasses"]])
        for name, record in records.items():
            if record.get("factoryClass", "").replace(".", "/") + ".class" not in classes:
                raise VerificationError(f"Missing compiled factory discovered by the pre-R8 fixture: {name}")
        libraries, consumers = dependencies([Path(path) for path in inputs["classpath"]], set(classes))
        r8 = resolved_r8(inputs)
        java = str(Path(inputs["javaHome"]) / "bin/java")
        version_output = run([java, "-cp", str(r8), "com.android.tools.r8.R8", "--version"], args.output / "r8-version.log")
        match = re.search(r"^R8 (\S+)", version_output, re.MULTILINE)
        if not match:
            raise VerificationError("Cannot read the resolved R8 compiler version")
        r8_version = match.group(1)
        program = args.output / "input.jar"
        shrunk = args.output / "shrunk.jar"
        mapping = args.output / "mapping.txt"
        usage = args.output / "usage.txt"
        rules = args.output / "rules.pro"
        names = args.output / "class-names.json"
        runtime_log = args.output / "runtime.log"
        input_jar(program, classes)
        rules.write_text(shrink_rules(records, consumers, args.project_rules, usage))
        for output in (shrunk, mapping, usage, runtime_log):
            output.unlink(missing_ok=True)
        command = [java, "-Xmx2g", "-cp", str(r8), "com.android.tools.r8.R8", "--release", "--classfile",
                   "--output", str(shrunk), "--pg-conf", str(rules), "--pg-map-output", str(mapping),
                   "--lib", inputs["javaHome"]]
        for library in [*map(Path, inputs["bootClasspath"]), *libraries]:
            command.extend(["--lib", str(library)])
        command.append(str(program))
        print(f"Shrinking {len(classes)} release/harness classfiles with AGP R8 {r8_version}", flush=True)
        run(command, args.output / "r8.log")
        mapped = class_mapping(mapping.read_text())
        names.write_text(json.dumps({name: mapped.get(name, name) for name in sorted(models)}, indent=2) + "\n")
        # Only the shrunk program jar and external libraries are runnable here.
        runtime = [java, "-Xverify:all", f"-Dnocturne.serializationFixtures={args.fixtures.resolve()}",
                   f"-Dnocturne.serializationClassNames={names}", "-cp",
                   os.pathsep.join(map(str, [shrunk, *libraries])), TEST_CLASS]
        print("Executing all shared fixtures from R8 classfile output", flush=True)
        executed = runtime_records(run(runtime, runtime_log))
        defined = jar_classes(shrunk)
        removed = set(usage.read_text().splitlines()) & {name[:-6].replace("/", ".") for name in classes}
        removed = sorted(name for name in removed if name != TEST_CLASS and not name.startswith(TEST_CLASS + "$")
                         and mapped.get(name, name) not in defined)
        evidence = {
            "format": 1, "status": "passed", "createdAt": datetime.now(timezone.utc).isoformat(),
            "execution": "r8-classfile-jvm", "apkRuntimeExecuted": False, "r8Version": r8_version,
            "fixtureSha256": fixture_hash, "inputModels": {name: model.sha256 for name, model in sorted(models.items())},
            "roundTrips": executed,
            "optimisation": {"renamedModels": sorted(name for name in models if mapped.get(name, name) != name),
                             "removedProductionClasses": removed},
            "artifacts": {name: artifact(path) for name, path in {
                "inputsManifest": args.inputs.resolve(), "inputJar": program, "outputJar": shrunk,
                "mapping": mapping, "usage": usage, "rules": rules, "r8": r8,
                "fixtures": args.fixtures.resolve(), "junit": args.junit.resolve(), "classNames": names,
                "projectRules": args.project_rules.resolve(), "runtimeLog": runtime_log,
                "r8Log": args.output / "r8.log", "r8VersionLog": args.output / "r8-version.log",
                "java": Path(java),
            }.items()},
            "dependencies": [artifact(path) for path in libraries],
            "bootClasspath": [artifact(Path(path)) for path in inputs["bootClasspath"]],
            "commands": {"shrink": command, "runtime": runtime},
            "limits": "Separately shrunk release classfiles run on the JVM with companion factory test roots and unshrunk dependency jars. This executes neither the APK's DEX nor its Android entry points, and does not reproduce APK reachability or DEX-specific optimisation.",
        }
        report_path.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n")
        read_shrunk_round_trips(report_path, models, fixture["models"], fixture_hash, r8_version)
        print(f"PASS: {len(models)} R8-shrunk JVM models / {sum(row['cases'] for row in executed.values())} cases; "
              f"{len(evidence['optimisation']['renamedModels'])} models renamed, {len(removed)} production classes removed. "
              f"APK DEX runtime NOT executed. Report: {report_path}")
        return 0
    except (VerificationError, OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired, zipfile.BadZipFile) as error:
        report_path.unlink(missing_ok=True)
        print(f"Shrunk serialization runtime FAILED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
