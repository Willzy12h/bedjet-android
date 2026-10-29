#!/usr/bin/env python3
"""Inspect the actual APK and fail closed against every published release."""

import argparse
import hashlib
import json
import re
import struct
import subprocess
import tempfile
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

PACKAGE = "io.github.willzy12h.nocturne"
MAX_VERSION_CODE = 2_100_000_000


def version_code(value):
    if isinstance(value, bool) or not re.fullmatch(r"[1-9][0-9]*", str(value)):
        raise ValueError("versionCode must be a positive decimal integer")
    number = int(value)
    if number > MAX_VERSION_CODE:
        raise ValueError("versionCode exceeds Android's supported maximum")
    return number


def fingerprint(value):
    result = value.replace(":", "").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", result):
        raise ValueError("Expected certificate SHA-256 must contain exactly 64 hex digits")
    return result


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def command(*args):
    return subprocess.check_output([str(arg) for arg in args], text=True)


def provenance(expected_source):
    commit = command("git", "rev-parse", "HEAD").strip()
    if commit != expected_source:
        raise ValueError("Checkout does not match the required source commit")
    # Generated build outputs are ignored; tracked edits and new source files are not.
    if command("git", "status", "--porcelain", "--untracked-files=normal").strip():
        raise ValueError("Source checkout is dirty; commit the reviewed source before packaging")
    return {"source_commit": commit, "source_tree": command("git", "rev-parse", "HEAD^{tree}").strip()}


def apk_version(apk, aapt2):
    badging = command(aapt2, "dump", "badging", apk)
    match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", badging, re.M)
    if not match or match[1] != PACKAGE:
        raise ValueError(f"APK is missing the expected package/version: {Path(apk).name}")
    return {"package_name": match[1], "version_code": version_code(match[2]), "version_name": match[3]}, badging


def require_unsigned(apk):
    """Reject signature structures even when their damaged signatures cannot verify."""
    with zipfile.ZipFile(apk) as archive:
        if any(re.fullmatch(r"META-INF/(?:[^/]+\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)", name, re.I)
               for name in archive.namelist()):
            raise ValueError("Unsigned APK contains JAR signature entries")
    with Path(apk).open("rb") as stream:
        size = stream.seek(0, 2)
        tail_start = max(0, size - 22 - 65535)  # EOCD plus its maximum ZIP comment.
        stream.seek(tail_start)
        tail = stream.read()
        eocd = tail.rfind(b"PK\x05\x06")
        if eocd < 0 or len(tail) < eocd + 22 or eocd + 22 + struct.unpack_from("<H", tail, eocd + 20)[0] != len(tail):
            raise ValueError("Unsigned APK has an invalid ZIP end record")
        central_size, central_offset = struct.unpack_from("<II", tail, eocd + 12)
        if central_offset + central_size != tail_start + eocd:
            raise ValueError("Unsigned APK has an unsupported ZIP central directory")
        if central_offset >= 16:
            stream.seek(central_offset - 16)
            if stream.read(16) == b"APK Sig Block 42":
                raise ValueError("Unsigned APK contains an APK Signing Block")


def write_metadata(apk, tools, output, expected_source, identity, expected_certificate, expected_code, expected_name):
    apk, tools, output = Path(apk), Path(tools), Path(output)
    source = provenance(expected_source)
    metadata, badging = apk_version(apk, tools / "aapt2")
    if metadata["version_code"] != version_code(expected_code) or metadata["version_name"] != expected_name:
        raise ValueError("APK version does not match the requested build version")
    subprocess.run([str(tools / "zipalign"), "-c", "-p", "4", str(apk)], check=True)
    signature = subprocess.run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    (output.parent / "signature.txt").write_text(signature.stdout)
    certs = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", signature.stdout, re.M)
    if identity == "unsigned":
        require_unsigned(apk)
        if signature.returncode == 0 or certs:
            raise ValueError("No-key release unexpectedly contains a signer")
        signer = None
    else:
        if signature.returncode != 0 or len(certs) != 1:
            raise ValueError("APK must verify with exactly one signer")
        signer = fingerprint(certs[0])
        if signer != fingerprint(expected_certificate):
            raise ValueError("APK signer does not match the expected certificate")
    (output.parent / "apk-badging.txt").write_text(badging)
    (output.parent / "manifest.txt").write_text(command(tools / "aapt2", "dump", "xmltree", apk, "--file", "AndroidManifest.xml"))
    metadata.update(source, schema_version=1, apk_file=apk.name, apk_sha256=sha256(apk),
                    apk_bytes=apk.stat().st_size, signing_identity=identity, signer_sha256=signer)
    output.write_text(json.dumps(metadata, indent=2) + "\n")
    (output.parent / "SHA256SUMS.txt").write_text(f"{metadata['apk_sha256']}  {apk.name}\n")
    return metadata


class GitHubReleases:
    def __init__(self, repository):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ValueError("Invalid GitHub repository")
        self.root = f"repos/{repository}"

    def pages(self, path):
        # --paginate follows every API Link page; asset lists are paginated separately.
        pages = json.loads(command("gh", "api", "--paginate", "--slurp", f"{self.root}/{path}?per_page=100"))
        if not isinstance(pages, list) or any(not isinstance(page, list) for page in pages):
            raise ValueError("GitHub returned an invalid release/asset listing")
        return [entry for page in pages for entry in page]

    def download(self, asset, destination):
        if asset.get("state") != "uploaded" or not isinstance(asset.get("id"), int):
            raise ValueError("Published release contains an incomplete asset")
        with destination.open("wb") as stream:
            subprocess.run(["gh", "api", "-H", "Accept: application/octet-stream",
                            f"{self.root}/releases/assets/{asset['id']}"], stdout=stream, check=True)
        if destination.stat().st_size != asset.get("size"):
            raise ValueError("Downloaded release asset has an unexpected size")
        digest = asset.get("digest")
        if digest and digest != f"sha256:{sha256(destination)}":
            raise ValueError("Downloaded release asset has an unexpected digest")


def check_published_metadata(metadata):
    if type(metadata.get("schema_version")) is not int or metadata["schema_version"] != 1 or metadata.get("package_name") != PACKAGE:
        raise ValueError("Published release metadata has an unsupported schema/package")
    if metadata.get("signing_identity") != "production":
        raise ValueError("Published release metadata is not production-signed")
    fingerprint(metadata["signer_sha256"])
    fingerprint(metadata["apk_sha256"])
    for key in ("source_commit", "source_tree"):
        if not re.fullmatch(r"[0-9a-f]{40}", str(metadata.get(key, ""))):
            raise ValueError("Published release metadata has missing/invalid source provenance")
    name = metadata.get("apk_file", "")
    if not name or Path(name).name != name or not name.lower().endswith(".apk"):
        raise ValueError("Published release metadata has a missing/invalid APK filename")
    if type(metadata.get("apk_bytes")) is not int or metadata["apk_bytes"] <= 0 or not metadata.get("version_name"):
        raise ValueError("Published release metadata is incomplete")
    version_code(metadata["version_code"])


def published_versions(client, aapt2, report):
    records = report.setdefault("published_artifacts", [])
    for release in client.pages("releases"):
        if release.get("draft") is True:
            continue
        if release.get("draft") is not False or not release.get("published_at"):
            raise ValueError("GitHub returned incomplete published-release metadata")
        found = False
        versions = {}
        manifests = []
        with tempfile.TemporaryDirectory(prefix="nocturne-published-") as directory:
            for asset in client.pages(f"releases/{release['id']}/assets"):
                name = asset["name"]
                if not name.lower().endswith(".apk") and name != "release-metadata.json":
                    continue
                found = True
                destination = Path(directory) / str(asset["id"])
                client.download(asset, destination)
                if name.lower().endswith(".apk"):
                    metadata, _ = apk_version(destination, aapt2)
                    versions[name] = (metadata["version_code"], sha256(destination))
                else:
                    metadata = json.loads(destination.read_text())
                    check_published_metadata(metadata)
                    manifests.append(metadata)
                code = version_code(metadata["version_code"])
                records.append({"release_id": release["id"], "tag": release["tag_name"],
                                "asset": name, "asset_id": asset["id"], "version_code": code})
            if not found:
                raise ValueError(f"Published release {release['tag_name']} has no APK or release-metadata.json version evidence")
            for metadata in manifests:
                actual = versions.get(metadata["apk_file"])
                if actual and actual != (metadata["version_code"], metadata["apk_sha256"]):
                    raise ValueError("Published APK and release metadata disagree")
    return max((record["version_code"] for record in records), default=0)


def junit_counts(directory):
    files = sorted(Path(directory).glob("TEST-*.xml"))
    if not files:
        return {"status": "not-produced", "suites": 0}
    totals = Counter(tests=0, failures=0, errors=0, skipped=0)
    for path in files:
        suite = ET.parse(path).getroot()
        counts = {key: int(suite.get(key, "0")) for key in totals}
        if suite.tag != "testsuite" or any(value < 0 for value in counts.values()) or counts["tests"] < sum(counts[key] for key in ("failures", "errors", "skipped")):
            raise ValueError(f"Invalid JUnit result counts: {path}")
        totals.update(counts)
    return {"status": "produced", "suites": len(files), **totals,
            "passed": totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    metadata = sub.add_parser("metadata")
    metadata.add_argument("--apk", required=True)
    metadata.add_argument("--tools", required=True)
    metadata.add_argument("--output", required=True)
    metadata.add_argument("--expected-source", required=True)
    metadata.add_argument("--identity", choices=("unsigned", "test-only", "production"), required=True)
    metadata.add_argument("--expected-certificate", default="")
    metadata.add_argument("--version-code", required=True)
    metadata.add_argument("--version-name", required=True)
    history = sub.add_parser("history")
    history.add_argument("--repository", required=True)
    history.add_argument("--aapt2", required=True)
    history.add_argument("--version-code", required=True)
    history.add_argument("--expected-source", required=True)
    history.add_argument("--output", required=True)
    summary = sub.add_parser("gate-summary")
    summary.add_argument("--expected-source", required=True)
    summary.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.action == "metadata":
        result = write_metadata(args.apk, args.tools, args.output, args.expected_source, args.identity,
                                args.expected_certificate, args.version_code, args.version_name)
        print(json.dumps(result, indent=2))
    elif args.action == "history":
        report = {"status": "failed", "candidate_version_code": args.version_code, "source_commit": args.expected_source}
        try:
            provenance(args.expected_source)
            code = version_code(args.version_code)
            previous = published_versions(GitHubReleases(args.repository), args.aapt2, report)
            report["highest_published_version_code"] = previous
            if code <= previous:
                raise ValueError(f"Candidate versionCode {code} must exceed published versionCode {previous}")
            report["status"] = "passed"
        except Exception as error:
            report["error"] = str(error)
            raise
        finally:
            output = Path(args.output)
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(report, indent=2) + "\n")
            print(json.dumps(report, indent=2))
    else:
        report = provenance(args.expected_source)
        report["core"] = junit_counts("core/build/test-results/test")
        report["app"] = junit_counts("app/build/test-results/testDebugUnitTest")
        lint = Path("app/build/reports/lint-results-debug.xml")
        report["lint"] = ({"status": "produced", "by_severity": dict(sorted(Counter(
            issue.get("severity", "Unknown") for issue in ET.parse(lint).getroot().findall("issue")).items()))}
            if lint.is_file() else {"status": "not-produced"})
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n")
        print("Required gate counts (before screenshot capture):")
        for name in ("core", "app", "lint"):
            print(f"{name}: {json.dumps(report[name], sort_keys=True)}")


if __name__ == "__main__":
    main()
