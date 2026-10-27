#!/usr/bin/env python3
"""Host-only safety/regression tests for permanent Nocturne TEST signing."""

import contextlib
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import permanent_test_signing as signing


class PermanentTestSigningTest(unittest.TestCase):
    def test_no_secrets_skips_without_failure_or_file(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "github-out"
            with contextlib.redirect_stdout(io.StringIO()) as stream:
                result = signing.run(SimpleNamespace(), {"GITHUB_OUTPUT": str(out)})
            self.assertFalse(result)
            self.assertEqual(out.read_text(), "signed=false\n")
            self.assertIn("skipped", stream.getvalue())

    def test_partial_secrets_skip_and_reveal_no_values(self):
        for name in signing.SECRET_NAMES:
            with self.subTest(missing=name):
                env = {k: "confidential-test-value" for k in signing.SECRET_NAMES if k != name}
                with contextlib.redirect_stdout(io.StringIO()) as stream:
                    self.assertFalse(signing.run(SimpleNamespace(), env))
                self.assertNotIn("confidential-test-value", stream.getvalue())

    def test_wrong_alias_fails_closed(self):
        env = {k: "secret-value" for k in signing.SECRET_NAMES}
        env["NOCTURNE_TEST_KEY_ALIAS"] = "production"
        with self.assertRaisesRegex(RuntimeError, "Unexpected test signing alias"):
            signing.run(SimpleNamespace(), env)

    def test_malformed_keystore_never_reaches_sdk(self):
        env = {k: "secret-value" for k in signing.SECRET_NAMES}
        env["NOCTURNE_TEST_KEY_ALIAS"] = "nocturne-test"
        with self.assertRaisesRegex(RuntimeError, "base64"):
            signing.run(SimpleNamespace(), env)

    def test_single_expected_certificate_and_both_schemes(self):
        text = ("Verified using v2 scheme (APK Signature Scheme v2): true\n"
                "Verified using v3 scheme (APK Signature Scheme v3): true\n"
                "Signer #1 certificate SHA-256 digest: " + signing.EXPECTED_CERT_SHA256.upper() + "\n")
        self.assertEqual(signing.certificate_digest(text), signing.EXPECTED_CERT_SHA256)
        with self.assertRaises(RuntimeError):
            signing.certificate_digest(text.replace("v3): true", "v3): false"))
        with self.assertRaises(RuntimeError):
            signing.certificate_digest(text + "Signer #2 certificate SHA-256 digest: " + "a" * 64 + "\n")
        with self.assertRaises(RuntimeError):
            signing.certificate_digest(text.replace("SHA-256 digest:", "SHA-256 hash:"))

    def test_package_version_exact(self):
        accepted = "package: name='io.github.willzy12h.nocturne' versionCode='20261027' versionName='test'"
        signing.require_package_version(accepted, 20261027)
        for unexpected in (accepted.replace("nocturne'", "nocturne.dev'"),
                           accepted.replace("20261027", "20261026"),
                           accepted.replace("package:", "other:")):
            with self.assertRaises(RuntimeError):
                signing.require_package_version(unexpected, 20261027)

    def test_pinned_test_certificate_is_not_gpt11(self):
        self.assertEqual(signing.EXPECTED_CERT_SHA256,
                         "064252ba12e8d8535a8d979688f763b182c7d6ff6ac0c33ddef5f71f42f8dd78")
        self.assertNotEqual(signing.EXPECTED_CERT_SHA256,
                            "42efc6e2284a00ae55345479177e27c45242196c02acb0f51640b886f7c2bde9")


if __name__ == "__main__":
    unittest.main(verbosity=2)