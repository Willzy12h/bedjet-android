#!/usr/bin/env python3
"""Host-only safety/regression tests for permanent Nocturne TEST signing."""

import contextlib
import base64
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import permanent_test_signing as signing


class PermanentTestSigningTest(unittest.TestCase):
    def test_android12_apk_with_both_signatures_is_accepted(self):
        # apksigner's default manifest range (minSdk 31) verifies v3 only, even
        # when v2 is present. Reproduce the observed Android Build Tools output
        # at the subprocess boundary without requiring a private key in tests.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'input.apk'
            source.write_bytes(b'packaged-program-bytes')
            output = root / 'result'
            env = dict(zip(signing.SECRET_NAMES, (
                base64.b64encode(b'\xfe\xed\xfe\xed' + bytes(2000)).decode(),
                'private-test-password', 'nocturne-test', 'private-test-password')))
            env['GITHUB_OUTPUT'] = str(root / 'github-output')

            def sdk_run(command, **kwargs):
                if command[1] == 'sign':
                    Path(command[command.index('--out') + 1]).write_bytes(source.read_bytes())
                if command[1] == 'verify':
                    covers_v2 = ('--min-sdk-version' in command and
                                 int(command[command.index('--min-sdk-version') + 1]) <= 27)
                    stdout = ('Verified using v2 scheme (APK Signature Scheme v2): ' +
                              str(covers_v2).lower() + '\n' +
                              'Verified using v3 scheme (APK Signature Scheme v3): true\n' +
                              'Signer #1 certificate SHA-256 digest: ' + signing.EXPECTED_CERT_SHA256 + '\n')
                elif command[1] == 'dump':
                    stdout = "package: name='io.github.willzy12h.nocturne' versionCode='20261078'"
                else:
                    stdout = ''
                return subprocess.CompletedProcess(command, 0, stdout=stdout, stderr='')

            args = SimpleNamespace(sdk=str(root / 'sdk'), build_tools='35.0.0',
                                   unsigned=str(source), output_dir=str(output), version_code=20261078)
            with patch.object(signing.subprocess, 'run', side_effect=sdk_run), \
                    contextlib.redirect_stdout(io.StringIO()) as stream:
                try:
                    accepted = signing.run(args, env)
                except RuntimeError as error:
                    self.fail(f'Valid dual-signature Android 12 APK was refused: {error}')
                self.assertTrue(accepted)
            self.assertEqual((root / 'github-output').read_text(), 'signed=true\n')
            metadata = json.loads((output / 'test-signature-metadata.json').read_text())
            self.assertEqual(metadata['signature_schemes'], ['v2', 'v3'])
            self.assertEqual(metadata['signing_certificate_sha256'], signing.EXPECTED_CERT_SHA256)
            self.assertNotIn('private-test-password', stream.getvalue())

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
        for scheme in ('v2', 'v3'):
            with self.subTest(scheme=scheme), self.assertRaises(RuntimeError):
                signing.certificate_digest(text.replace(scheme + "): true", scheme + "): false"))
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
                         "bd233ddcf84f6ed97a1597d433a0137dd2c0e64b13c2c537bb8ee5508de85f15")
        self.assertNotEqual(signing.EXPECTED_CERT_SHA256,
                            "42efc6e2284a00ae55345479177e27c45242196c02acb0f51640b886f7c2bde9")


if __name__ == "__main__":
    unittest.main(verbosity=2)
