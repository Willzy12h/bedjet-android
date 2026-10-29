# Independent-fixes TEST delivery

Date: 29 October 2026.

The user has authorised completing remote delivery and providing the permanent TEST APK. This supersedes the earlier approval block recorded in the independent-fixes handover. A newly authorised Git CLI dry-run push actually exited 128 with `fatal: unable to get password from user`; the connected GitHub app remains available for the repository.

## Additional CI contract correction

The complete Build workflow's screenshot checker still required 102 legacy filenames, while the unchanged 14 ScreenshotCapture methods produce 212. A signed artifact uploaded before that later gate failed would not be an accepted final build.

The checker now requires the explicit current 170 primary scenes and 42 secondary windows, retaining the original 28 historical baselines and rejection of missing or unexpected files. Secondary windows use their owning primary scene's metadata. Exact Activity/sheet dimensions and bounded native popup/picker dimensions remain checked. The current Scheduled Appearance, real setup and default Simple-view feature states are explicit. Full PNG verification rejects the reproduced missing-IEND file.

Test-first evidence records the initial failed contract run, a passing correction, then a native compatibility replay that exposed three stale Simple-view expectations. A second focused RED precedes the final correction. The final helper run exits 0 with 27 methods passed, zero failures/errors/skips. A labelled replay over the 212 accepted native PNGs also exits 0: 28 historical comparisons, 184 unbaselined captures, zero errors. The replay preserves PNG bytes and only adapts copied sidecar tags; it is not fresh rendering or remote CI evidence.

Only the screenshot checker and its Python regression tests change executable source in this delivery correction. Application/core production, Android tests and capture methods are unchanged from the independently verified source `3fd7f043308f10a8524ab29e75c96f5cd2eea292`.

## Exact-history transfer and acceptance

The authenticated GitHub app can prepare an isolated import workflow in this same repository. A pinned incremental Git bundle preserves the original commits rather than recreating their identities. The import checks the manifest, every part, complete bundle, target tree, source digest, exact commit list, ancestry and connectivity before an ordinary push to one staging ref. It uses the normal repository Actions credential and no TEST or production signing secret. The candidate remains separate from the temporary import history.

The final candidate must complete the existing Build workflow successfully. Its permanent TEST signing output must explicitly succeed, and the downloaded artifact's source/run, package, version, signature, certificate and SHA-256 must be independently verified. The accepted artifact and workflow identity are recorded in the delivery manifest; local debug/unsigned builds and artifacts from failed workflows are not installation acceptance.

Required permanent TEST certificate SHA-256:

```text
bd233ddcf84f6ed97a1597d433a0137dd2c0e64b13c2c537bb8ee5508de85f15
```

No PR, merge, tag, release, force-push or production-signing change is authorised by this delivery. Physical installation and BedJet/Samsung/TalkBack/widget/overnight acceptance remain NOT RUN; follow [DEVICE-VALIDATION.md](DEVICE-VALIDATION.md) with the actual delivered APK identity.
