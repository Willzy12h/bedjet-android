# Nocturne handover

**Updated:** 23 October 2026. **Branch:** `claude/nocturne-completion`, based on independent-review commit `ae6e4d52a38dc2f295dd5c26f0f4614a24fed587`, which contains hardening commit `7fffabcb58741c48b765285d78c9284ce9967c27`. The remote had no newer fix branch at the completion fetch. Task 5 checkpoint commits named in the old handover could not be fetched or found in the clone; the remaining UI integration was reconstructed from the task report and current APIs.

## Quick read

- [Completion review](review/nocturne-completion.md): individual IR-01–IR-32 dispositions, implementation scope and qualifications.
- [Register](review/register.md): original workstream rows and the current completion summary at the top.
- [SDD ledger](review/sdd-ledger.md): design rulings, previous task history and completion decisions.
- [Physical acceptance](review/samsung-bedjet-acceptance.md): executable Samsung/BedJet 3 checklist. **Every physical result is NOT RUN.**
- [Plan](superpowers/plans/2026-10-22-nocturne-hardening.md), [independent review](review/independent-review-tasks-1-4.md) and [final pass](review/final-pass.md): source requirements and independent findings. Preserve the final-pass qualification for IR-24.

## Current implementation

Transport and runtime operations are target-bound, with guarded fresh-status automatic re-time and physical Off handling. Originals have a persistent integrity barrier; import/check-in have recovery journals; stale actions, widget Off identity and interrupted writes have regression coverage. An unknown imported original is not silently adopted for another BedJet. Legacy v1 `[]` slot provenance remains unknowable (IR-31), so upgraded real installations need a supervised inventory and restore check.

Task 5 integrates explicit BedJet 3 setup, preview and scheduled theme, History export, optional Health Connect overlay, hand-over controls, recovery/Resume, tonight-only wake override, morning check-in, and explicit spare-slot profile pinning. Midnight Ink, Daylight and Simple remain. New copy is in UK English resources; migration of every older literal UI string remains incomplete. Phone layout and TalkBack are not verified by Robolectric.

Task 7 configures fail-safe release signing and enables R8/resource shrinking. An incomplete signing configuration fails; with no key, release output is unsigned. There is no debug-key fallback or blanket keep rule. The tag workflow requires an explicit certificate fingerprint and increasing version code. This branch is a test candidate only: **do not merge, tag or publish a production release on the basis of automated results.**

## Verification recorded for this completion source

The full local Gradle gate passed **148 core tests and 238 of 244 app tests**, with **six app screenshot tests skipped** and **zero failures**. Debug assembly passed. Lint reported **zero errors, 22 warnings and one hint**. The unsigned shrunk release build passed and measured **4,217,595 bytes**. Fixed-clock Robolectric before/after captures are rendered-app evidence; they do not establish BedJet hardware or Samsung behaviour.

The shrunk test candidate is `outputs/nocturne-0.2.0-hardening-test.1.apk`: version name `0.2.0-hardening-test.1`, version code `2`, **4,229,883 bytes**. APK SHA-256: `1b7ac2f81971b4ef83cb5392bd207d1f0f4859f876de79dd4f7c185921d3110d`. Throwaway test signer certificate SHA-256: `8eba05db1a803aeb9b4b00ced5bae9bc56a1553d07c77d4216cafeddb452df67` (RSA 3072, APK Signature Scheme v2 verified with `apksigner`). Its key is outside Git. This is not a production signing identity; same-signer upgrade and installation on a phone are **NOT RUN**.

The physical checklist is entirely **NOT RUN**. Start with hash/certificate and same-signer upgrade, then explicit BedJet selection, transport and physical Off, all three original-slot backups and read-back restoration, preview/override, and interrupted-write recovery. Use an awake supervisor, modest settings and a separate verified wake alarm. IR-24 platform timing and IR-29 power-loss durability also remain physical limits.

## Reproduce automated gate

Use a local JDK 17 and Android SDK with `local.properties` pointing to the SDK. On Windows, from the repository root:

```powershell
.\gradlew.bat --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

`ScreenshotCapture` is opt-in; use its fixed-clock fixture and record the generated image files separately. For a test release, use a throwaway key **outside Git**, verify it with the SDK's APK signer and record version, size, certificate and APK SHA-256. The target PR base is `claude/lucid-mayer-i1h2gy`; push only the completion branch without force and open a **draft** PR. Hardware acceptance must remain visibly pending in the PR.
