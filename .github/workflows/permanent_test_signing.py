#!/usr/bin/env python3
"""Optional, isolated Nocturne TEST signing; never uses production NOCTURNE_* signing variables."""

import argparse
import base64
import binascii
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

SECRET_NAMES = (
    "NOCTURNE_TEST_KEYSTORE_B64",
    "NOCTURNE_TEST_KEYSTORE_PASSWORD",
    "NOCTURNE_TEST_KEY_ALIAS",
    "NOCTURNE_TEST_KEY_PASSWORD",
)
EXPECTED_CERT_SHA256 = "bd233ddcf84f6ed97a1597d433a0137dd2c0e64b13c2c537bb8ee5508de85f15"
EXPECTED_PACKAGE = "io.github.willzy12h.nocturne"


def available(env):
    present = [bool(env.get(name)) for name in SECRET_NAMES]
    if not all(present):
        print("::notice::Permanent Nocturne TEST signing skipped: four dedicated TEST secrets are required. "
              "No production signing secret is used.", flush=True)
        return False
    return True


def certificate_digest(verify_output):
    certs = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$", verify_output, re.M)
    if len(certs) != 1:
        raise RuntimeError("Exactly one verified APK signing certificate is required")
    schemes = ("Verified using v2 scheme (APK Signature Scheme v2): true",
               "Verified using v3 scheme (APK Signature Scheme v3): true")
    if not all(x in verify_output for x in schemes):
        raise RuntimeError("Both APK v2 and v3 signatures must verify")
    return certs[0].lower()


def require_package_version(badging, version_code):
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)'", badging, re.M)
    if not match or match.group(1) != EXPECTED_PACKAGE or match.group(2) != str(version_code):
        raise RuntimeError("Signed APK package or versionCode mismatch")


def export_output(path, signed):
    if path:
        with Path(path).open("a", encoding="utf-8") as stream:
            stream.write("signed=" + ("true" if signed else "false") + "\n")


def run(args, env):
    if not available(env):
        export_output(env.get("GITHUB_OUTPUT"), False)
        return False
    alias = env["NOCTURNE_TEST_KEY_ALIAS"]
    if alias != "nocturne-test":
        raise RuntimeError("Unexpected test signing alias")
    try:
        keydata = base64.b64decode(env["NOCTURNE_TEST_KEYSTORE_B64"], validate=True)
    except (ValueError, binascii.Error):
        raise RuntimeError("TEST keystore is not valid base64") from None
    if not (2000 <= len(keydata) <= 65536 and keydata.startswith(b"\xfe\xed\xfe\xed")):
        raise RuntimeError("TEST keystore is not a valid-sized JKS container")
    tools = Path(args.sdk) / "build-tools" / args.build_tools
    apksigner, zipalign, aapt = (tools / tool for tool in ("apksigner", "zipalign", "aapt"))
    source = Path(args.unsigned)
    if not source.is_file():
        raise RuntimeError("Unsigned release APK not found")
    dest = Path(args.output_dir)
    dest.mkdir(parents=True, exist_ok=True)
    signed = dest / f"nocturne-{args.version_code}-PERMANENT-TEST.apk"
    if signed.exists():
        raise RuntimeError("Refusing to replace an existing permanent-test APK")
    with tempfile.TemporaryDirectory(prefix="nocturne-permanent-test-", dir=env.get("RUNNER_TEMP")) as temporary:
        temporary = Path(temporary)
        keyfile = temporary / "signing.jks"
        aligned = temporary / "aligned.apk"
        keyfile.write_bytes(keydata)
        keyfile.chmod(0o600)
        safe_env = {key: value for key, value in env.items()
                    if key != "NOCTURNE_TEST_KEYSTORE_B64"}
        # Passwords are loaded ONLY through the child's environment, never command arguments.
        # No command output containing secret material is captured into CI logs.
        subprocess.run([str(zipalign), "-f", "-p", "4", str(source), str(aligned)],
                       check=True, capture_output=True, env=safe_env)
        subprocess.run([str(apksigner), "sign", "--ks", str(keyfile),
                        "--ks-type", "JKS", "--ks-key-alias", alias,
                        "--ks-pass", "env:NOCTURNE_TEST_KEYSTORE_PASSWORD",
                        "--key-pass", "env:NOCTURNE_TEST_KEY_PASSWORD",
                        "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
                        "--v3-signing-enabled", "true", "--min-sdk-version", "31",
                        "--out", str(signed), str(aligned)],
                       check=True, capture_output=True, env=safe_env)
        signature = subprocess.run([str(apksigner), "verify", "--verbose", "--print-certs", str(signed)],
                                   check=True, capture_output=True, text=True, env=safe_env).stdout
        actual = certificate_digest(signature)
        if actual != EXPECTED_CERT_SHA256:
            signed.unlink(missing_ok=True)
            raise RuntimeError("Permanent TEST signer certificate does not match the pinned expected SHA-256")
        subprocess.run([str(zipalign), "-c", "-p", "4", str(signed)],
                       check=True, capture_output=True, env=safe_env)
        badging = subprocess.run([str(aapt), "dump", "badging", str(signed)],
                                 check=True, capture_output=True, text=True, env=safe_env).stdout
        require_package_version(badging, args.version_code)
    checksum = hashlib.sha256(signed.read_bytes()).hexdigest()
    (dest / "test-signature-metadata.json").write_text(json.dumps({
        "sha256": checksum, "signing_certificate_sha256": actual,
        "package": EXPECTED_PACKAGE, "versionCode": args.version_code,
        "signature_schemes": ["v2", "v3"], "identity": "permanent-TEST-only"
    }, indent=2) + "\n")
    export_output(env.get("GITHUB_OUTPUT"), True)
    print(f"Permanent TEST APK verified: {signed.name}; sha256={checksum}; cert_sha256={actual}", flush=True)
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--unsigned", required=True)
    parser.add_argument("--sdk", required=True)
    parser.add_argument("--build-tools", default="35.0.0")
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    if args.version_code < 1:
        parser.error("version code must be positive")
    try:
        run(args, os.environ)
    except Exception:
        # Do not print child stderr, which may include password-bearing diagnostics.
        print("::error::Permanent Nocturne TEST signing failed; no verified artifact produced", file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()