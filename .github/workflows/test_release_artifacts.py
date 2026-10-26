#!/usr/bin/env python3
"""Small host tests for the release gate's security decisions; no Android build."""

import json
import io
import struct
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

import release_artifacts as release
from release_signing import apk_payload


class FakeGitHub:
    def __init__(self, releases, assets, contents):
        self.releases, self.assets, self.contents = releases, assets, contents
        self.visited = []

    def pages(self, path):
        self.visited.append(path)
        return self.releases if path == "releases" else self.assets[path]

    def download(self, asset, path):
        path.write_text(self.contents[asset["id"]])


def published(identifier, tag=None, **extra):
    return dict(id=identifier, tag_name=tag or f"v{identifier}", draft=False, published_at="2026-01-01T00:00:00Z", **extra)


def apk(identifier, name):
    return {"id": identifier, "name": name}


class ReleaseGateTest(unittest.TestCase):
    def inspect_apk(self, path, _aapt2):
        return {"version_code": release.version_code(path.read_text())}, ""

    def check_history(self, client):
        report = {}
        with patch.object(release, "apk_version", self.inspect_apk):
            highest = release.published_versions(client, "unused-aapt2", report)
        return highest, report

    def test_every_published_apk_and_prerelease_counts(self):
        client = FakeGitHub([published(2), published(1, prerelease=True)], {
            "releases/2/assets": [apk(20, "newer.apk"), apk(21, "other-device.APK")],
            "releases/1/assets": [apk(10, "older-but-higher.apk")],
        }, {20: "10", 21: "11", 10: "99"})
        highest, report = self.check_history(client)
        self.assertEqual(highest, 99)
        self.assertEqual(len(report["published_artifacts"]), 3)
        self.assertEqual(client.visited, ["releases", "releases/2/assets", "releases/1/assets"])

    def test_empty_history_is_an_explicit_first_release(self):
        highest, report = self.check_history(FakeGitHub([], {}, {}))
        self.assertEqual(highest, 0)
        self.assertEqual(report["published_artifacts"], [])

    def test_drafts_are_not_published_history(self):
        self.assertEqual(self.check_history(FakeGitHub([{"draft": True}], {}, {}))[0], 0)

    def test_published_release_missing_version_evidence_fails(self):
        client = FakeGitHub([published(1)], {"releases/1/assets": []}, {})
        with self.assertRaisesRegex(ValueError, "no APK or release-metadata"):
            self.check_history(client)

    def test_unreadable_apk_fails_instead_of_skipping(self):
        client = FakeGitHub([published(1)], {"releases/1/assets": [apk(1, "broken.apk")]}, {1: "broken"})
        with self.assertRaisesRegex(ValueError, "positive decimal"):
            self.check_history(client)

    def test_missing_release_publication_state_fails(self):
        with self.assertRaisesRegex(ValueError, "incomplete published-release"):
            self.check_history(FakeGitHub([{"id": 1}], {}, {}))

    def manifest(self, **extra):
        return dict({"schema_version": 1, "package_name": release.PACKAGE, "version_code": 100,
                     "version_name": "1.0", "signing_identity": "production", "signer_sha256": "a" * 64,
                     "apk_sha256": "b" * 64, "apk_file": "release.apk", "apk_bytes": 100,
                     "source_commit": "c" * 40, "source_tree": "d" * 40}, **extra)

    def metadata_client(self, metadata):
        return FakeGitHub([published(1)], {"releases/1/assets": [apk(1, "release-metadata.json")]}, {1: json.dumps(metadata)})

    def test_complete_metadata_without_retained_apk_counts(self):
        self.assertEqual(self.check_history(self.metadata_client(self.manifest()))[0], 100)

    def test_partial_and_test_signed_metadata_fail(self):
        for change in ({"signing_identity": "test-only"}, {"package_name": "wrong"},
                       {"signer_sha256": "bad"}, {"version_code": True}, {"source_commit": ""},
                       {"source_tree": "wrong"}, {"apk_bytes": True}, {"apk_file": "../release.apk"}, {"schema_version": True}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.check_history(self.metadata_client(self.manifest(**change)))

    def test_disagreement_between_metadata_and_apk_fails(self):
        client = self.metadata_client(self.manifest())
        client.assets["releases/1/assets"].append(apk(2, "release.apk"))
        client.contents[2] = "99"
        with self.assertRaisesRegex(ValueError, "disagree"):
            self.check_history(client)

    def test_all_api_pages_are_flattened(self):
        with patch.object(release, "command", return_value='[[{"id":1}],[{"id":2}]]') as command:
            self.assertEqual(release.GitHubReleases("owner/repo").pages("releases"), [{"id": 1}, {"id": 2}])
        self.assertIn("--paginate", command.call_args.args)
        self.assertIn("--slurp", command.call_args.args)

    def test_bad_api_shape_is_not_empty_history(self):
        with patch.object(release, "command", return_value='{"message":"denied"}'), self.assertRaises(ValueError):
            release.GitHubReleases("owner/repo").pages("releases")

    def test_equal_or_lower_candidate_fails_and_preserves_evidence(self):
        for code in ("100", "99"):
            with self.subTest(code=code), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "history.json"
                argv = ["release_artifacts.py", "history", "--repository", "owner/repo", "--aapt2", "unused",
                        "--version-code", code, "--expected-source", "c" * 40, "--output", str(output)]
                with patch("sys.argv", argv), patch.object(release, "provenance"), \
                     patch.object(release, "GitHubReleases", return_value=self.metadata_client(self.manifest())), \
                     redirect_stdout(io.StringIO()), self.assertRaisesRegex(ValueError, "must exceed"):
                    release.main()
                report = json.loads(output.read_text())
                self.assertEqual(report["status"], "failed")
                self.assertEqual(report["highest_published_version_code"], 100)
                self.assertEqual(report["source_commit"], "c" * 40)

    def test_higher_candidate_passes_against_recorded_history(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "history.json"
            argv = ["release_artifacts.py", "history", "--repository", "owner/repo", "--aapt2", "unused",
                    "--version-code", "101", "--expected-source", "c" * 40, "--output", str(output)]
            with patch("sys.argv", argv), patch.object(release, "provenance"), \
                 patch.object(release, "GitHubReleases", return_value=self.metadata_client(self.manifest())), \
                 redirect_stdout(io.StringIO()):
                release.main()
            self.assertEqual(json.loads(output.read_text())["status"], "passed")

    def test_version_limits_and_missing_values(self):
        self.assertEqual(release.version_code("2100000000"), 2_100_000_000)
        for value in ("", "0", "-1", "01", "1.0", True, None, "2100000001"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                release.version_code(value)

    def test_fingerprint_normalizes_only_valid_sha256(self):
        self.assertEqual(release.fingerprint(":".join(["AB"] * 32)), "ab" * 32)
        for value in ("", "ab" * 31, "gg" * 32, "abc def"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                release.fingerprint(value)

    def test_signature_entries_do_not_change_payload_but_dex_does(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory) / name for name in ("unsigned.apk", "signed.apk", "different.apk")]
            for path, dex, signed in zip(paths, (b"dex", b"dex", b"changed"), (False, True, True)):
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("classes.dex", dex)
                    archive.writestr("META-INF/library.kotlin_module", "must compare")
                    if signed:
                        archive.writestr("META-INF/CERT.RSA", "signature")
            self.assertEqual(apk_payload(paths[0]), apk_payload(paths[1]))
            self.assertNotEqual(apk_payload(paths[0]), apk_payload(paths[2]))

    def test_unsigned_zip_can_have_an_unsigned_jar_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "unsigned.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"dex payload with APK Sig Block 42 inside it")
                archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
            release.require_unsigned(apk)

    def test_damaged_jar_signature_is_not_unsigned(self):
        with tempfile.TemporaryDirectory() as directory:
            for name in ("META-INF/CERT.RSA", "META-INF/invalid.sf", "META-INF/SIG-BROKEN"):
                with self.subTest(name=name):
                    apk = Path(directory) / "damaged-signed.apk"
                    with zipfile.ZipFile(apk, "w") as archive:
                        archive.writestr("classes.dex", b"dex")
                        archive.writestr(name, b"not a valid signature")
                    with self.assertRaisesRegex(ValueError, "JAR signature entries"):
                        release.require_unsigned(apk)

    def test_damaged_apk_signing_block_is_not_unsigned(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "damaged-signed.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"dex")
            data = apk.read_bytes()
            eocd = data.rfind(b"PK\x05\x06")
            central_offset = struct.unpack_from("<I", data, eocd + 16)[0]
            # Deliberately invalid signing-block contents: verification could not print a signer.
            broken_block = b"broken-signature-block" + b"APK Sig Block 42"
            data = bytearray(data[:central_offset] + broken_block + data[central_offset:])
            struct.pack_into("<I", data, eocd + len(broken_block) + 16, central_offset + len(broken_block))
            apk.write_bytes(data)
            with self.assertRaisesRegex(ValueError, "APK Signing Block"):
                release.require_unsigned(apk)

    def test_junit_counts_keep_skipped_failures_and_errors_separate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "TEST-first.xml").write_text('<testsuite tests="10" failures="1" errors="2" skipped="3"/>')
            (root / "TEST-second.xml").write_text('<testsuite tests="4" failures="0" errors="0" skipped="1"/>')
            self.assertEqual(release.junit_counts(root), {"status": "produced", "suites": 2, "tests": 14,
                             "failures": 1, "errors": 2, "skipped": 4, "passed": 7})

    def test_missing_junit_is_not_a_zero_test_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(release.junit_counts(directory), {"status": "not-produced", "suites": 0})


if __name__ == "__main__":
    unittest.main()
