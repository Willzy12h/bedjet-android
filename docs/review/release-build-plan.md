# Nocturne release/build package implementation plan

**Historical plan:** this records the 23 October release implementation and its
original branch scope. Current follow-up status and separately identified CI
artefacts are recorded in the [candidate-integration report](release-candidate-report.md#candidate-integration--24-october-2026).

**Goal:** deliver a verified, test-signed, shrunk BedJet 3 APK and a scoped branch for Claude to merge.

**Baseline:** `4fe524375fa2652ff3b97f3c1e942b35bf280155`, fetched from `origin/claude/nocturne-final-candidate` on 23 October 2026. Work branch: `gpt/nocturne-release`.

**Architecture:** retain the candidate's Android/Gradle toolchain and production implementation. Add reproducible release checks under `.github/workflows/`, narrow build configuration, test fixtures and evidence. Verify release JVM JSON behaviour separately from the actual R8 APK's mapped DEX definitions; neither is described as executing the APK on Android.

**Specification:** The six-point release/build request in this session. It authorises implementation, testing and pushing this branch, with Claude retaining merge ownership. No additional design approval is required for routine implementation choices.

## Constraints and decisions

- Edit only `.github/workflows/**`, `app/build.gradle.kts`, ProGuard rules, the test-only screenshot fixture and `docs/review/release-*.md`.
- Do not edit production UI, resource values, night, data or core code; do not push to Claude's branch.
- Keep minification and resource shrinking enabled. Add no blanket keep rules.
- Generate a narrow release manifest overlay from the allowed Gradle file where a component needs hardening.
- Keep test keys outside the repository and evidence bundles. Production signing belongs only to a separate tag path, after fingerprint and monotonic version checks.
- Use UK English; support statements concern BedJet 3 only. Physical hardware acceptance remains untested.
- The exact pushed commit must have successful CI; a previous commit's green result is insufficient.

## Work and verification

- **1. Build/signing:** validate absent, partial, invalid and complete signing configuration; build unsigned and explicitly test-signed shrunk releases; compare their signer identities with the debug certificate. Verify APK metadata with Android tools.
- **2. Serialisation:** inventory serialisable persistence models; round-trip every covered model using release-variant classes; inspect the actual APK's defined DEX classes against its R8 mapping/usage and fail on missing coverage.
- **3. Manifest:** inspect the merged release manifest including library components; restrict unnecessary exported receivers through a generated overlay; document each component and permission.
- **4. CI:** run the required debug/core/lint gates, release checks and signing build test; upload evidence and the explicitly test-signed APK; isolate production tag signing and require increasing version codes plus the expected certificate.
- **5. Screenshots:** render with `SCREENSHOT_TAG=after` and fixed `Graph.clock`; compare all existing baseline images and record new, missing or changed images, including narrow/large-font coverage. No redesign.
- **6. Handover:** review the whole scoped diff; record exact test counts, APK/version/hash/certificate/source metadata and limitations in `release-candidate-report.md`; commit, push only `gpt/nocturne-release`, verify CI on its exact head and provide that SHA.

## Review focus

- A release unit test must not be represented as runtime execution of R8 DEX.
- A model or serializer name in mapping/usage alone does not prove a packaged DEX definition.
- Explicit broadcasts are not blocked merely by an intent filter containing protected system actions.
- Missing or wrong signing values must fail without producing an artefact under a different identity.
- Missing baseline screenshots and not-run checks must remain visible in the report.
- A report commit changes the source SHA; final CI must run after that commit.

## Progress

Implementation, independent review and all local build/test/signing/manifest/screenshot checks are complete. The scoped release reports record the exact results and visual limitations. Final verification is the Build run on the pushed report commit; its source SHA, conclusion and artefact metadata are recorded in the delivery message. The initial toolchain setup issue was resolved before the successful required gate.
