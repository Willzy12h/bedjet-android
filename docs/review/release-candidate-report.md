# Nocturne release candidate report

## Candidate integration — 24 October 2026

The checked working source combines the published Part A head
`0761e37085f3ae429f5d9a83d42b2541e7a14891` with candidate
`0c91d25935a3d4b093abc2095c8a3637e2d8ca99`, which includes the P5 changes and the
earlier `1612bdb` release follow-up. Conflict resolution retains the stricter
rejection of every required class listed as removed in R8 `usage.txt`, the
separate post-R8 JVM fixture execution and the removal of the moved-member
fallback. The other release workflow, harness, fixture, helper and requirements
files are unchanged from the published Part A head; action pins, the `production`
environment declaration and signing/hash requirements are preserved.

### Merged-source required gate

The fresh `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
gate passed in **7 minutes 21 seconds**, with **60 tasks: 56 executed and four
up-to-date**. All **197 frozen input file hashes** remained unchanged through
the gate. Counts come from the preserved merged-source JUnit XML.

| Suite | Suites | Discovered | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Core | 20 | 156 | 156 | 0 | 0 | 0 |
| App debug | 59 | 472 | 466 | 0 | 0 | 6 |
| **Total** | **79** | **628** | **622** | **0** | **0** | **6** |

Debug assembly passed. Lint recorded **zero errors, zero fatal issues, 23 warnings
and one hint**. The six skips remain the existing opt-in `ScreenshotCapture`
methods; this gate does not claim fresh screenshots.

The four `ManualWakeTitleTest` methods are included in the app total and all
passed. Before the notification-title correction, the same four-test regression
suite produced two expected assertion failures, for an unknown manual end and a
Remote adjustment reusing the previous manual end; its two controls passed.
The helper rerun passed **21 signing/artefact, eight screenshot-helper and 35
serialisation tests**. Actionlint `1.7.12` passed.

Preserved merged-source evidence is under `followups-evidence/integration/`:
`required-tests-counts.json`, `required-suites.json`, `required-gate-xml/`,
`lint-results-debug.xml`, `required-gate.log` and the
`manual-wake-title-red.*` records.

### Merged-source release evidence

The combined release harness/export/build passed in **2 minutes 2 seconds**, with
**61 tasks: 56 executed and five up-to-date**. Release JUnit records **two methods
passed, zero failures, errors or skips**: the complete model fixture round trips
and private snapshot-wrapper persistence. Those are the pre-R8 JUnit methods;
the subsequent execution of the same harness on separate R8 classfiles is
reported below.

| Post-R8 JVM check | Integrated-source observed result |
| --- | --- |
| R8 compiler | `8.10.24`, from the resolved AGP artefact |
| Input release/harness classfiles | **1,025** |
| Production serialisable models / JSON cases | **48 / 105**, all passed |
| Shared fixture methods | Both completed, including private snapshot-wrapper persistence |
| Models renamed / executed model hashes changed | **48 / 48** for each check |
| Production classes removed | **881** |
| Input / output jar size | **2,465,835 / 201,146 bytes** |
| Output jar SHA-256 | `17bd98a0af3e80a06b84a7e4a5ef9de17faf949bc224305ba00cb9d1c7c082bd` |
| Fixture SHA-256 | `d5602f0961190359ad2864e22c32d681d0643565d66100ef0c12cc45443aa8f6` |

The runtime run used companion serializer-factory test roots and **202 unshrunk
dependency jars**, with JVM bytecode verification and no original app/core jars
on its runtime classpath. Existing application and serialization consumer rules
remain in effect; no blanket model/serializer keep rule or optimisation-disabling
switch was added. The gate checks the compiled model, fixture, compiler, rules,
input/output, dependency and execution-log hashes, matches loaded model names and
bytes to the shrunk jar, and requires actual model renaming and class removal.

The combined gate passed for all **48 models and 105 cases**. Its actual APK
structural result is **one DEX, 4,470 defined classes, R8 `8.10.24` full mode and
mapping ID `bc4fc8d`**. Every model and serializer check uses direct
`mapped-class` evidence, and none is listed as removed in the APK's R8 usage
report. The separately executed R8 version matches the APK mapping. The fresh
merged-manifest gate passed for **39 components**, with the component inventory
equal to the pre-integration inventory. All **197 frozen input file hashes**
remained unchanged after the release checks as well.

**Proof boundary:** the shared fixtures executed on separate R8 classfile output
with test entry points and unshrunk dependency jars. The actual APK DEX was
inspected structurally and was **not executed**. The JVM run does not reproduce
Android entry-point reachability, whole-program dependency shrinking or
DEX-specific optimisation, and establishes no Android restoration, widget,
arbitrary historical schema or physical acceptance result. The combined report
records `shrunkJvmRuntimeExecuted: true` and `apkRuntimeExecuted: false`.

### Integrated-source local unsigned validation APK

| Local validation APK | Exact value |
| --- | --- |
| Preserved APK | `followups-evidence/integration/integration-unsigned.apk` |
| Version name / code | `0.1.0` / `1` |
| APK size | **4,327,639 bytes** |
| APK SHA-256 | `c22f916ccb2dc1de15e6a4b2645761a582ed11c45395f0c00f4c6a02ac015de4` |
| Signing identity / signer certificate | **Unsigned / none** |
| Recorded source commit | `null` — uncommitted candidate-integration working tree |

The APK size and digest were recomputed from the preserved file. Its version was
checked with Android build tools. The unsigned-structure check passed and
`apksigner` returned the expected exit code `1`, with no certificate. Its embedded
VCS metadata records `generate_error_reason: NO_VALID_GIT_FOUND`.

This APK was built from the checked, uncommitted integration source before the
merge/handover commit. `unsigned-apk-metadata.json` records both integration
parents, the working-tree source kind and a null source commit. The frozen source
hashes and release artefact hashes bind the local checks to their tested bytes;
they do not assign the APK to a future Git head. This is a local validation
artefact, with no commit-pinned candidate provenance claim.

The integration evidence directory preserves `release-build.log`,
`release-serialization-junit.xml`, `serialization-shrunk/shrunk-runtime.json`,
`serialization-shrunk-driver.log`, `release-serialization.json`,
`release-serialization-gate.log`, `release-manifest/report.json` and
`unsigned-apk-metadata.json`. The saved JUnit XML matches the JUnit hash bound by
the fresh runtime report. The older Part A results below remain pre-integration
evidence with their original artefact identities.

**CI for the final merged branch head remains pending at this report's
pre-publication freeze.** Use [PR #2](https://github.com/Willzy12h/bedjet-android/pull/2) and the
Build run whose `head_sha` matches the containing commit to establish CI success,
its exact source commit/tree and its own package identity.
Historical run `40035597694` remains tied to `2d9ebf4`; neither it nor the
pre-integration Part A APK is a build of this integration. No production tag, signing
run or owner-setting change is part of the integration.

## Part A — 24 October 2026

**Pre-integration record:** this section preserves the local working-source
checks recorded before candidate `0c91d259` was integrated, subsequently
published through `0761e37`. Its local APK was built before the handover commits;
the source-provenance limitation recorded below remains in force. Use the
candidate-integration section above for the current validation state.

The pre-integration Part A work started from candidate
`3c3a64e91a20524f0ed6b39f84123067c38cbe3c`. The reviewed release implementation from
`1612bdb74f4c6584e0e7c017895454071fecfc12` has been carried forward after confirming
that the candidate made no intervening changes to these release files. Its action
pins, `production` environment declaration, signing/hash checks and hashed Pillow
requirements are retained. No GitHub environment, reviewer, ruleset, variable,
secret or other owner setting has been changed.

### Verification before candidate integration

The serialisation structural gate now rejects a required model or its executed
serializer when its original class name is listed as removed in R8 `usage.txt`,
even if `mapping.txt` points to a class defined in the APK. The new negative test
first failed for both a removed model and a removed serializer; after the guard
change the complete Python serialisation suite passes **35 tests**. Moved or
inlined member references remain insufficient evidence.

The final helper rerun also passed **21 signing/artefact tests** and **8
screenshot-helper tests**. Actionlint `1.7.12` accepted the workflow. These helper
results are separate from the application build and release execution.

### Required gate before candidate integration

The final `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
gate passed in **6 minutes 57 seconds**, with **59 tasks: 16 executed and 43
up-to-date**. Counts were preserved from the final gate's raw JUnit XML before
the release invocation.

| Suite | Suites | Discovered | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Core | 20 | 156 | 156 | 0 | 0 | 0 |
| App debug | 51 | 439 | 433 | 0 | 0 | 6 |
| **Total** | **71** | **595** | **589** | **0** | **0** | **6** |

Debug assembly passed. Lint recorded **zero errors, zero fatal issues, 23 warnings
and one hint**. The six skipped tests are the existing opt-in screenshot captures;
this gate does not claim a fresh render result. All **173 tested input file
hashes** matched the pre-integration working source.

The preserved evidence is `required-tests-counts.json`, `required-suites.json`,
`required-gate-xml/`, `lint-results-debug.xml` and `required-gate.log` in the
pre-integration follow-up evidence directory.

### Release evidence before candidate integration

The combined release invocation passed in **1 minute 53 seconds**, with **61
tasks: 58 executed and 3 up-to-date**. It ran the shared
`:app:testReleaseUnitTest` harness, `:app:exportReleaseSerializationInputs` and
`:app:assembleRelease`. The release JUnit XML records **two methods passed, zero
failures, errors or skips**: model round trips and private snapshot-wrapper
persistence. These are the pre-R8 JUnit results; the standalone JVM execution is
reported separately below.

The shared fixture set covers `RuntimeState.endRequested`: its populated case
uses the existing `NightIdentity` value, its empty-object case retains the default
control, and an explicit `null` case is included. All **48 models and 105 fixture
cases** pass before and after separate R8 JVM shrinking. The computed
`ActiveNight.hasPlannedManualEnd` property adds no persisted field.

| Post-R8 JVM check | Pre-integration observed result |
| --- | --- |
| R8 compiler | `8.10.24`, from the resolved AGP `8.10.1` artefact |
| Input release/harness classfiles | **1,011** |
| Production serialisable models / JSON cases | **48 / 105**, all passed |
| Shared fixture methods | Both completed, including private snapshot-wrapper persistence |
| Models renamed / executed model hashes changed | **48 / 48** for each check |
| Production classes removed | **867** |
| Input / output jar size | **2,431,679 / 201,255 bytes** |
| Output jar SHA-256 | `c23890f6d94e807674c71a4b0d0fe4632e43a1c79e7bf3fadcaeea31f93df151` |
| Fixture SHA-256 | `d5602f0961190359ad2864e22c32d681d0643565d66100ef0c12cc45443aa8f6` |

The runtime check executed the shared harness from separately R8-processed
release classfiles on the JVM, with bytecode verification enabled and without
the original app/core jars on its runtime classpath. It used companion
serializer-factory test roots, the existing application and serialization
consumer rules, and **202 unshrunk dependency jars**. No blanket production
model/serializer keep rule or optimisation-disabling switch was added.

The combined gate passed after checking compiled model, fixture, compiler,
rules, input/output jar, library and execution-log hashes; matching loaded model
bytes; both fixture methods; actual model renaming and production class removal;
and an R8 version matching the APK mapping. Its APK structural result is **one
DEX, 4,458 defined classes, R8 `8.10.24` full mode, mapping ID `cf8328c`**. All
model and serializer checks use direct `mapped-class` evidence; none is listed
as removed in the APK's R8 usage report. The merged-manifest gate also passed
for **39 components**.

**Proof boundary:** the APK's DEX was inspected structurally and was not executed.
The separate JVM compilation does not reproduce Android entry-point reachability,
whole-program dependency shrinking or DEX-specific optimisation. Its passing
result establishes the documented fixture behaviour for that JVM output. It does
not establish Android restoration, widget behaviour, arbitrary historical schema
compatibility or physical BedJet acceptance. The report deliberately records
`shrunkJvmRuntimeExecuted: true` and `apkRuntimeExecuted: false`.

The preserved release evidence is `release-build.log`,
`release-serialization-junit.xml`, `serialization-shrunk/shrunk-runtime.json`,
`serialization-shrunk-driver.log`, `release-serialization.json`,
`release-serialization-gate.log` and `release-manifest/report.json` in the
pre-integration evidence directory. The saved JUnit XML matches the JUnit hash bound by
the runtime report.

### Local unsigned validation APK before candidate integration

| Local validation APK | Exact value |
| --- | --- |
| APK path | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Package | `io.github.willzy12h.nocturne` |
| Version name / code | `0.1.0` / `1` |
| APK size | **4,287,951 bytes** |
| APK SHA-256 | `30846ff881098fbae1551149b6fb58dd727a58f5b0cd20c5bd74d584bbfc2ca0` |
| Signing identity / signer certificate | **Unsigned / none** |

The APK size and SHA-256 were recomputed from the local file. Android build-tools
confirmed its version with `aapt2`; `apksigner` rejected the unsigned APK, as
expected. `local-unsigned-validation.json` records these results.

This is a **local validation build from the uncommitted Part A working source,
before the handover commits**. Git HEAD remained
`3c3a64e91a20524f0ed6b39f84123067c38cbe3c`; the APK includes the working changes
tested above. Its embedded `META-INF/version-control-info.textproto` contains
`generate_error_reason: NO_VALID_GIT_FOUND`, so it has no usable embedded commit
identity. The tested source hashes and release input/artefact hashes bind the
local evidence to those checked bytes; they do not make this a package of a
future handover commit. No commit-pinned candidate provenance is claimed for it.

**CI for the final handover commit is pending at this report's freeze.** The
subsequent pull-request run must establish its own exact source commit/tree and
test-only package identity. The historical CI run below retains its original
`2d9ebf4` identity and cannot establish CI success for the current changes. No
production tag, signing run or owner-settings change is part of this validation.

### Retrieved historical CI candidate — run 40035597694

The [Build run](https://github.com/Willzy12h/bedjet-android/actions/runs/40035597694)
and its artefacts were retrieved through the GitHub API on 24 October 2026. The
run completed successfully for the earlier release branch. These are the exact
CI candidate values from that run's `release-metadata.json`, independently
checked against the downloaded APK. They describe a **test-only** package from
`2d9ebf4`, not the local implementation APK in the 23 October report, the
`1612bdb` follow-up, or the current Part A source.

| CI provenance | Recorded value |
| --- | --- |
| Run / attempt / run number | `40035597694` / `1` / `6` |
| Workflow / event / conclusion | `Build` / `push` / `success` |
| Source branch | `gpt/nocturne-release` |
| Run started / last updated | `2026-10-23T22:45:04Z` / `2026-10-23T23:06:55Z` |
| Source commit | `2d9ebf4f625f0eaccd6bbead1daa17a42b56a3d6` |
| Source tree | `583b8c11c0c0d0e7147c5893035fe1d266779409` |
| Candidate artefact | `nocturne-TEST-ONLY-40035597694-1`, ID `12536649953` |
| Source artefact | `nocturne-source-40035597694-1`, ID `12537250976` |

| CI APK metadata | Exact value |
| --- | --- |
| APK filename | `nocturne-0.2.0-hardening-test.6-TEST-ONLY.apk` |
| Package | `io.github.willzy12h.nocturne` |
| Version name / code | `0.2.0-hardening-test.6` / `7` |
| APK size | **4,254,867 bytes** |
| APK SHA-256 | `3f5dd1515609c33cbfafd2964b65481fc712e498652f9723b350ed0cd506a076` |
| Signer certificate SHA-256 | `114ff771ce149acb3729e7284e8621c1fb4ca60c0367778757490f5338f32c36` |
| Signing identity | `test-only` |

The downloaded candidate ZIP has SHA-256
`7eea885a44864f9141b1cdfd1ce211377a82392198aba9867c97a988c4302cae`;
the source ZIP has SHA-256
`d548d2e072fdfe3a3019d2e82749d84e541b2b8052883055446c56211c0105e9`.
Both match the digests returned by the run's
[artefact API](https://api.github.com/repos/Willzy12h/bedjet-android/actions/runs/40035597694/artifacts).
The candidate's `SHA256SUMS.txt`, APK byte count and recomputed APK SHA-256 match
its metadata. Android build-tools `35.0.0` independently confirmed the version
with `aapt2` and the certificate with `apksigner`. The source archive's
`commit.txt` and `tree.txt` match the candidate metadata and the
[run API](https://api.github.com/repos/Willzy12h/bedjet-android/actions/runs/40035597694).
ZIP digests identify the downloaded containers; the APK digest identifies the
APK inside them.

This historical green run predates the separate post-R8 JVM gate and cannot
prove that the current changes pass CI. Its test-only signature is not a
production identity, and no APK installation or physical acceptance is claimed.

## Follow-up — 24 October 2026

**Historical record:** this section records the working-source verification
subsequently committed as `1612bdb74f4c6584e0e7c017895454071fecfc12`. Its counts,
hashes and local APK refer to that earlier work. Use the candidate-integration
section above for the current follow-up's status.

That follow-up was on `gpt/nocturne-docs-guard`, branched from the then-latest fetched
`origin/claude/nocturne-final-candidate`, commit
`2d9ebf4f625f0eaccd6bbead1daa17a42b56a3d6`. Claude retains merge ownership.
The earlier release package and its artefact identities are preserved in the
historical report below; they must not be relabelled as builds of this follow-up.

### Required gate recorded for the earlier follow-up

The requested `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
gate passed in **7 minutes 28 seconds**, using `--no-daemon --max-workers=2` and
`-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8`. Counts were read from the raw
JUnit XML and preserved before any later test invocation.

| Suite | Suites | Discovered | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Core | 20 | 156 | 156 | 0 | 0 | 0 |
| App debug | 44 | 375 | 369 | 0 | 0 | 6 |
| **Total** | **64** | **531** | **525** | **0** | **0** | **6** |

Debug assembly passed. Lint passed with **zero errors/fatal issues, 23 warnings
and one hint**. The six skipped methods are the opt-in Robolectric screenshot
captures; no new render result is claimed by this follow-up.

The imported-original regression suite is included in the app total. Before the
guard change it ran **10 tests with two expected failures**, for imported backup
and snapshot originals containing odd Standby/Wait bytes. After the change all
**10 passed**, including refusal with no send for Turbo 255, Heat 200 and invalid
Extended Heat/Cool/Dry temperatures; valid Turbo and device-recorded originals
retain their previous behaviour. Only Standby/Wait are exempt; the predicate does
not use `Mode.usesTemperature`.

### Executable serialisation proof after R8

The existing release fixture harness now runs twice: first through
`:app:testReleaseUnitTest`, then from a separately R8-processed JVM jar. The
second run uses the R8 compiler resolved by the application's Android Gradle
plugin, release app/core compiler output, the same 104 JSON fixtures and the
same snapshot persistence assertions. It runs with JVM bytecode verification
and without the original app/core jars on the runtime classpath.

| Post-R8 JVM check | Observed result |
| --- | --- |
| R8 compiler | `8.10.24`, from the resolved AGP artefact |
| Production serialisable models | **48 / 48 passed** |
| JSON fixture cases | **104 / 104 passed** |
| Shared fixture methods | Both completed, including private snapshot-wrapper persistence |
| Models renamed by R8 | **48 / 48** |
| Executed model classfile hashes changed | **48 / 48**, matched to the shrunk jar |
| Production classes removed | **836** |
| Input / output jar size | **2,355,233 / 201,089 bytes** |
| Output jar SHA-256 | `724637c5cda316eab9f5bc4ca847f1d815766c9ba51f073974eccb560a064804` |
| Negative control | Removing `ChangeKind.MANUAL` from a temporary fixture copy made the same shrunk jar exit with the expected enum-coverage assertion failure |

The test-only shrink roots the harness entry point and discovered companion
`serializer()` factories. Model fields, methods and generated serializers remain
eligible for optimisation; no blanket production keep rule was added. Existing
application rules and resolved kotlinx.serialization consumer rules are applied.
The 202 external runtime dependency jars remain unshrunk. The input export is
an isolated Gradle init script and does not change the production R8 task.

The final gate requires this execution report. It verifies model/fixture/input,
output, compiler, rules, library and log hashes; mapped loaded class names and
bytes; both fixture-method completion; actual model renaming and class removal;
and an R8 version matching the APK's mapping. Original model classes on a runtime
dependency classpath, stale output, missing completion and unchanged pre-R8
model bytes fail the gate.

The permissive moved-member fallback has been **removed**. A mapped class must
actually be defined in the APK's DEX, or its original name must be defined there.
A moved constructor, descriptor, single encode/decode method, or even both moved
directions cannot stand in for a defined serializer class. Future optimisation
that removes a required class therefore fails closed for investigation.

**Proof boundary:** the new runtime result executes separate R8 classfile output,
with test entry points and unshrunk libraries. The actual APK still has
**structural proof only**: its DEX is inspected but not executed. The JVM shrink
does not reproduce APK reachability, whole-program dependency shrinking or
DEX-specific optimisation. `shrunkJvmRuntimeExecuted: true` and
`apkRuntimeExecuted: false` are deliberate. No Android runtime restoration,
widget behaviour or BedJet 3 hardware result is inferred.

The fresh `:app:assembleRelease` validation build passed in **1 minute 16 seconds**
with R8 and resource shrinking. The combined runtime/structural gate passed for
all **48 models and 104 cases**. Its actual APK has one DEX with **4,436 defined
classes**, R8 `8.10.24` and mapping ID `c9b07db`; all model and serializer checks
use direct `mapped-class` evidence. The merged-manifest gate also passed for
**39 components**.

This local validation APK is **unsigned**: no signing key was configured, the
signature-structure check found none, and `apksigner` rejected it for the missing
signature manifest. Its default version is `0.1.0` / code `1`, size
**4,242,531 bytes**, and SHA-256 is
`55a3d7f7599c18be9c3e7d3f359b26fe571213a7f8ca4ccb67fbb2466c1921da`.
It was built from the follow-up working source before the documentation commit;
its identity is recorded as a local validation artefact. The earlier test-signed
APK below retains its own original source and certificate identity. No new
test-signed or production-signed distribution package is claimed here.

Reproduce the additional runtime gate after the required build checks:

```bash
./gradlew --no-daemon --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' \
  -I .github/workflows/release-serialization.init.gradle \
  :app:testReleaseUnitTest \
  --tests io.github.willzy12h.nocturne.releasegate.ReleaseSerializationTest \
  :app:exportReleaseSerializationInputs
python3 -B .github/workflows/release_serialization_shrunk.py \
  --output /tmp/nocturne-release-evidence/serialization-shrunk
```

Pass that directory's `shrunk-runtime.json` to the APK gate using
`--shrunk-report`, alongside the exact APK, its R8 mapping/usage and release
JUnit XML. Regenerate the runtime report if its JUnit or input export changes;
both are hash-bound. CI uploads the runtime jar, mapping, rules, logs and report
with the rest of its gate evidence.

### Production signing and workflow changes

The production signing job now declares `environment: production`. The expected
certificate variable is read by its consuming steps after the environment is
available. The four keystore secrets and `NOCTURNE_RELEASE_CERT_SHA256` must be
scoped exclusively to that environment. The explicit increasing
`NOCTURNE_RELEASE_VERSION_CODE` stays a repository variable because the preceding
build job needs it.

[Production release settings — owner action](release-production-settings.md)
lists the exact repository settings: an independent required reviewer, prevention
of self-review, no administrator bypass, a **Tag `v*` only** deployment rule, and
two active tag rulesets separating restricted creation from immutable updates
and deletion. It also specifies removal of repository/organisation fallbacks for
the environment credentials and fingerprint.

**Those settings are NOT APPLIED by this work and their existing state is
UNVERIFIED.** The workflow declaration does not configure reviewers, secret
scopes or tag protection. The owner must complete and verify that document's
settings before allowing production signing. No GitHub settings, secrets,
production tag or release were changed here.

All **18 action invocations** are pinned to **8 immutable upstream commit SHAs**,
including `softprops/action-gh-release` and `android-actions/setup-android`.
Pillow is locked to `12.3.0` with official wheel hashes; installation uses
`--require-hashes --only-binary=:all:`. The hashes were checked against PyPI and
validated by an installation on Python 3.12 and a wheel download for Python 3.11.
The temporary `gpt/nocturne-release` push trigger is removed. Current triggers are
pushes to `main`, `v*` tag pushes and pull requests.

The local helper suites pass **21 signing/artefact tests, 8 screenshot-helper
tests and 34 serialisation-gate tests**. Actionlint `1.7.12` accepts the current
workflow. Release JUnit passes **2 tests, zero failures, errors or skips**; these
are the pre-R8 invocation and are not counted again as post-R8 JUnit tests.

This branch push does not itself trigger Build. No new Actions run or production
execution is claimed for this follow-up. A pull request or the eventual main/tag
integration runs the strengthened workflow and uploads its own exact-source
evidence and test-only APK. The old release branch's green run cannot establish
CI success for the new branch.

The small saved-original safety correction, full current gate counts and
source-reconciled UI/documentation limits are recorded in
[Nocturne completion](nocturne-completion.md) and the
[review register](register.md). All newly added physical acceptance cases in the
[Samsung / BedJet 3 checklist](samsung-bedjet-acceptance.md) remain **NOT RUN**.

## Historical release package — 23 October 2026

The following report describes the earlier `gpt/nocturne-release` implementation
and its local test-signed APK. Its source, counts, screenshot results, signing
probes and CI handover instructions are historical. The 24 October section above
supersedes its workflow triggers, production configuration and serialisation
proof description; it does not claim that the older artefact ran the new gate.

### Result and source identity

The local implementation build passed the required core/app/debug/lint gate and
completed both unsigned and explicitly test-signed release builds. All 19 invalid
signing configurations were rejected. The candidate is a **test-only BedJet 3
APK**, with code minification and resource shrinking enabled. Release JVM
serialisation, the actual APK DEX structural gate and all six screenshot tests
also passed. The screenshot fixture/coverage gate verified 38 renders, 28
historical comparisons and ten explicitly unbaselined views. The final CI gate
runs after this report is committed and pushed; its observed result and exact
commit are supplied in the delivery message and Actions evidence.

| Source item | Value |
| --- | --- |
| Work branch | `gpt/nocturne-release` |
| Review baseline | `4fe524375fa2652ff3b97f3c1e942b35bf280155`, fetched from `origin/claude/nocturne-final-candidate` |
| Locally verified implementation commit | `b0e4e1b093c738ae5dde9d7106250c0c39ef8bc7` |
| Implementation tree | `618acf43f336be1913acb2de3f4bbecf90b5b38a` |
| Final report commit / CI | Match the branch head to the [Build run](https://github.com/Willzy12h/bedjet-android/actions/workflows/build.yml?query=branch%3Agpt%2Fnocturne-release) and that run’s `release-metadata.json`; the delivery message records the verified SHA and run. |

The APK identified in this report belongs to the implementation commit above.
Adding this report and the final evidence documentation changes the source commit.
The later CI run therefore produces a separate artefact, with its own version,
throwaway signer, APK hash and source identity. The local APK must retain its
original implementation SHA.

A report cannot embed its own final Git commit hash without changing that hash.
The authoritative identity of the final CI candidate is its per-run
`release-metadata.json`, together with the matching Actions run and source
archive. That manifest records `source_commit`, `source_tree`, version, APK size
and SHA-256, signer SHA-256 and `signing_identity`. The final handover must check
those values against the exact pushed branch head; an earlier green run is not
sufficient.

### Authorised scope

The implementation changes are confined to `.github/workflows/**`,
`app/build.gradle.kts`, the test-only `ScreenshotCapture.kt` fixture and
`docs/review/release-*.md`. No production UI, resource values, night, data or core
source was edited. The release manifest hardening is generated by the permitted
Gradle file. No blanket ProGuard keep rule was added, and minification/resource
shrinking remain enabled.

The work stays on `gpt/nocturne-release`; Claude retains merge ownership. No
production release or version tag has been created by this work. No APK has been
installed on a physical device, and no Samsung, Health Connect provider or BedJet 3
hardware acceptance result is claimed.

### Required local gate

The required tasks completed successfully against the implementation source tree
subsequently committed as `b0e4e1b093c738ae5dde9d7106250c0c39ef8bc7`.
The exact local invocation was:

```bash
./gradlew --no-daemon --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' \
  :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The `required-gate-summary.json` records that committed source identity and was
captured before the opt-in screenshot run could replace the app test-result XML.

| Suite | Test suites | Tests discovered | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Core | 20 | 156 | 156 | 0 | 0 | 0 |
| App debug | 44 | 368 | 362 | 0 | 0 | 6 |
| **Total** | **64** | **524** | **518** | **0** | **0** | **6** |

The six skips are the deliberately opt-in screenshot test methods. They do not
count as executed screenshots. Debug assembly passed. Lint reported **23 warnings
and one hint**, with no error or fatal issue. This is a passing lint gate with
recorded findings.

Local evidence: `required-gate-summary.json`, the full required-gate log, core/app
JUnit XML and HTML reports, and `lint-results-debug.*`. CI preserves the same
reports before screenshot capture and uploads the recorded counts alongside them.
The 48 host-helper regression tests also passed: 21 signing/artefact checks,
19 serialisation-gate checks and eight screenshot-evidence checks.
`actionlint` 1.7.12 and `git diff --check` passed.

The local debug build used the default version; the release builds explicitly
used `0.2.0-release-test.20261023` / code `2`. The local runtime was JDK 17.0.21,
Gradle 8.14.3, AGP 8.10.1, Android SDK 36 and build-tools 35.0.0.
The initial environment lacked a Java compiler; installing the complete JDK
resolved that setup failure before the successful required gate.

### Release signing and candidate metadata

`release_signing.py` completed with `status: passed`. It built an unsigned release
with all signing values absent, then generated a temporary PKCS12 outside Git and
performed a second `:app:assembleRelease` with every `NOCTURNE_*` signing value
provided. The private key was removed afterwards; it is excluded from the
repository, candidate bundle and uploaded evidence.

| Check | Observed result |
| --- | --- |
| No signing configuration | Unsigned APK produced; `apksigner` verification rejected it; no JAR signature entries or APK Signing Block were present. |
| Partial signing configuration | All 14 nonempty partial combinations failed with the explicit signing-configuration error. |
| Invalid complete configuration | Missing key file, directory used as key file, wrong store password, missing alias and wrong key password all failed: five cases. |
| Explicit test signing | Gradle built a signed release; `apksigner` verified the generated test certificate. |
| Debug-key separation | The test signer differs from the verified debug APK signer. |
| Build consistency | Unsigned and signed APKs contain identical program and resource bytes after excluding signature entries. |
| Alignment | The final APK passed `zipalign` verification. |

The negative probes require both a nonzero Gradle result and the
`Nocturne release signing:` diagnostic. An unrelated build failure cannot satisfy
a signing rejection test. Stale signed/unsigned output files are removed before
the corresponding build checks.

#### Verified local implementation artefact

| Field | Value |
| --- | --- |
| APK | `nocturne-0.2.0-release-test.20261023-TEST-ONLY.apk` |
| Package | `io.github.willzy12h.nocturne` |
| Version name / code | `0.2.0-release-test.20261023` / `2` |
| Size | **4,254,879 bytes** |
| APK SHA-256 | `ad1da78b3c62b6f3572f3cf2511d0b351a19e12eb8cb4716b340f365f4cca420` |
| Signer SHA-256 | `df15ef269d3d728c24cf6cf7c3fd32506daf608fa7a33985193e4737f26a9c01` |
| Signing identity | `test-only` |
| Source commit | `b0e4e1b093c738ae5dde9d7106250c0c39ef8bc7` |
| Source tree | `618acf43f336be1913acb2de3f4bbecf90b5b38a` |
| Minimum / target SDK | `31` / `35` |
| Debug signer SHA-256 | `b57c29bdfaa7cee2ce3154cdcddf6d2a68d05706ca59a93e2f9ca29e4001517f` |
| Preserved unsigned APK SHA-256 | `4504c9d09de036b6e9d5034c60f2b23d2a4b9272ad3e10f69d3b243d93350cee` |

These values come from `release-package/candidate/release-metadata.json` and
`release-package/evidence/signing-checks.json`. The bundle also retains signature,
manifest/badging, checksum and R8 mapping/configuration/usage evidence. The APK
size is an observed value; no controlled before/after shrinking percentage is
claimed.

This throwaway identity is never a production identity. Another CI run uses a new
key. The APK cannot update an installation signed with a different certificate;
any installation or data migration remains a separate user-supervised decision.

### Serialisation and actual minified APK boundary

The compiled production serialisation inventory contains **48 models**, with
**104 JSON fixture cases**. The test-only release harness covers private/nested
models, serialised fields and every enum value. It is added only to the
release test source set and cannot become a production R8 root.

The evidence has two separate parts:

1. `:app:testReleaseUnitTest` executes JSON encode/decode behaviour using release
   variant JVM classfiles **before R8**. Its output binds the executed model
   classfiles and fixtures by SHA-256. It does not execute the minified APK.
2. `release_serialization.py` inspects every `classes*.dex` in the actual signed
   APK, matches its R8 marker to the supplied mapping ID, and checks defined
   classes or mapped merged/inlined implementation owners for each model and its
   serializer. A name appearing only in mapping, usage or a referenced DEX
   descriptor is insufficient. The report binds the APK, mapping, usage, fixtures
   and JUnit evidence by hash.

**Release JUnit: PASS.** Two JUnit tests completed with no failures, errors or
skips, covering all 48 models and 104 JSON fixture cases. The test XML records
1.057 seconds of test time; the Gradle invocation completed successfully in
1 minute 5 seconds.

**Actual APK structural gate: PASS.** The report is bound to candidate SHA-256
`ad1da78b3c62b6f3572f3cf2511d0b351a19e12eb8cb4716b340f365f4cca420`.
It inspected the single packaged DEX, containing 4,436 defined classes, and matched
R8 mapping ID `3b550dc`. Every model and its exercised serializer has direct
`mapped-class` evidence; no merged/inlined fallback was needed for this build.
The complete per-model evidence is retained in `release-serialization.json`.

The structural gate supplements the JVM behaviour tests. Its
`apkRuntimeExecuted: false` field is deliberate. No Android-runtime minified
serialisation round trip, restored widget execution or physical-device behaviour
is inferred from these host checks.

### Release manifest audit

The [complete merged-manifest audit](release-manifest-audit.md) passed with
**39 components: 17 application and 22 dependency components**, including
**12 exported components and two widget receivers**. It inventories activities,
aliases, services, receivers, provider details, effective permissions and enabled
states. All 15 requested permissions and the single declared signature permission
are recorded.

A generated release-only overlay makes these five receivers private:
`PlatformClockReceiver`, `BootReceiver`, `AlarmChangedReceiver`,
`QuickControlsReceiver` and `SingleActionReceiver`. Their original intent filters
and widget metadata remain intact. The audit discusses explicit intent handling
and the concrete boundaries of the remaining public entries, including the
legacy Health SDK service's Binder caller checks.

The built merged manifest SHA-256 is
`4f985ff884569e5b3f5cdf6478c583be0e9937ffa2bfc943259fc2e6462e3701`.
This is static manifest evidence. Widget-host delivery, system job/tile binding,
Health Connect provider interoperability and changed-receiver behaviour still
need Android/device acceptance.

### Screenshot comparison

The [separate screenshot report](release-screenshots.md) defines the actual
capture and visual-review evidence. `SCREENSHOT_TAG=after` pins `Graph.clock`
before activity creation to `2026-10-22T22:30:00Z`, in `Europe/London`.

The expected set is 38 renders: 28 Dark/Light/Simple views with historical
counterparts, seven combined 320 dp/200% font-scale views, and three feature
views. The latter ten are explicitly **UNBASELINED**. Baseline files are preserved;
a missing historical baseline fails the coverage check. Per-image metadata checks
the clock, theme, mode, viewport, font scale, density and image dimensions.

**Capture and fixture/coverage result: PASS.** Six JUnit screenshot methods
passed with no failures, errors or skips, producing 38/38 PNGs and sidecars.
The comparison covered all 28 historical pairs, recorded ten unbaselined views
and found no missing baseline or after image. Every historical pair differs;
changed pixels range from 3.1926% to 74.5334%, including state, clock and
resampling differences. Visual findings are recorded below after inspection.

The local visual inspection found these concrete points:

- Dark, Light, Simple Dark and Simple Light retain their corresponding colour
  treatments. The largest historical differences are the lower Tonight and
  running-night views, where clock, displayed state and visible controls differ.
- At 320 dp with 200% font, the inspected Start/End actions, wake override,
  chart times, profile actions and theme controls wrap within the available
  width. Navigation uses icons. Long setup explanations and later settings
  extend below the viewport and require scrolling; that is not evidence of
  horizontal text clipping.
- The floating **New profile** control overlaps part of the next partially
  visible profile card. The same pattern appears in the historical 411 dp
  baseline. It is recorded for Claude, with no redesign in this branch.
- One local Simple Dark running capture retains a transient snackbar. The
  feature image named `features-01-idle-handover.png` captures the underlying
  Tonight Activity, without the separate popup menu. It therefore provides no
  visual evidence of that menu's contents. These capture limits are explicit
  in the screenshot report.

The report records visible evidence and comparison limitations. It does not
convert successful screenshot generation into general visual acceptance.

Historical baselines use a different unpinned clock and predate earlier
application changes. Difference images are review evidence, not an automatic
regression pass. The captures use Robolectric and the demo BedJet; they are not
physical Samsung screenshots or evidence of BedJet 3 communication.

### CI and production tag path

| Job | Trigger and responsibility | Evidence / output |
| --- | --- | --- |
| `build` | Push to `main` or `gpt/nocturne-release`, version-tag push, or PR; explicitly checks out the pushed SHA or PR head. Runs the required gate, signing builds/probes, serialisation, manifest and screenshots without production secrets. | Source archive; raw gate reports/counts; signing and structural evidence; screenshot reports/differences; clearly labelled `TEST-ONLY` APK and metadata. |
| `production` | Version-tag push only, after the entire build job succeeds. Downloads the verified unsigned build, verifies its recorded source/hash/version, inspects published release history, then signs it outside Gradle. | Certificate-verified production APK and metadata; history and gate evidence. |
| `release` | Version-tag push only, after production verification succeeds. This is the only job with repository write permission. | Production APK, `release-metadata.json` and `SHA256SUMS.txt`. |

Required production configuration is explicit:

| Repository setting | Kind | Purpose |
| --- | --- | --- |
| `NOCTURNE_RELEASE_VERSION_CODE` | Variable | Positive explicit release version code, strictly greater than all published version evidence. |
| `NOCTURNE_RELEASE_CERT_SHA256` | Variable | Expected production certificate fingerprint; validated and compared with the actual signed APK. |
| `NOCTURNE_KEYSTORE_BASE64` | Secret | Encoded production keystore, decoded only into temporary storage in the tag signing job. |
| `NOCTURNE_KEYSTORE_PASSWORD` | Secret | Production keystore password. |
| `NOCTURNE_KEY_ALIAS` | Secret | Exact production private-key alias. |
| `NOCTURNE_KEY_PASSWORD` | Secret | Production private-key password. |

Version checking paginates every published release and its assets, includes
prereleases, inspects every APK's actual version code, and validates canonical
`release-metadata.json` assets. Missing, unreadable, incomplete or inconsistent
history fails. A history containing no published releases permits the first
release; an existing release without APK/metadata version evidence does not.
Equal or lower codes fail, including a same-tag rerun after publication. All
version tags share one workflow concurrency group, with cancellation disabled,
so their check/sign/publish sequences cannot race each other.

Missing, partial or invalid production signing inputs and a mismatched
certificate prevent publication. Production secrets are absent from PR and test
jobs, and the publication job downloads only the production package. Keys are
excluded from every upload path. Gate logs and outcomes are uploaded even when
checks fail; a successfully signed test candidate can remain available for
inspection after a later gate fails, so its presence alone is not a full CI pass.

**CI status at report creation:** this file is written before the final push,
so it does not claim a run for its own future commit. The branch is handed over
only after the matching run succeeds. Use the [branch Build history](https://github.com/Willzy12h/bedjet-android/actions/workflows/build.yml?query=branch%3Agpt%2Fnocturne-release),
verify its `head_sha` against the delivered branch SHA, and use that run’s
`release-metadata.json` for the final CI APK. Production/tag jobs are expected
to be skipped on this branch; the `build` job must succeed. The delivery message
provides the observed run URL, conclusion and final source SHA.

### Acceptance limits and handover

The local evidence establishes the recorded host build/test/signing outcomes,
release JVM behaviour, actual APK structural checks and static manifest result
for the implementation source tree. Screenshot capture and comparison evidence
are recorded separately from visual acceptance. The final CI run repeats these
gates against the exact pushed commit and records its own artefact identity.
The test APK remains a candidate for BedJet 3 acceptance, not a production
release or a completed physical-device acceptance result.

Preserve the full candidate metadata and reports when handing the branch to
Claude. The final handover should identify the exact branch head, matching CI run,
CI candidate artefact and any remaining warnings or visual findings. Do not
relabel the local implementation APK as an artefact of the later report commit.
