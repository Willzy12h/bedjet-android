# Independent-fixes device validation

Date: 29 October 2026. **Every physical result is NOT RUN.**

Use this with the current [correction register](INDEPENDENT-FIX-REGISTER.md) and its exact candidate manifest. Automated tests and native Robolectric images do not establish physical BedJet, Samsung, TalkBack or overnight behaviour.

## Candidate and upgrade identity

The package is `io.github.willzy12h.nocturne`. The existing permanent **TEST** signing certificate must remain:

```text
bd233ddcf84f6ed97a1597d433a0137dd2c0e64b13c2c537bb8ee5508de85f15
```

Use the exact APK, version code, source commit and SHA-256 recorded in the current delivery manifest. A local debug APK or unsigned release is not a compatible replacement for this permanent TEST build. If the certificate or version does not permit an update, stop and resolve the delivery identity. Do not uninstall the existing app to conceal that mismatch.

The isolated candidate branch is prepared to run the existing TEST build workflow after remote delivery is authorised. That is separate from production signing and release publication. Until the resulting APK has been obtained and its certificate/version/hash verified, installation delivery remains blocked.

| Record | Actual value |
| --- | --- |
| Tester, date/time and time zone | NOT RECORDED |
| Candidate commit, APK filename and SHA-256 | Refer to the final candidate manifest; NOT INSTALLED |
| Installed and candidate version names/codes and certificates | NOT INSPECTED ON PHONE |
| Phone model, Android/One UI version and patch | NOT RECORDED |
| BedJet 3 identity/location and firmware | NOT RECORDED |
| Clock app, chosen palette slots, font/display size and battery settings | NOT RECORDED |

## Supervised checks

Preserve app exports and all three original slot records before changing a real programme. Use modest settings, keep physical Off available, and remain present for the short daytime checks. Keep an independent wake alarm for any overnight test. A pending or unknown result must remain labelled that way in the test record.

| Check | Procedure and pass condition | Result |
| --- | --- | --- |
| Compatible update | Install the verified permanent TEST APK as an update. Profiles, settings, History, originals and the selected target survive. Opening the app sends no unsolicited start. | NOT RUN |
| Target and transport | Select the intended BedJet 3, read status, make and restore a small fan change, then verify Off. Check reconnect and denied/regranted Bluetooth permission; no other unit is controlled. | NOT RUN |
| First-step confirmation | Run the existing short supervised test after preserving the working-slot original. Compare the reported mode, applicable fan and temperature and first programme step with the device. A mismatching or unavailable report must not produce a confirmed start. | NOT RUN |
| External Off and disconnection | During a supervised programme press physical Off, then disconnect/reconnect the phone. The app must stop claiming the tracked programme is confirmed once it receives the relevant Off report; it must not restart the BedJet by itself. Record report timing separately from physical action timing. | NOT RUN |
| Cancellation and background | Leave/recreate the Activity during an accepted command and briefly remove the radio connection. Observe the actual confirmed, refused or unknown outcome. No duplicate start or abandoned unbounded owner is acceptable. | NOT RUN |
| Wake-only change | After Quiet settling has completed, change tonight's wake earlier and later while still eligible. Read back the remaining programme: completed settling is not replayed. Explicit Update tonight remains a separate profile-edit action. | NOT RUN |
| Fan Ease with Stay | Preview and run a suitable supervised profile with constant Middle temperature and different fan endpoints. Verify both saved fan speeds appear, temperature stays constant and the total plan/wake is preserved. Turbo-then-Heat must not gain another Turbo burst merely at a fan boundary. | NOT RUN |
| Import recovery and Share | Import a profile-only file and restart normally; unrelated data stays intact. Share survives ordinary Activity recreation. If the process is replaced while the document picker is open, the old Share is refused and can be retried. Do not manufacture disk corruption on the live installation. | NOT RUN |
| Demo isolation | While no real night is active, enter Demo with distinct day/night palette choices. Both choices are inherited, Demo changes remain disposable, and returning restores real data and target. | NOT RUN |
| Drag and controls | In Overcast and Midnight Ink, drag short and wrapped tall phases in both directions, including near a tall row's edge. Drop beside the reached row; cancel without mutation. The lifted card stays readable. Both limit-slider handles and charging switches remain visible. | NOT RUN |
| Largest text and TalkBack | At the phone's largest intended font/display settings, reach editor limits, Save/Discard, confirmation actions, History actions and all four tabs. Exercise TalkBack move-up/move-down, labels, focus and announcements. | NOT RUN |
| Widgets, tile and alarm delivery | Install both widget types and the Quick Settings tile. Verify the selected target, actions/confirmations, unknown/last-report wording, refresh and eligible alarm changes. Test Samsung battery restrictions with their actual settings recorded. | NOT RUN |
| Overnight and restoration | Only after the daytime checks, observe a complete night, wake and background return in the intended configuration. Restore/read back the affected original slot and record all failed or blocked cases. | NOT RUN |

For each failure record the exact action sequence, time, target, displayed state, physical behaviour and screenshot/diagnostics filename. Report missing evidence as NOT RUN or BLOCKED. The older [Samsung/BedJet acceptance plan](../review/samsung-bedjet-acceptance.md) contains broader scenario detail; its old source/signing references and historical counts do not identify this candidate.
