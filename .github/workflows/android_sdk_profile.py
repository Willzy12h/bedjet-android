#!/usr/bin/env python3
"""Record runner SDKs, then verify the isolated, reviewed Android 36 toolchain.

The inventory preserves any newer runner platforms; it does not claim that a
target-36 app has been validated against their target-specific behaviour.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET


CLI_VERSION = "1.1.16717390"
CLI_SHA256 = "b34ed0baeeffa2aaee2720d82491b975835696e2e56840449078bec885436564"
CLI_URL = "https://dl.google.com/android/cli/latest/linux_x86_64/android-cli"


def install(environment, output):
    """Verify the actual CLI before execution; the SDK's android is a launcher."""
    temporary = Path(environment["RUNNER_TEMP"]).resolve()
    root = temporary / "nocturne-android-sdk"
    binary = temporary / "nocturne-android-cli/android"
    report = {"phase": "install", "recorded_utc": datetime.now(timezone.utc).isoformat(),
              "url": CLI_URL, "expected_version": CLI_VERSION, "expected_sha256": CLI_SHA256,
              "binary": str(binary), "sdk_root": str(root), "commands": [], "errors": []}
    try:
        if any(Path(environment.get(key, "")).resolve() != root
               for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT")):
            raise ValueError("Both SDK environment variables must identify the isolated SDK")
        binary.parent.mkdir(parents=True, exist_ok=True)
        download = binary.with_suffix(".download")
        with urllib.request.urlopen(CLI_URL, timeout=60) as response, download.open("wb") as target:
            shutil.copyfileobj(response, target)
        report["sha256"] = hashlib.sha256(download.read_bytes()).hexdigest()
        if report["sha256"] != CLI_SHA256:
            raise ValueError("Android CLI download does not match the reviewed SHA256; no execution")
        download.chmod(0o700)
        download.replace(binary)
        execution_environment = {**environment, "ANDROID_USER_HOME": str(temporary / "nocturne-android-user")}
        for name, arguments in (("version", ["--version"]),
                                ("packages", ["--sdk=" + str(root), "sdk", "install", "platform-tools",
                                              "platforms/android-36", "build-tools/35.0.0", "ndk/27.0.12077973"])):
            command = [str(binary), "--no-metrics", *arguments]
            log = output.parent / ("android-cli-" + name + ".log")
            try:
                result = subprocess.run(command, input="y\n" * 20, text=True, env=execution_environment,
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600)
            except subprocess.TimeoutExpired as error:
                partial = error.stdout or ""
                if isinstance(partial, bytes):
                    partial = partial.decode("utf-8", errors="replace")
                log.write_text(partial)
                print(partial, end="", flush=True)
                report["commands"].append({"command": command, "exit": None, "timed_out": True,
                                           "timeout_seconds": error.timeout, "log": str(log)})
                raise
            log.write_text(result.stdout)
            print(result.stdout, end="", flush=True)
            report["commands"].append({"command": command, "exit": result.returncode, "log": str(log)})
            if result.returncode:
                raise ValueError(f"Android CLI {name} exited {result.returncode}")
            if name == "version" and CLI_VERSION not in result.stdout.splitlines():
                raise ValueError("Android CLI reported an unexpected version")
        if environment.get("GITHUB_PATH"):
            with Path(environment["GITHUB_PATH"]).open("a") as path_file:
                path_file.write(str(binary.parent) + "\n")
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        report["errors"].append(str(error))
    report["status"] = "failed" if report["errors"] else "installed"
    return report


def properties(path):
    values = {}
    if path.is_file():
        for line in path.read_text().splitlines():
            if line.strip() and not line.lstrip().startswith(("#", "!")) and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    return values


def package(directory):
    source = directory / "source.properties"
    manifest = directory / "package.xml"
    result = {"directory": str(directory.resolve()), "properties": {}, "read_errors": []}
    try:
        result["properties"] = properties(source)
        for name, path in (("source_properties", source), ("package_xml", manifest)):
            result[name + "_sha256"] = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
        if manifest.is_file():
            document = ET.parse(manifest).getroot()
            result["package_paths"] = [node.get("path") for node in document.iter()
                                       if node.tag.rsplit("}", 1)[-1] == "localPackage"]
            result["api_levels"] = [node.text for node in document.iter()
                                    if node.tag.rsplit("}", 1)[-1] == "api-level"]
    except (OSError, UnicodeError, ET.ParseError) as error:
        result["read_errors"].append(str(error))
    return result


def inventory(root):
    return {"root": str(root.resolve()), "exists": root.is_dir(), **{
        group: {child.name: package(child) for child in sorted((root / group).iterdir()) if child.is_dir()}
        if (root / group).is_dir() else {}
        for group in ("platforms", "build-tools", "ndk", "cmdline-tools")
    }}


def inspect(phase, environment):
    expected = (Path(environment["RUNNER_TEMP"]) / "nocturne-android-sdk").resolve()
    roots = {key: environment.get(key) for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT")}
    # This is setup-android's actual fallback, not an assumed installed SDK.
    selected_root = roots["ANDROID_SDK_ROOT"] or str(Path.home() / ".android/sdk")
    locations = sorted({str(Path(value).resolve()) for value in (*roots.values(), selected_root) if value})
    executable = shutil.which("sdkmanager", path=environment.get("PATH", ""))
    resolved_executable = str(Path(executable).resolve()) if executable else None
    android = shutil.which("android", path=environment.get("PATH", ""))
    resolved_android = str(Path(android).resolve()) if android else None
    report = {"phase": phase, "recorded_utc": datetime.now(timezone.utc).isoformat(),
              "environment_roots": roots, "setup_action_selected_root": selected_root,
              "expected_isolated_root": str(expected), "isolated_root_exists": expected.exists(),
              "sdkmanager": executable, "resolved_sdkmanager": resolved_executable,
              "android_cli": android, "resolved_android_cli": resolved_android,
              "inventories": [inventory(Path(location)) for location in locations],
              "profile": {"compile_sdk": 36, "target_sdk": 36, "min_sdk": 31,
                          "build_tools": "35.0.0", "ndk": "27.0.12077973",
                          "cmdline_tools_build": "15859902", "cmdline_tools_revision": "22.0",
                          "android_cli_version": CLI_VERSION, "android_cli_sha256": CLI_SHA256,
                          "newer_target_behaviour_validation": "NOT RUN"}, "errors": []}
    errors = report["errors"]
    if phase == "before":
        if expected.exists():
            errors.append("The isolated SDK root already exists before setup")
    else:
        for key, value in roots.items():
            if not value or Path(value).resolve() != expected:
                errors.append(f"{key} does not identify the isolated SDK root")
        actual = inventory(expected)
        report["isolated_inventory"] = actual
        if set(actual["platforms"]) != {"android-36"}:
            errors.append("The isolated SDK must contain exactly the android-36 platform")
        checks = (("platforms", "android-36", "AndroidVersion.ApiLevel", {"36"}),
                  ("build-tools", "35.0.0", "Pkg.Revision", {"35.0.0"}),
                  ("ndk", "27.0.12077973", "Pkg.Revision", {"27.0.12077973"}),
                  ("cmdline-tools", "22.0", "Pkg.Revision", {"22.0", "22.0.0"}))
        for group, name, key, allowed in checks:
            entry = actual[group].get(name, {})
            if entry.get("read_errors"):
                errors.append(f"Unable to read {group}/{name} metadata")
            if entry.get("properties", {}).get(key) not in allowed:
                errors.append(f"Unexpected or missing {group}/{name} {key}")
            if not entry or not Path(entry["directory"]).is_relative_to(expected):
                errors.append(f"{group}/{name} is missing or resolves outside the isolated SDK")
        platform = actual["platforms"].get("android-36", {})
        if platform.get("package_paths") != ["platforms;android-36"] or platform.get("api_levels") != ["36"]:
            errors.append("The platform package manifest does not identify API 36")
        binary = expected.parent / "nocturne-android-cli/android"
        if resolved_android != str(binary) or not os.access(binary, os.X_OK):
            errors.append("PATH does not select the isolated, pinned Android CLI")
        report["android_cli_sha256"] = hashlib.sha256(binary.read_bytes()).hexdigest() if binary.is_file() else None
        if report["android_cli_sha256"] != CLI_SHA256:
            errors.append("The Android CLI executable does not match its reviewed SHA256")
    report["status"] = "failed" if errors else "recorded" if phase == "before" else "verified"
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("before", "install", "after"))
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    report = install(os.environ, args.output) if args.phase == "install" else inspect(args.phase, os.environ)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"phase": args.phase, "status": report["status"], "errors": report["errors"],
                      "receipt": str(args.output)}))
    return 1 if report["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
