# Release instructions

## Scope and evidence

This guide describes the repository at **`c15c515a4dabbeae719c12323b6a6c0639c8406e`**
(`gpt/nocturne-f1`), the exact base of this documentation pass. All source
`file:line` references below refer to that revision. Official Android and GitHub
guidance was checked on **24 October 2026**.

**A `TEST-ONLY` APK is never a production release.** Production requires the
owner's approved signing identity and GitHub settings. This documentation pass
does not generate a new minified APK, establish a current APK hash or certificate,
or verify installation or upgrade on a physical device. Record the new
documentation gate separately in [register.md](register.md) and
[docs2-reconciliation.md](docs2-reconciliation.md).

The [release candidate report](release-candidate-report.md) preserves earlier
release evidence. Its integration APK came from an uncommitted working tree and
has no commit-pinned package identity; the older CI candidate belongs to commit
`2d9ebf4f625f0eaccd6bbead1daa17a42b56a3d6`. Neither is an APK of this guide's exact
base. Keep their original dates, source descriptions and hashes when citing them.
Sources: `docs/review/release-candidate-report.md:90–127,269–316`.

## 1. Choose the right artefact

| Artefact | Signing identity and purpose | Source at the reviewed base |
| --- | --- | --- |
| Debug APK | Development build using Android's debug signing arrangement. It is not the release candidate. The signing gate verifies that the test release uses a different certificate from the actual debug APK. | `.github/workflows/build.yml:103–109`; `.github/workflows/release_signing.py:130–137`; [Android signing guidance][android-signing] |
| Unsigned release APK | Release variant with R8 and resource shrinking, produced when all four local signing inputs are absent. It is an inspection/signing input and cannot be installed as an unsigned APK. | `app/build.gradle.kts:57–100`; `.github/workflows/release_signing.py:80–94`; [Android signing guidance][android-signing] |
| `nocturne-<version>-TEST-ONLY.apk` | Minified release variant signed with a newly generated, temporary RSA test key. The driver deletes that key and records `signing_identity: test-only`. It is for supervised acceptance only. | `.github/workflows/release_signing.py:96–143` |
| `nocturne-<version>.apk` from the production package | The verified unsigned input, signed by the protected production job. Its certificate must match the independently established production fingerprint. Only this package feeds the release publication job. | `.github/workflows/build.yml:299–347,377–405` |

The release build has no automatic debug-key fallback. A partially supplied
signing configuration, absent key file, invalid password or unusable alias fails
configuration. Valid local signing uses all four `NOCTURNE_KEYSTORE`,
`NOCTURNE_KEYSTORE_PASSWORD`, `NOCTURNE_KEY_ALIAS` and `NOCTURNE_KEY_PASSWORD`
environment values. These are build inputs, not instructions to copy production
credentials into a developer shell. Source: `app/build.gradle.kts:57–100`.

Each test-candidate run creates a different certificate. A newer `TEST-ONLY` APK
therefore cannot be assumed to update a previous test candidate, a debug build or
a production installation. The same package name does not make different
signers interchangeable. Preserve the data needed for acceptance before any
change of installation; do not uninstall an existing installation just to bypass
a signer mismatch. The current production signing command supplies no signing
lineage for a planned key rotation. Sources:
`.github/workflows/release_signing.py:96–143`;
`.github/workflows/build.yml:339–342`; [Android signing guidance][android-signing]
and [Android signing-key rotation][android-v3].

## 2. Complete the owner's production settings

Follow [Production release settings — owner action](release-production-settings.md)
before creating the next production tag. **The live settings, secret scopes,
reviewer membership and production certificate remain UNVERIFIED by this work.**
The workflow's `environment: production` declaration names an environment; it
does not create its protection rules. GitHub may create a missing environment
without protections. Sources: `.github/workflows/build.yml:251–254`;
`docs/review/release-production-settings.md:1–14`;
[GitHub environment configuration][github-environments].

The owner checklist is:

- Configure the `production` environment with an independent required reviewer,
  self-review prevention and administrator bypass disabled. Allow deployments
  through **Selected branches and tags**, with exactly one rule: **Tag `v*`**.
  Do not add a branch rule. Source:
  `docs/review/release-production-settings.md:16–43`;
  [GitHub environment configuration][github-environments].
- Keep `NOCTURNE_KEYSTORE_BASE64`, `NOCTURNE_KEYSTORE_PASSWORD`,
  `NOCTURNE_KEY_ALIAS` and `NOCTURNE_KEY_PASSWORD` as **production environment
  secrets**. Keep `NOCTURNE_RELEASE_CERT_SHA256` as a **production environment
  variable**. Remove broader copies or organisation access as the owner guide
  specifies. Keep `NOCTURNE_RELEASE_VERSION_CODE` as a **repository variable**,
  because the earlier `build` job needs it. Sources:
  `docs/review/release-production-settings.md:45–100`;
  `.github/workflows/build.yml:56–76,282–292,319–347`.
- Protect `v*` tags using both active rulesets in the owner guide: narrowly
  authorised creation, and a separate update/deletion restriction with an empty
  bypass list. Retain dated, non-secret evidence of both configurations. Sources:
  `docs/review/release-production-settings.md:102–143,175–195`;
  [GitHub ruleset restrictions][github-rules] and
  [ruleset layering][github-layering].

Keep private keys and passwords out of repository files, artefact uploads and
review evidence. Record public certificate fingerprints and setting names/scopes
instead. The production signing step decodes its keystore into temporary storage,
uses environment-based password inputs, and removes the temporary key on exit;
its upload paths contain the release package and evidence. This describes the
workflow, not verified live secret configuration. Source:
`.github/workflows/build.yml:319–347,365–382`.

## 3. Review the release run

The workflow runs on pushes to `main`, `v*` tag pushes and pull requests. For a
tag, it removes the leading `v` to obtain `versionName` and requires an explicit
positive `NOCTURNE_RELEASE_VERSION_CODE` no greater than `2100000000`. Non-tag CI
uses `0.2.0-hardening-test.<run number>` and a code of `run number + 1`. A local
build without version properties defaults to `0.1.0` / `1`; those defaults are not
proof of an approved release version. Sources:
`.github/workflows/build.yml:3–7,56–76`; `app/build.gradle.kts:48–54`.

For the next authorised release:

1. Select the reviewed commit and intended version, and complete the owner
   settings above before a `v*` tag is pushed. Record the tag, commit and expected
   production certificate. Check that the run's source evidence identifies that
   commit; the workflow checks out and records its explicit `SOURCE_SHA` and tree.
   Sources: `.github/workflows/build.yml:18,34–46`;
   `docs/review/release-production-settings.md:145–173`.
2. Require the whole `build` job to pass. It runs helper regressions, core/app
   tests, debug assembly/lint, unsigned and test-signed release checks, release
   serialisation checks, a manifest inventory and screenshot checks. A
   `TEST-ONLY` artefact can be uploaded after signing succeeds even if a later
   gate fails, so an available APK alone is insufficient. Sources:
   `.github/workflows/build.yml:96–188,190–239`.
3. Review that run and attempt before approving its `production` deployment.
   The production job requires successful `build` and a `v*` tag push. After
   approval it checks published version history and rechecks the transferred
   unsigned APK's source, hash, package/version and unsigned identity. Missing or
   inconsistent published version evidence fails; the new version code must be
   greater than all recorded published codes, including prereleases. Sources:
   `.github/workflows/build.yml:251–317`;
   `.github/workflows/release_artifacts.py:124–196,240–256`;
   `docs/review/release-production-settings.md:153–173`.
4. Require successful production signing and certificate verification. The
   publication job depends on production and downloads only that run's production
   package. It publishes the APK, `release-metadata.json` and `SHA256SUMS.txt`.
   Tags share a non-cancelling release concurrency group. Re-running a published
   tag with an equal version code fails the history check. Sources:
   `.github/workflows/build.yml:12–15,319–347,384–405`;
   `.github/workflows/release_artifacts.py:240–256`.
5. Apply the file checks below to the downloaded APK before installation, and
   preserve the release/run URL, attempt, commit/tree, version, APK SHA-256 and
   signer fingerprint with the acceptance record. Metadata supplies the source,
   version, APK and signer fields from the inspected APK and clean checkout;
   preserve the release/run URL and attempt separately. `gate-outcomes.json`
   records the CI run ID and attempt. Sources:
   `.github/workflows/release_artifacts.py:45–60,86–115`;
   `.github/workflows/build.yml:205–207`.

This guide does not create a tag, approve a deployment or publish a release.

## 4. Verify the APK before installing

Obtain the APK and its own `release-metadata.json` and `SHA256SUMS.txt` from the
same trusted release or CI run/attempt. For test acceptance, use the explicitly
named `nocturne-TEST-ONLY-<run id>-<attempt>` artefact and its readme. For production,
use the production release assets. Their names and contents are defined at
`.github/workflows/build.yml:232–248,377–405` and
`.github/workflows/release_signing.py:122–143`.

Use Android SDK Build Tools with `aapt2` and `apksigner`; the workflow uses
**35.0.0** (`.github/workflows/build.yml:93–94,275`). The following commands inspect
local files. Replace the three example paths with the actual paths and run each
command successfully before continuing. They do not need a private signing key.
Android documents `aapt2 dump badging` for manifest information and `apksigner
verify --verbose --print-certs` for signature verification and certificate output.
References: [AAPT2][android-aapt2], [apksigner][android-apksigner].

```bash
NOCTURNE_APK='/absolute/path/to/the-downloaded.apk'
NOCTURNE_METADATA='/absolute/path/to/release-metadata.json'
NOCTURNE_BUILD_TOOLS='/absolute/path/to/android-sdk/build-tools/35.0.0'

python3 -m json.tool "$NOCTURNE_METADATA"
"$NOCTURNE_BUILD_TOOLS/aapt2" dump badging "$NOCTURNE_APK"
python3 - "$NOCTURNE_APK" <<'PY'
import hashlib
import sys
from pathlib import Path

apk = Path(sys.argv[1])
print("APK SHA-256:", hashlib.sha256(apk.read_bytes()).hexdigest())
print("APK bytes:", apk.stat().st_size)
PY
"$NOCTURNE_BUILD_TOOLS/apksigner" verify --verbose --print-certs "$NOCTURNE_APK"
```

Compare the results as follows. **Stop before installation if any check fails or
the trusted expected value is unavailable.**

| Check | Required comparison |
| --- | --- |
| Source | Metadata `source_commit` and `source_tree` match the reviewed source and the chosen run/tag evidence. A hash of the APK alone does not establish its source. |
| Package | The `package:` line from `aapt2` names `io.github.willzy12h.nocturne`, matching metadata `package_name`. |
| Version | The actual `versionName` and `versionCode` equal metadata `version_name` and `version_code`, and the intended candidate/release. Do not infer either from the filename. |
| APK bytes | The computed SHA-256 equals metadata `apk_sha256` and the entry for this APK in `SHA256SUMS.txt`; the size equals `apk_bytes`. Compare the APK digest, not a downloaded ZIP's digest. |
| Signature | `apksigner verify` exits successfully. Read the **certificate SHA-256 digest**, not the public-key digest or certificate name. The repository gate requires exactly one signer. |
| Signing identity | For production, metadata says `production` and the actual certificate digest matches the independently approved production fingerprint. For a test candidate, metadata says `test-only` and the digest matches the trusted metadata for that exact run/attempt. |

The metadata field names and repository checks above come from
`.github/workflows/release_artifacts.py:16,45–60,86–115`; production compares the
actual certificate against `NOCTURNE_RELEASE_CERT_SHA256` at
`.github/workflows/build.yml:326,343–347`. Fingerprints may be normalised by
removing colons and comparing case-insensitively, but must contain exactly
64 hexadecimal digits (`.github/workflows/release_artifacts.py:29–33`).

A valid signature proves that the APK verifies under its reported signing
certificate. It does not by itself tell you that this is the intended production
signer. Establish the production reference through the owner's trusted signing
record or a previously authenticated production APK. A fingerprint copied only
from the new APK and accepted without an independent reference does not establish
that identity. Likewise, matching a hash supplied beside a file checks
consistency; the release/run and expected signer still need to be trusted.
These are the trust assumptions for the comparisons above.

### Read signature-scheme results correctly

- **v1** is the older JAR signing scheme. **v2** uses an APK signing block and is
  supported from Android 7.0. **v3** extends that scheme with supported-SDK and
  signing-key rotation information. References: [apksigner][android-apksigner],
  [v2][android-v2], [v3][android-v3].
- A line reporting `false` for one scheme does not, by itself, mean the APK is
  unsigned or invalid. Use the overall `apksigner verify` result for the APK's
  supported Android versions and record the schemes it actually verifies.
  Nocturne declares `minSdk = 31`; do not require v1 merely because an older
  signing checklist mentions it. Sources: `app/build.gradle.kts:48–51`;
  [apksigner][android-apksigner] and [v2][android-v2].
- **v4** produces a separate `.idsig` file. This workflow explicitly disables v4
  when signing production; a missing v4 signature is therefore not a production
  verification failure. It still requires successful APK verification and the
  expected signer. Sources: `.github/workflows/build.yml:339–347`;
  [apksigner][android-apksigner].

Do not edit or re-sign an APK after checking it. Recheck the final bytes if they
change. Android's signing guidance explains that changing a signed APK invalidates
its signature. Reference: [apksigner][android-apksigner].

## 5. Keep automated evidence and physical acceptance separate

| Evidence | What the repository checks | Remaining limit |
| --- | --- | --- |
| Signing and packaging | Unsigned output; all partial signing combinations and invalid-key probes; explicit test signer distinct from debug; identical unsigned/test program and resource bytes; actual package/version/hash/certificate checks. Sources: `.github/workflows/release_signing.py:36–58,80–149`; `.github/workflows/release_artifacts.py:86–115`. | A production key, live GitHub settings and installation/upgrade still need their own evidence. |
| Shrinking and serialisation | R8/resource shrinking is enabled. The release fixture tests model round trips and private snapshot-wrapper persistence, then repeats them on separately R8-shrunk JVM classfiles. The gate also inspects actual APK class definitions and mapping/usage evidence. Sources: `app/build.gradle.kts:94–100`; `.github/workflows/release-serialization/ReleaseSerializationTest.kt:92–168`; `.github/workflows/release_serialization.py:250–267,340–403,438–453`. | The separate JVM run uses test roots and unshrunk dependencies. It does not execute APK DEX or prove Android restoration, widgets or arbitrary historical schema compatibility. |
| This documentation pass | Source review at the exact base above; the separate documentation gate recorded by the integrating reviewer. | No fresh minified build or current signed package identity is claimed here. Physical install, upgrade, Samsung/BedJet operation and TalkBack acceptance remain **NOT RUN** by this pass. |

Use [the supervised acceptance sheet](samsung-bedjet-acceptance.md) for installation
and runtime results. Keep the actual run status, device/build identity, signer and
observations with each result; do not turn a source review or historical host test
into a physical pass.

## Official references

- [Android SDK `apksigner`][android-apksigner]
- [Android SDK AAPT2][android-aapt2]
- [Android app signing and update identity][android-signing]
- [APK Signature Scheme v2][android-v2]
- [APK Signature Scheme v3 and signing-key rotation][android-v3]
- [GitHub environment configuration][github-environments]
- [GitHub ruleset restrictions][github-rules]
- [GitHub ruleset layering][github-layering]

[android-apksigner]: https://developer.android.com/tools/apksigner
[android-aapt2]: https://developer.android.com/tools/aapt2
[android-signing]: https://developer.android.com/studio/publish/app-signing
[android-v2]: https://source.android.com/docs/security/features/apksigning/v2
[android-v3]: https://source.android.com/docs/security/features/apksigning/v3
[github-environments]: https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments
[github-rules]: https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets
[github-layering]: https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets
