# Task 7 release preflight — 22 October 2026

**Historical preflight:** this records the 22 October checkout and tool availability. The [candidate-integration report](release-candidate-report.md#candidate-integration--24-october-2026) records the current validation state. The retained gate executes shared fixtures on separate R8 classfile output, rejects required classes listed as removed by R8 and has no moved-member fallback. The APK DEX check remains structural proof only; the old signing/workflow observations below are not a description of the current branch.

Read-only preparation, not implementation or task review. No builds, installations, keys, emulator/device sessions or repository edits performed. Inspected repository `/workspace/scratch/ad4eb857ceea/nocturne-hardening`; requested code base a5d01c1, observed HEAD 46be6becf2e704f54047cc65463f6bf1575aa1e9 (documentation-only continuation commit). Task 4–6 changes must be incorporated before release verification. All paths below are absolute or relative to that repository as stated.

## Decision

Use the Task 7 plan's explicit fallback: inspect the **actual minified APK's defined DEX classes**, using its own R8 mapping, alongside packaged manifest/resources and signature verification. Label this structural verification, not a successful runtime round trip. This is the smallest meaningful available-host check; it is weaker than executing serialization and does not establish minified restoration/widget operation on Android.

No Android runtime was found in the inspected SDK or PATH: SDK has no `emulator/` or `system-images/`; `dalvikvm`, `art`, `emulator` are absent from PATH; `/dev/kvm` does not exist. `adb` exists but was not started or used to probe devices. These observations do not claim that no remote device can ever be provided.

A standard `app/src/test` test uses JVM classfiles, not the APK's DEX. The regular debug/release unit-test task is therefore not proof that R8 output serializes. Local R8 supports `--classfile`, but a separately shrunk JVM JAR is a separate optimisation output, with different roots/backend; it is useful supplementary regression evidence only. R8's help accepts class/zip/jar inputs, and its classfile output flag is not a DEX-to-JVM execution bridge. Do not call a separate R8/JVM experiment “against the actual minified APK”. Installing a DEX conversion pipeline merely for this gate would add another transformation and would still not execute Android's original artifact.

## Exact available tools

SDK root: `/workspace/scratch/ad4eb857ceea/android-sdk`.

- `build-tools/34.0.0/apksigner`: executable; `version` returned `0.9`.
- `build-tools/34.0.0/aapt2`: executable; `version` returned `Android Asset Packaging Tool (aapt) 2.19-10229193`.
- `build-tools/34.0.0/aapt`, `build-tools/34.0.0/dexdump`, `build-tools/34.0.0/d8`, `platform-tools/adb` exist.
- `cmdline-tools/latest/bin/apkanalyzer`: exists but mode 0644; direct invocation fails with permission denied. Invoke with `bash /workspace/scratch/ad4eb857ceea/android-sdk/cmdline-tools/latest/bin/apkanalyzer ...`; no chmod/install needed. Its `dex packages --help` prints available options then exits with “help is not a recognized option”; option discovery confirms `--defined-only`, `--proguard-mappings`, `--proguard-folder`, and `--show-removed`.
- `cmdline-tools/latest/lib/r8.jar`: direct JDK invocation of `com.android.tools.r8.R8 --help` succeeds and exposes `--classfile` and `--dex`. This SDK tool is not necessarily the same R8 version selected by the final AGP.
- Full JDK: `/workspace/scratch/ad4eb857ceea/nocturne-jdk17/jdk-17.0.20.1+1`; use its `bin/java`, `bin/keytool` if needed at implementation time. Gradle cache: `/workspace/scratch/ad4eb857ceea/gradle-cache`.
- Installed SDK platforms 35 and 36; only Build Tools 34.0.0 observed. Task 6's AGP upgrade may request another Build Tools version; do not assume offline release build availability from the existing debug gate.

## Concrete artifact checks for the implementer

After the final integrated source produces a test-key-signed, minified release APK, bind every report to that exact APK SHA-256 and source commit. Save R8 `app/build/outputs/mapping/release/mapping.txt`, generated effective configuration and usage report alongside verification evidence where produced. Do not let a later unsigned build overwrite the candidate without copying/hash-binding it first.

Suggested tool invocations (set APK to the actual copied candidate, not a stale glob):

```bash
SDK=/workspace/scratch/ad4eb857ceea/android-sdk
bash "$SDK/cmdline-tools/latest/bin/apkanalyzer" manifest print "$APK"
bash "$SDK/cmdline-tools/latest/bin/apkanalyzer" dex packages --defined-only --proguard-mappings app/build/outputs/mapping/release/mapping.txt "$APK"
"$SDK/build-tools/34.0.0/aapt2" dump badging "$APK"
"$SDK/build-tools/34.0.0/aapt2" dump xmltree "$APK" --file AndroidManifest.xml
"$SDK/build-tools/34.0.0/apksigner" verify --verbose --print-certs "$APK"
sha256sum "$APK"
stat -c '%s' "$APK"
```

Check defined classes, not merely referenced descriptors or strings. Scan every `classes*.dex`. Match original serializers to actual obfuscated definitions using the same artifact's mapping, including generated `$$serializer` classes for `BackupBundle`, `Settings`, nested profile/program/history/settings objects, and separately persisted `ActiveNight`/pending/applied models and `LoggedNotice`. A literal grep for original `$$serializer` names can falsely fail under obfuscation. Mapping alone or `--show-removed` output can falsely claim a removed class survives. If optimisation merges/inlines a serializer, investigate its mapped implementation; do not immediately add blanket keep rules merely to satisfy a name grep.

Inventory the **post-Task6 merged release manifest**, not just today's source manifest. Verify each application/activity/service/receiver/provider resolves to a defined packaged class; inspect component permissions/exported values and referenced widget XML/shortcut resources. Glance widget implementation classes are not all manifest components; include their actual reachability/reflection requirements in review. New Health Connect, tile, job/work and reminder components must be included after integration. Manifest retention is structural evidence, not proof the components launch or restore.

Maintain ordinary JVM JSON round-trip/backwards-schema tests as behaviour evidence, clearly separated from the DEX check. The plan permits this fallback when actual minified-class JVM execution is impractical. If execution is later required, use an Android runtime/instrumentation against the signed minified target, with tests kept separate from the target's production roots; no such runtime is presently provisioned.

## Serialization evidence and keep-rule implications

Sources: `core/build.gradle.kts`, `gradle/libs.versions.toml`, `core/src/main/kotlin/io/github/willzy12h/nocturne/core/store/{Backup,Settings,SnapshotStore}.kt`, `core/src/main/kotlin/io/github/willzy12h/nocturne/core/night/ActiveNight.kt`, `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt`, and `core/src/test/kotlin/io/github/willzy12h/nocturne/core/store/BackupTest.kt`.

Both modules apply Kotlin serialization; library version is 1.7.3. BackupCodec uses explicit `BackupBundle.serializer()` and JSON with ignoreUnknownKeys/encodeDefaults/prettyPrint. Repository explicitly requests Settings, ActiveNight, Profile, SlotBackup, NightRecord and LoggedNotice serializers. SnapshotStore has a private serializable SnapshotFile wrapper. Existing BackupTest covers an unminified round trip, v1 defaults and snapshot round trips. These are concrete reachable paths, so begin with dependency-supplied rules and targeted gaps, not `-keep class io.github...** { *; }`.

Inspected dependency artifact:
`/workspace/scratch/ad4eb857ceea/gradle-cache/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.7.3/1f226780b845ff9206474c05159245d861556249/kotlinx-serialization-core-jvm-1.7.3.jar`.

It already supplies `META-INF/com.android.tools/r8/kotlinx-serialization-common.pro` and `kotlinx-serialization-r8.pro` (plus equivalent generic ProGuard rules). Rules cover Companion fields/serializer methods, serializable object serializer access, runtime annotations, and descriptor optimisation protection; R8 rules permit shrinking/optimising/obfuscating serializable classes while preserving annotation handling. Inspect final effective configuration to confirm consumption; copying blanket serializer rules before examining this is unnecessary. No `app/proguard-rules.pro` existed at inspection.

## Signing and workflow pitfalls

Sources: `app/build.gradle.kts`, `.github/workflows/build.yml`, `README.md` Updates section, `.gitignore`; requirements: Task 7 in `docs/superpowers/plans/2026-10-22-nocturne-hardening.md`, R39/R40/V04 in `docs/review/register.md`, `docs/review/sdd-ledger.md` and `docs/HANDOVER.md`.

1. Current Gradle logic treats a nonexistent configured key as absence (`takeIf { it.exists() }`) and falls back to `signingConfigs.getByName("debug")`. Current release is not minified, and resource shrinking is unset. Distinguish no signing configuration (permitted unsigned release with clear notice) from partial/invalid configured credentials (clear error); never silently produce a debug-signed release.
2. Current CI warns on missing base64 secret, builds debug-signed release, then tag-triggered publish job uploads it. Tag release path must fail before publication if any production signing secret is missing/invalid. Presence of just the base64 key is insufficient. Validate signature/certificate before release artifact publication; mere file existence is insufficient.
3. Current APK copy hardcodes `app-release.apk`. Unsigned output convention is `app-release-unsigned.apk`; handle the unsigned CI/PR build deliberately without accidentally publishing it. Do not use an ambiguous wildcard that can select stale signed/unsigned candidates.
4. PR build step currently receives signing secrets whenever GitHub makes them available. Production signing should be restricted to intended trusted release context; PR verification does not need the production private key. Fork PRs will naturally lack secrets and should still be able to build unsigned. Release publishing job is correctly tag-gated and separately gets contents:write; retain this separation.
5. Test key must be explicit and separate, generated only at implementation time in temp storage, never committed/published or substituted by a production fallback. Record certificate SHA-256 and APK SHA-256. `.gitignore` currently has no keystore pattern; temp-outside-repository storage is especially important.
6. README currently tells users a tag creates a signed release without describing missing-secret failure/unsigned local output. Update to match behaviour. A test-signed build cannot upgrade an existing production/debug-signed install with another certificate; do not claim otherwise or remove an installed app to make it work.
7. Verify both paths deliberately: no-key unsigned artifact (apksigner expected failure due to no signer), and explicit test-key signed minified artifact (apksigner succeeds and matches test certificate). Wrong/missing password or alias must not trigger fallback. These are future Task7 checks, not completed preflight checks.

## Version and size metadata

Current `app/build.gradle.kts`: applicationId/namespace `io.github.willzy12h.nocturne`, default versionName `0.1.0`, versionCode `1`; overrides `-PversionName` / `-PversionCode`. Workflow tag `v0.2.0` yields name `0.2.0`; non-tag name `0.0.0-dev.${GITHUB_RUN_NUMBER}`; code is `${GITHUB_RUN_NUMBER}` in both cases. A workflow run number is not proof that a candidate's code exceeds every previously installed build; record/check the actual prior candidate before claiming upgrade acceptance. No final release version was selected in this preflight.

Expected after authorised Task6: compileSdk36/AGP8.10.1/Health Connect1.1.0, retaining targetSdk35/minSdk31/Kotlin2.0.21/Gradle8.14.3. Verify package/version/min/target values from the final APK, not only Gradle text. Include source SHA, versionName/code, artifact bytes, artifact SHA-256, signer SHA-256 and test-signing label in hand-off and `docs/review/samsung-bedjet-acceptance.md` candidate metadata. Physical results remain NOT RUN.

Handover baseline debug APK is 60,654,288 bytes; this is a historical debug comparison, not a controlled shrinking-only measurement. For a meaningful “before/after shrinking” claim, compare unshrunk and shrunk release builds from the same final integrated source/dependencies, or clearly disclose that debug size comparison also changes variant/source/dependencies. No new APK was built or measured here.
