#!/usr/bin/env python3
"""Build and verify unsigned and explicitly test-signed shrunk releases."""

import argparse
import hashlib
import itertools
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

sys.dont_write_bytecode = True
from release_artifacts import fingerprint, provenance, version_code, write_metadata

SIGNING_KEYS = ("NOCTURNE_KEYSTORE", "NOCTURNE_KEYSTORE_PASSWORD", "NOCTURNE_KEY_ALIAS", "NOCTURNE_KEY_PASSWORD")
GRADLE = ["./gradlew", "--no-daemon", "--max-workers=2", "-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8"]


def run_gradle(tasks, environment, log, expect_failure=False):
    print(f"[release signing] {log.stem}", flush=True)
    with log.open("w") as stream:
        result = subprocess.run(GRADLE + tasks, env=environment, stdout=stream, stderr=subprocess.STDOUT)
    if expect_failure:
        if result.returncode == 0 or "Nocturne release signing:" not in log.read_text():
            raise RuntimeError(f"Signing probe did not fail for the expected configuration reason: {log}")
    elif result.returncode != 0:
        raise RuntimeError(f"Gradle failed; see {log}")


def signing_cases(valid, missing_path, directory_path):
    for count in range(1, len(SIGNING_KEYS)):
        for present in itertools.combinations(SIGNING_KEYS, count):
            label = "partial-" + "-".join(str(SIGNING_KEYS.index(key) + 1) for key in present)
            yield label, {key: valid[key] for key in present}
    for label, key, value in (
        ("missing-key-file", "NOCTURNE_KEYSTORE", str(missing_path)),
        ("key-is-directory", "NOCTURNE_KEYSTORE", str(directory_path)),
        ("wrong-store-password", "NOCTURNE_KEYSTORE_PASSWORD", "wrong-store-password"),
        ("missing-alias", "NOCTURNE_KEY_ALIAS", "missing-alias"),
        ("wrong-key-password", "NOCTURNE_KEY_PASSWORD", "wrong-key-password"),
    ):
        yield label, dict(valid, **{key: value})


def apk_payload(apk):
    """Ignore APK/JAR signatures but compare every packaged program/resource byte."""
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("APK contains duplicate ZIP entries")
        return {name: hashlib.sha256(archive.read(name)).hexdigest() for name in names
                if not re.fullmatch(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", name, re.I)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME"), required=not os.environ.get("ANDROID_HOME"))
    parser.add_argument("--build-tools", default="35.0.0")
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True)
    parser.add_argument("--expected-source", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9][A-Za-z0-9.+_-]*", args.version_name):
        parser.error("version name must start with a digit and contain only letters, digits, dot, plus, underscore or dash")
    code = version_code(args.version_code)
    source = provenance(args.expected_source)
    output = Path(args.output).resolve()
    unsigned, candidate, evidence = (output / part for part in ("unsigned", "candidate", "evidence"))
    for directory in (unsigned, candidate, evidence):
        directory.mkdir(parents=True, exist_ok=True)
    report = dict(source, status="failed", probes=[], private_key_uploaded=False)
    tools = Path(args.sdk).resolve() / "build-tools" / args.build_tools
    base = {key: value for key, value in os.environ.items() if key not in SIGNING_KEYS}
    properties = [f"-PversionName={args.version_name}", f"-PversionCode={code}"]
    try:
        # A stale unsigned file must never hide an accidental fallback to a signed output.
        unsigned_output = Path("app/build/outputs/apk/release/app-release-unsigned.apk")
        signed_output = Path("app/build/outputs/apk/release/app-release.apk")
        unsigned_output.unlink(missing_ok=True)
        signed_output.unlink(missing_ok=True)
        run_gradle([":app:assembleRelease"] + properties, base, evidence / "unsigned-build.log")
        unsigned_apk = unsigned / "app-release-unsigned.apk"
        shutil.copy2(unsigned_output, unsigned_apk)
        write_metadata(unsigned_apk, tools, unsigned / "release-metadata.json", args.expected_source,
                       "unsigned", "", code, args.version_name)
        shutil.copytree("app/build/outputs/mapping/release", unsigned / "r8", dirs_exist_ok=True)
        report["unsigned_apk"] = json.loads((unsigned / "release-metadata.json").read_text())

        # TemporaryDirectory is outside the checkout and is removed on success or error.
        with tempfile.TemporaryDirectory(prefix="nocturne-test-key-", dir=os.environ.get("RUNNER_TEMP")) as temporary:
            keydir = Path(temporary).resolve()
            if keydir.is_relative_to(Path.cwd().resolve()):
                raise ValueError("Temporary signing key directory must be outside the Git checkout")
            keyfile, certificate = keydir / "candidate.p12", keydir / "certificate.der"
            password = secrets.token_hex(24)
            if os.environ.get("GITHUB_ACTIONS") == "true":
                print(f"::add-mask::{password}", flush=True)
            key_environment = dict(base, NOCTURNE_TEST_KEY_PASSWORD=password)
            subprocess.run(["keytool", "-genkeypair", "-noprompt", "-keystore", str(keyfile), "-storetype", "PKCS12",
                            "-alias", "candidate", "-storepass:env", "NOCTURNE_TEST_KEY_PASSWORD",
                            "-keypass:env", "NOCTURNE_TEST_KEY_PASSWORD", "-keyalg", "RSA", "-keysize", "3072",
                            "-validity", "30", "-dname", "CN=Nocturne throwaway test candidate,O=Test only,C=GB"],
                           env=key_environment, check=True)
            subprocess.run(["keytool", "-exportcert", "-keystore", str(keyfile), "-alias", "candidate",
                            "-storepass:env", "NOCTURNE_TEST_KEY_PASSWORD", "-file", str(certificate)],
                           env=key_environment, check=True)
            expected_certificate = hashlib.sha256(certificate.read_bytes()).hexdigest()
            valid = dict(zip(SIGNING_KEYS, (str(keyfile), password, "candidate", password)))
            for label, values in signing_cases(valid, keydir / "missing.p12", keydir):
                run_gradle(["help"] + properties, dict(base, **values), evidence / f"{label}.log", expect_failure=True)
                report["probes"].append({"case": label, "result": "rejected with signing configuration error"})

            signed_output.unlink(missing_ok=True)
            run_gradle([":app:assembleRelease"] + properties, dict(base, **valid), evidence / "test-signed-build.log")
            signed_apk = candidate / f"nocturne-{args.version_name}-TEST-ONLY.apk"
            shutil.copy2(signed_output, signed_apk)
            report["test_signed_apk"] = write_metadata(signed_apk, tools, candidate / "release-metadata.json",
                                                      args.expected_source, "test-only", expected_certificate, code, args.version_name)
            if apk_payload(unsigned_apk) != apk_payload(signed_apk):
                raise ValueError("Unsigned and test-signed releases contain different program/resource bytes")
            report["unsigned_and_test_signed_payloads_identical"] = True

        # A successful signed build must use the generated certificate, never Android's debug identity.
        debug_signature = subprocess.check_output([str(tools / "apksigner"), "verify", "--print-certs",
                                                  "app/build/outputs/apk/debug/app-debug.apk"], text=True)
        debug_certs = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", debug_signature, re.M)
        if len(debug_certs) != 1 or fingerprint(debug_certs[0]) == expected_certificate:
            raise ValueError("Test release signer must differ from the verified debug APK signer")
        report["debug_signer_sha256"] = fingerprint(debug_certs[0])
        report["test_signer_sha256"] = expected_certificate
        shutil.copytree("app/build/outputs/mapping/release", candidate / "r8", dirs_exist_ok=True)
        (candidate / "READ-ME.txt").write_text(
            "TEST ONLY. This APK uses a throwaway certificate, never the production signing identity.\n"
            "The private key has been deleted. Each CI run uses a different certificate.\n"
            "It cannot update installations signed with another key. Export data before changing installations.\n"
            "Samsung and BedJet 3 physical acceptance: NOT RUN.\n")
        report["status"] = "passed"
    except Exception as error:
        report["error"] = str(error)
        raise
    finally:
        (evidence / "signing-checks.json").write_text(json.dumps(report, indent=2) + "\n")
        print(f"Signing evidence: {evidence / 'signing-checks.json'}", flush=True)


if __name__ == "__main__":
    main()
