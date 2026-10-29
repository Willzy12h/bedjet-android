# Independent-fixes TEST delivery

Date: 29 October 2026.

The user has authorised completing remote delivery and providing the permanent TEST APK. This supersedes the earlier approval block recorded in the independent-fixes handover. A newly authorised Git CLI dry-run push actually exited 128 with `fatal: unable to get password from user`; the connected GitHub app remains available for the repository.

## Additional CI contract correction

The complete Build workflow's screenshot checker still required 102 legacy filenames, while the unchanged 14 ScreenshotCapture methods produce 212. A signed artifact uploaded before that later gate failed would not be an accepted final build.

The checker now requires the explicit current 170 primary scenes and 42 secondary windows, retaining the original 28 historical baselines and rejection of missing or unexpected files. Secondary windows use their owning primary scene's metadata. Exact Activity/sheet dimensions and bounded native popup/picker dimensions remain checked. The current Scheduled Appearance, real setup and default Simple-view feature states are explicit. Full PNG verification rejects the reproduced missing-IEND file.

Test-first evidence records the initial failed contract run, a passing correction, then a native compatibility replay that exposed three stale Simple-view expectations. A second focused RED precedes the final correction. The final helper run exits 0 with 27 methods passed, zero failures/errors/skips. A labelled replay over the 212 accepted native PNGs also exits 0: 28 historical comparisons, 184 unbaselined captures, zero errors. The replay preserves PNG bytes and only adapts copied sidecar tags; it is not fresh rendering or remote CI evidence.

That correction changes only the screenshot checker and its Python regression tests. Subsequent delivery changes update CI action pins, SDK provisioning and the lint acceptance helper. Application/core production, Android tests and capture methods remain unchanged from the independently verified source `3fd7f043308f10a8524ab29e75c96f5cd2eea292`.

## Completed hosted Build 50: execution passes, delivery is not accepted

The exact original history, including `146501a69814f28d3879ca835bb93d2442b455a1`, is now available remotely. The candidate at `a888b7b991c22b8ada349ea84d98a1e412efb0a2` completed [Build 50](https://github.com/Willzy12h/bedjet-android/actions/runs/40942525383) successfully on 29 October 2026. The downloaded source archive was checked against all 1,364 Git blobs and modes in that exact tree.

Independent recount of the archived required-gate XML finds core **332 passed**, and app **1,829 passed plus the 41 documented opt-in skips**; no test failures or errors. The required Gradle command completed successfully. The raw lint XML, however, contains **one `OldTargetApi` warning**, at `app/build.gradle.kts:72` for target SDK 36. The prior workflow recorded the finding but did not reject delivery. The corrected helper preserves its summary, then exits nonzero for any lint finding or a missing report. Its three new regression cases first reproduced the gap; the complete corrected helper suite passes 26 methods.

Build 50's permanent signing step explicitly logged: `Permanent Nocturne TEST signing skipped: four dedicated TEST secrets are required.` Its permanent APK upload was skipped. The successful workflow conclusion therefore does **not** establish an installable APK with the required signing identity. The separately generated throwaway `TEST-ONLY` artifact is evidence, not a compatible installation deliverable.

The job also records two SDK provisioning deprecation warnings from `sdkmanager`; they are retained separately from the zero compiler/Gradle build diagnostics. Neither those messages nor the lint finding is removed from the evidence or relabelled as a clean run.

## Reproducible SDK profile and strict lint acceptance

The reviewed app compiles and targets API 36. Lint 8.13.2 takes the maximum of its built-in stable API and installed platform APIs when deciding whether to report `OldTargetApi`. Its built-in stable API is 36. A runner's newer SDK inventory can therefore change this result without an application-source change. Build 50's raw lint finding alone does not identify which newer platform was installed; the correction records the actual runner inventory before setup rather than assuming a particular package.

The three compile/test workflows now create a fresh isolated SDK, install the explicit reviewed API 36, build-tools 35.0.0 and NDK 27.0.12077973 profile, and verify the selected SDK roots, package metadata and Android CLI path before Gradle. Before/after inventory receipts are retained in their evidence artifacts. No lint suppression, rule disablement or removal of the runner's original packages is used. The production inspection job is unchanged.

Google's [SDK Manager documentation](https://developer.android.com/tools/sdkmanager) now identifies `sdkmanager` as deprecated and directs users to [`android sdk install`](https://developer.android.com/tools/agents/android-cli/commands/sdk_install). The setup action is retained at its reviewed commit but only downloads command-line tools build 15859902; its legacy package/license invocations are disabled. The replacement runs the actual Android CLI 1.1.16717390, verified against SHA-256 `b34ed0baeeffa2aaee2720d82491b975835696e2e56840449078bec885436564` **before execution**. The bundled `android` is only a launcher that downloads another executable, so pinning its enclosing SDK archive alone would not pin the CLI. The official download currently uses a `latest` URL; any changed bytes fail closed and require a reviewed pin update. There is no unverified launcher download or automatic executable update in the accepted path.

The final helper's real local provisioning run exited 0, with both CLI subprocesses exiting 0, and its verified isolated SDK contains only platform 36. Its complete raw output records no deprecation warning. The earlier two `sdkmanager` warnings remain in Build 50's evidence. These local checks do not replace fresh hosted verification.

This makes the declared API 36 toolchain reproducible. It does not validate Android 17/API 37 target-specific behaviour, and the original newer-target advisory remains recorded. Fresh hosted verification of this correction is required before acceptance.

## Permanent signing verification correction on resume

A real signing rehearsal with the recovered original key revealed a separate validation defect. The helper requested and produced both v2 and v3 signatures, but default `apksigner verify` uses the APK manifest's minimum SDK 31 and reported v2 as not verified. Explicit verification from API 24 successfully checked both schemes on the exact same APK. The previous parser therefore rejected a valid permanent-signed APK before writing success metadata.

The verification invocation now explicitly includes API 24 so both requested signature schemes are checked. The APK manifest and signing minimum remain API 31, and the pinned certificate, alias, package, version checks and prohibition on production credentials are unchanged. A behavioural regression first failed with the reproduced dual-signature rejection, then the complete permanent-signing suite passed all eight cases, including refusal of either missing scheme. A real signing rehearsal also passed, with the original pinned certificate and every packaged program/resource entry unchanged. This does not claim installation on Android 7; API 24 is only the signature-verification range.

## Remaining permanent TEST signing prerequisite

The Build job needs all four existing TEST secrets available in its authorised scope:

- `NOCTURNE_TEST_KEYSTORE_B64`
- `NOCTURNE_TEST_KEYSTORE_PASSWORD`
- `NOCTURNE_TEST_KEY_ALIAS` (expected alias `nocturne-test`)
- `NOCTURNE_TEST_KEY_PASSWORD`

They must refer to the existing JKS whose certificate is pinned below. The GitHub connection can publish reviewed source and read Actions evidence, but cannot administer Actions secrets. Build 50 supplied empty values for all four TEST variables. The earlier browser configuration check required sign-in. A repository owner must make the existing TEST configuration available for automatic CI signing; secret values must not be supplied in chat.

During recovery in a new session on 29 October, the original saved test keystore and its credentials were located and recovered into a private temporary directory. A certificate export confirmed the exact pinned SHA-256 below, an RSA-4096 public key and validity through 27 October 2056. No password/private-key content was printed, and no replacement key was generated. This enables local signing of a source-verified, CI-built release with the existing signing helper while preserving the installation identity. It does not establish that GitHub Actions secrets have been configured. The final delivery must identify its actual signing location and verification evidence.

## Exact-history transfer and acceptance

The authenticated GitHub app prepared an isolated import workflow in this same repository. A pinned incremental Git bundle preserved the original commits rather than recreating their identities. The successful import checked the manifest, every part, complete bundle, target tree, source digest, exact commit list, ancestry and connectivity before an ordinary push to one staging ref. It used the normal repository Actions credential and no TEST or production signing secret. The candidate remains separate from the temporary import history.

The final candidate must complete the existing Build workflow successfully. When re-signing the CI test candidate, independently verify its original source, SHA-256 and signer first, then compare every non-signature APK entry before and after. The permanent TEST signing helper must explicitly succeed, and the delivered artifact's source/run, package, version, signature, certificate and SHA-256 must be independently verified. If signing is performed locally with the recovered original key, record that fact and the missing automatic CI secret configuration; do not claim a permanent-signed Actions artifact exists. The accepted artifact and workflow identity are recorded in the delivery manifest; local debug/unsigned builds and artifacts from failed workflows are not installation acceptance.

Required permanent TEST certificate SHA-256:

```text
bd233ddcf84f6ed97a1597d433a0137dd2c0e64b13c2c537bb8ee5508de85f15
```

No PR, merge, tag, release, force-push or production-signing change is authorised by this delivery. Physical installation and BedJet/Samsung/TalkBack/widget/overnight acceptance remain NOT RUN; follow [DEVICE-VALIDATION.md](DEVICE-VALIDATION.md) with the actual delivered APK identity.
