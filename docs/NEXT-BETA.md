# Next beta engineering handover

Integration branch `codex/nocturne-next-beta` starts at published beta.7, `18705776806da1efc0e62b327926f3bc679f3acd`. The maintainer requested Android only. The independent worktrees start from the same revision. This is a review branch. Promotion, release numbering and publication require a separate release decision.

## Agreed scope

| Items | Work |
| --- | --- |
| 01–03 | Confirm Off with current device evidence, verify backups before replacement, erase deleted history from app-managed snapshots. |
| 04–06 | Confirm and remember manual controls per unit and mode, preserve saved data on update, connect on a deliberate appliance button or preset tap and handle uncertainty. |
| 07–08 | Tonight uses fan height, duration width and mode colour. Long heat occupies a single bottom-left tile. |
| 09–12 | Sleep stage compatibility, honest wake labels, reliable scheduled-start boundaries and persistent hand-over. |
| 13–15 | Lock-screen consent, named shortcut consent and official Nocturne signing for public updates through trusted release CI. |
| 16–18 | Keep clock picker policy. Restrict changes to diagnostics privacy to the exported file and its disclosure. Verify build inputs. |
| 19–23 | First run, sleep coverage labels, separate same-day sessions, skip one scheduled start with Undo, and finish a manual run at a chosen time. |

## Review and release

Check each commit against the affected tests. Exercise timeout, retry, process death and stale device reports in the controller. In storage, test exact exported bytes, semantic validation and interrupted erasure. For UI review, compare the same fixed-clock programme at normal and enlarged font sizes.

Before public release, upgrade a populated previous public Nocturne installation in place using the same Nocturne application ID, approved release certificate and a higher version code. Verify settings, saved profiles, device selection, quick controls and ordinary launch. Check the signed APK certificate and content digest. A disposable test key can validate an upgrade between two test builds but cannot update the public beta.

Hardware acceptance is pending until recorded on a real BedJet 3. Check Off with a delayed status, connect then cancel a preset, change Cool and fan across modes, release the connection across process death, and exercise timers with the phone disconnected.

## Signing and build changes

PR jobs use disposable test signing and transfer an unsigned artefact. The existing protected production job signs verified public prereleases as well as stable releases with the established Nocturne certificate. The optional long-lived test-signing step was removed from PR CI. If old test keys are still stored as repository-level Actions secrets, the repository owner should remove them there or scope them to a protected environment; workflow edits alone cannot revoke repository-level access. No credentials were inspected. The wrapper checksum is pinned to the official 8.14.3 binary digest.
