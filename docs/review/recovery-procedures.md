# Recovery procedures

**Scope note — 25 October 2026.** This document describes `c15c515a4dabbeae719c12323b6a6c0639c8406e`. GPT-2r changes are in progress for D2 confirmation, M1/M5 notices, M2 Off ordering, D4 Take back and D5 Resume offer. **Those changes and haptics are NOT reflected here.** Claude will assign a later re-pin after GPT-2r and haptics land. All physical results remain **NOT RUN**.

## Scope and verification status

These procedures describe the code at `c15c515a4dabbeae719c12323b6a6c0639c8406e` on `gpt/nocturne-f1`. Button names below match that base. Source notes give repository-relative locations so that later changes can be checked against the instructions.

**Physical verification: NOT RUN.** No BedJet, physical phone, Samsung device or TalkBack run was performed for this document. The source and relevant test definitions were inspected, and the existing automated suites passed in the [GPT-5 gate](docs2-reconciliation.md#required-gate). This is not evidence of a successful hardware recovery.

## 1. “Some saved data couldn't be read”

Use this procedure when the unreadable-data notice appears on **Tonight** or under **Settings → Backups and snapshots**. If the notice also says **A restore isn't finished**, complete section 2 first.

1. Select **View**. Read the file names and the explanation. **Hide details** closes those details again.
2. To recover saved data, select **Restore** on the Tonight notice. This opens **Settings → Backups and snapshots**; it does not immediately restore anything. In Settings, the snapshots are already below the notice, so the notice itself has no separate **Restore** button.
3. Before restoring, note that **every snapshot or backup-file restore turns charger auto-start off and clears the device saved for that automation**, regardless of the current or backed-up setting. Do not rely on charger auto-start remaining enabled after a restore. Source: `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt:473`.
4. Choose an appropriate snapshot by its label and date, then select **Restore** beside it. Read **Restore this snapshot?** and select **Restore** to proceed, or **Cancel** to leave the saved data as it is.
5. If you have an exported Nocturne backup instead, select **Restore from file** and choose that file. Selecting the file starts the import and restore; this route has no second snapshot-style confirmation dialogue.
6. Read the result. If the snapshot could not be read or the import was refused, do not assume the data was recovered. If no suitable backup is available and you choose to continue with the current data, select **Dismiss** on the unreadable-data notice.

**What these choices do.** Nocturne uses defaults where it cannot read saved files. **View** only expands the explanation. A successful snapshot or file restore replaces profiles and restores settings and history, with exceptions for the current night, its history record, the selected BedJet and other current-device settings. It merges saved slot originals. Nocturne saves the previous app data as a snapshot first, and restoring app data does not send a programme to the BedJet. Invalid backup contents are refused before the restore changes the repository.

**Dismiss** clears the unreadable-file report. It does not reconstruct missing data, remove the set-aside copies, finish a pending restore or lift the block on slots whose originals are unreadable. Automatic snapshots are paused while the unreadable-file report is present; clearing that report permits automatic snapshots again. A remaining saved-originals warning needs section 3.

Source notes: notice controls and visibility, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/RecoveryNotices.kt:32–38` and `:59–74`; labels and explanation, `app/src/main/res/values/strings_ui.xml:5–19`; Tonight placement and callback, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightScreen.kt:96–105`; navigation, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/MainActivity.kt:191–195`; backup controls and snapshot confirmation, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/SettingsScreen.kt:180–203` and `:256–263`; file-selection callback, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/SettingsScreen.kt:78–80`; import execution, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/AppViewModel.kt:359–364`; restore scope, `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt:452–454`; validation and pre-restore snapshot, `:458–463`; restored profiles, `:466`; preserved settings and history, `:469–477`; saved-original merge, `:478–483`; snapshot entry point, `:504–507`; defaults, dismissal and automatic snapshots, `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt:155–173`, `:397–410`, `:529–532` and `:771–774`.

## 2. “A restore isn't finished”

This notice means a saved-data change is still waiting to finish writing. It usually concerns a restore, but the same notice can also follow an unfinished accepted morning suggestion. The screen can show the intended values even though the write has not finished. While the underlying write remains blocked, further changes to affected profiles, settings, history and saved originals cannot be saved.

1. Select **View** to read the recovery instruction.
2. Free some storage space, then reopen Nocturne, as the notice asks.
3. Check whether **A restore isn't finished** has gone. Nocturne attempts to finish the pending restore when it opens. It also requires the pending write to finish before a later write to one of the affected files can proceed.
4. If the notice remains, treat the restore as unfinished. Keep the backup available and do not rely on subsequent edits having been saved. Freeing space is the instruction given by this build; it does not guarantee that every possible storage failure is resolved.

**Dismiss** is not offered when the unfinished restore is the only problem. **Restore** is also absent from that notice because another restore would wait on the same unfinished write. If unreadable files are reported alongside it, **Dismiss** can clear their report, but the unfinished-restore notice remains. A damaged restore-journal file that has been set aside is handled as an ordinary unreadable file; the special title is what distinguishes a restore still waiting to finish.

Source notes: exact title and steps, `app/src/main/res/values/strings_ui.xml:7–9`; separate notice states, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/RecoveryNotices.kt:29–38` and `:54–70`; pending-write guard and completion, `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt:596–619`; start-up recovery and reading intended values, `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt:628–655` and `:683–688`; dismissal retains the pending notice, `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt:771–774`; accepted morning suggestions use the same recovery mechanism, `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt:312–319` and `:665`.

## 3. Unreadable saved originals: recover them or accept the loss

A saved original is Nocturne's record of what a BedJet slot held before Nocturne first wrote that slot. When those records become unreadable, Nocturne blocks writes to a slot that has no saved original. Dismissing the file notice or reopening the app does not remove that protection.

### Recover an original from a backup

1. Before accepting loss, check for a usable snapshot or exported backup containing the original for the **same BedJet and the same slot**.
2. Restore that snapshot or file using section 1.
3. Check the saved-original entries for the selected BedJet under **Settings → BedJet**. This list shows saved programmes with contents; it does not display saved empty-slot markers. Absence from this list therefore does not prove that an empty-slot original failed to recover. Recovering an exact original allows that slot to pass the missing-original check. Other slots without recovered originals remain blocked.

Restoring a backup with no matching original does not authorise the app to call the slot's current contents its original. The block can therefore remain visible after an otherwise successful data restore. Recovering the record into Nocturne also does not write it back to the BedJet; **Preview & put back** and **Put original back** are a separate operation, described in section 6.

Source notes: first-original retention and missing-original guard, `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt:352–377`; persistent block, `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt:136–144`; backup merge, `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt:478–483`; selected-device entries, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/SettingsScreen.kt:116–121`; exact-slot recovery assertions, `app/src/test/kotlin/io/github/willzy12h/nocturne/data/RepositoryTest.kt:311–332`.

### Accept that the originals are lost

1. Select **Accept lost originals…** on the notice.
2. Read **Accept that these originals are lost?**. Select **Cancel** if you want to keep the block and look for a backup.
3. Select **Accept and continue** only when you accept the stated loss and the uncertainty of future originals.
4. Read any error. If Nocturne says the unreadable originals have not been set aside, reopen it and try again. If it cannot record acceptance or lift the block, treat the affected slots as still blocked.

**What acceptance changes.** Nocturne records the acceptance before removing the block. A later write to a slot with no saved original can then save whatever that slot holds at that time as its original. The new record is marked **uncertain**, because the slot may already contain one of Nocturne's own programmes. Every original newly recorded after acceptance is marked uncertain, including after a restart. An existing readable original is retained.

**What acceptance does not recover.** The confirmation does not reconstruct the lost programmes, read back every slot, write a programme or prove that the slot's present contents are the original. It permits future writes after acknowledging the loss. It also clears the ordinary unreadable-file report, while an unfinished-restore notice remains.

If a usable backup is found later, an exact matching original from it can replace an uncertain original that Nocturne recorded locally after the accepted loss. This exception is specific: ordinary current originals retain priority when backups are merged, and accepting loss does not make newly recorded originals certain again.

Source notes: dialogue and callbacks, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/RecoveryNotices.kt:70–84` and `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/AppViewModel.kt:265–269`; exact wording, `app/src/main/res/values/strings_ui.xml:14–19`; acceptance ordering and failures, `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt:397–424`; future uncertainty and first-original retention, `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt:148–153` and `:352–367`; later exact-backup replacement, `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt:478–483` and `:741–745`; recovery error messages, `app/src/main/res/values/strings_runtime.xml:146–147`.

## 4. Unconfirmed slot write: “Mark as checked”

On Tonight, the notice identifies the command and slot: **Your BedJet hasn't confirmed “[command]” on slot [number]. Check the BedJet, then mark it as checked.** It is shown for an unresolved write belonging to the selected BedJet.

1. Read the command and slot number. Check that slot and the BedJet's actual state before continuing.
2. If you cannot establish what happened, leave the notice in place. The notice does not itself prove whether the write failed, partly succeeded or completed.
3. Once you have checked, select **Mark as checked**. The app reports **Marked as checked.**

This is your acknowledgement. The handler only clears Nocturne's saved unresolved-slot-write record; it does not query, resend, restore or verify the programme. It does not change an active night's own confidence or clear its separate recovery reason. If the night still says its state is unknown, follow section 5 as well.

An unresolved record can also be settled by a successful later write of that slot, by ending the night to which the write belongs, or by keeping a fresh run of that night. A write concerning another slot or another night is not necessarily cleared by those actions.

Source notes: notice text and button, `app/src/main/res/values/strings_ui.xml:21–22`; selected-device display, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightScreen.kt:102–105`; local acknowledgement, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/AppViewModel.kt:259–260` and `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:936–940`; result wording, `app/src/main/res/values/strings_runtime.xml:13`; unresolved states and association rules, `app/src/main/kotlin/io/github/willzy12h/nocturne/night/RuntimeState.kt:26–44`; failed-write handling, `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:917–933`; related-night settlement, `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:421–427` and `:668–671`.

## 5. A night whose BedJet state is unknown

The Tonight message is **BedJet state unknown. Check the unit before another programme.** Check the BedJet before choosing how Nocturne should continue. The following controls have different consequences.

Source notes: displayed state, `app/src/main/res/values/strings_ui.xml:200–201` and `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightScreen.kt:124–133`; recovery controls, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/NightRecoveryControls.kt:45–79`.

### Keep tracking the current run

1. On Tonight, select **Keep tracking** when the night is unknown.
2. Wait for the result. Nocturne reconnects and requests a fresh report from the selected BedJet. The action requires an unknown tracked night and a report that the BedJet is running.
3. If it succeeds, Nocturne follows the reported current run manually. Check the manual-tracking message. If the report says the BedJet is not running, the action is refused with **Check the BedJet programme, or stop tracking. No programme was restarted.** A connection or status-reading failure also does not establish a recovered run.

Successful **Keep tracking** uses the reported mode, fan, target temperature and remaining time to record one current manual step. It marks that tracked run confirmed, updates its recorded end time and clears the pending programme change, pending re-time and recovery reason. It also acknowledges an unresolved slot write associated with this night. Nocturne has not resumed the saved programme; alarm changes will not restart it. A separate **Resume programme** offer may be shown while a valid offer for this night remains.

Source notes: eligibility and manual-run transition, `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:654–678`; fresh selected-device report, `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:680–690`; re-time refusal for manual tracking, `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:533–539` and `:563–574`; wording, `app/src/main/res/values/strings_runtime.xml:18` and `:43`, and `app/src/main/res/values/strings_ui.xml:201`; resume-offer visibility, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/NightRecoveryControls.kt:37–40` and `:54–56`.

### Stop tracking this night

1. Select **Stop tracking**.
2. Read **Stop tracking this night?**, including **This doesn't turn the BedJet off**.
3. Select **Stop tracking** in the dialogue to stop following the night. Select **Keep tracking** in this dialogue to cancel it. That dialogue's **Keep tracking** button only closes the dialogue; it does not perform the fresh-status action described above.
4. Check the BedJet before starting another programme. Use the BedJet's actual state to decide whether it needs to be turned off; stopping tracking does not do that for you.

Confirmed **Stop tracking** is local. It removes the active tracked night, keeps its record in History with a stopped event, clears the pending re-time and wake override, and cancels the night's local alarms and wake/quiet notifications. A slot-write record associated with that night is cleared. It sends no command to turn off the BedJet. A temporary **Resume previous night** offer may remain; choosing that later is a separate action which can send the remaining programme.

Source notes: dialogue callbacks and exact distinction between the two **Keep tracking** controls, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/NightRecoveryControls.kt:48–52` and `:74–79`; warning, `app/src/main/res/values/strings_ui.xml:99`; local stop and cleanup, `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:421–450`; local command dispatch, `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:51–69`; resume-offer creation, `core/src/main/kotlin/io/github/willzy12h/nocturne/core/night/NightChanges.kt:64–78`; resume execution, `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:643–652`.

### Take back after releasing the BedJet

1. If you previously used **Release BedJet for another phone**, open **More** on Tonight and select **Take back**. The menu offers **Take back** while the connection is released; an unknown night alone does not make that item appear.
2. Wait for the result. Take back resumes automatic connection and reads fresh status from the selected BedJet. It does not replay the uncertain programme or resend a failed command.
3. If Nocturne reports that the BedJet is not running the night as tracked, check the unit and choose **Keep tracking** or **Stop tracking** as described above.
4. If it reports **Connected and checked the current BedJet status.**, still read the Tonight state. In this base, a matching check does not promote an already-unknown night to confirmed or clear an existing recovery reason or unresolved slot-write record. Use the corresponding recovery procedure if those remain.

For a tracked programme, the check compares fresh running status and programme progress with the expected current step, including relevant mode, fan and temperature values. For a manually tracked run it uses the manual-run comparison, including its start/end allowances. A mismatch sets the night to unknown, clears the pending re-time and records that the BedJet changed elsewhere.

There is one separate completion case: if an earlier explicit ending command is still pending for this exact night and the fresh report shows the BedJet is no longer running, Take back can finish the local tracked night as stopped. It sends no new ending command to do so. Also, Take back resumes automatic connection before it checks status; if the status check then fails, the **Take back** menu item can disappear even though the check did not succeed. Treat the result message and remaining night state as authoritative.

Source notes: **More** menu, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/NightRecoveryControls.kt:47` and `:66–71`; release-dependent menu label, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/features/FeatureSections.kt:101–103` and `:125–126`; button names, `app/src/main/res/values/strings_features.xml:60–61`; complete base Take back implementation, `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:1056–1075`; comparison rules, `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:827–850`; pending-end completion, `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:802–808`; release state ends before the status read, `app/src/main/kotlin/io/github/willzy12h/nocturne/night/LinkSessions.kt:137–146`; result wording, `app/src/main/res/values/strings_runtime.xml:72–73`.

## 6. Restore original refused: an imported step is outside its mode's temperature range

The refusal says: **A step in this saved original has a temperature its mode doesn't allow, so it wasn't sent to the BedJet.** Originals restored into Nocturne from either a backup file or a snapshot are treated as imported.

### What to do

1. Stop this restore attempt and keep any known-good backup available. This refusal occurs before a connection or slot write in the restore action; repeating the same attempt does not correct the saved step.
2. Check that the selected BedJet and slot are the ones you intended. The route is **Settings → BedJet → Preview & put back** beside the saved original. The confirmation is titled **Restore original slot [number]?**, and its action is **Put original back**.
3. Use **Cancel** if the preview is not the intended original. The dialogue shows the saved programme, mode, fan and duration of each step, but does not show a temperature value or provide a control for editing the original.
4. Do not assume that importing another backup will replace the refused original. This base normally keeps an existing original for the same BedJet and slot. Its replacement exception applies to uncertain originals recorded locally after accepted loss, as described in section 3; it does not apply simply because a current original was imported and is now refused. The base restore dialogue provides no repair or overwrite action for this case.

### What the guard checks

An imported original is refused if a step other than Standby or Wait has a temperature outside that mode's allowed range. This includes Turbo's fixed temperature. Standby and Wait have unused temperature values and are excluded from this check. The refusal does not clamp, rewrite or send the offending original. A directly device-recorded original is kept byte-for-byte by this particular check; successfully importing a backup does not guarantee that every imported original can subsequently be written to the BedJet.

Even a valid original has other guards: Nocturne must have no tracked night, a valid slot and a selected BedJet, the saved original must still match the one chosen in the UI, and a fresh report must show no stored programme running. End the tracked night or explicitly stop tracking first, then check the BedJet itself: **Stop tracking** alone does not stop its programme. A successful **Put original back** writes the saved original to the slot and verifies it; it does not start a night.

Source notes: refusal and all action guards, `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:880–897`; refusal text, `app/src/main/res/values/strings_runtime.xml:49–55`; imported marking and merge precedence, `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt:478–483`; selected-original and target rechecks, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/AppViewModel.kt:245–249`; available entries and full confirmation UI, `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/SettingsScreen.kt:116–121` and `:265–284`; exact labels and explanation, `app/src/main/res/values/strings_ui.xml:133`, `:183–184` and `:192`; write/readback flow, `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:899–916`.

Readback implementation: `core/src/main/kotlin/io/github/willzy12h/nocturne/core/link/BedJetController.kt:44–54`.

## Evidence available for review

The following test definitions were read as supporting evidence and ran within the passing [GPT-5 gate](docs2-reconciliation.md#required-gate). They use app test environments and simulated BedJet behaviour; they are not a record of physical-device acceptance.

| Procedure | Relevant test definitions |
| --- | --- |
| Unreadable-data buttons, unfinished-restore controls, loss confirmation and checked callback | `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RecoveryNoticesTest.kt:36–111` |
| Exact original recovery; accepted loss, future uncertainty and later backup replacement; acceptance failure | `app/src/test/kotlin/io/github/willzy12h/nocturne/data/RepositoryTest.kt:311–332`, `:346–366`, `:371–394` and `:422–429` |
| Unfinished restore survives dismissal and failed start-up writes | `app/src/test/kotlin/io/github/willzy12h/nocturne/data/RepositoryTest.kt:975–1049` |
| Mark as checked and related/unrelated-night slot-write settlement | `app/src/test/kotlin/io/github/willzy12h/nocturne/night/TruthfulOutcomesTest.kt:246–299` |
| Local Stop tracking and fresh target-specific Keep tracking | `app/src/test/kotlin/io/github/willzy12h/nocturne/night/RuntimeOwnershipTest.kt:298–304`, `:402–413` and `:428–438` |
| Take back reports a mismatch or a completed check; pending Off can finish from fresh non-running status | `app/src/test/kotlin/io/github/willzy12h/nocturne/night/TruthfulStateTest.kt:100–115`; `app/src/test/kotlin/io/github/willzy12h/nocturne/night/SendConfirmationTest.kt:198–212` |
| Imported temperature refusal with nothing sent; Standby/Wait exceptions, Turbo guard and recorded-original preservation | `app/src/test/kotlin/io/github/willzy12h/nocturne/night/ImportedOriginalRestoreTest.kt:68–149` |

**Remaining verification: NOT RUN on a physical BedJet or phone; NOT RUN on Samsung; NOT RUN with TalkBack.** This document deliberately describes the cited base and makes no claim about later recovery fixes.
