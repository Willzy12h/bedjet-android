# Release merged-manifest audit

## Scope and evidence

Review base: `4fe524375fa2652ff3b97f3c1e942b35bf280155` on `gpt/nocturne-release`.
This review covers application and dependency manifest components, their effective
exported values, component permissions, widget metadata and the code behind public
entry points. The release audit must run on the **built merged release manifest**;
reviewing `app/src/main/AndroidManifest.xml` alone is insufficient.

Build evidence is pending the release gate. The component inventory and manifest
SHA-256 below will be completed from that output. No installation, explicit-intent
probe on Android, widget-host execution, Samsung test, Health Connect provider test
or Bluetooth operation is claimed by this static audit.

## Exposure changes within the permitted scope

`app/build.gradle.kts` generates a release-only manifest overlay. It changes these
five receivers to `android:exported="false"` using the manifest merger's
`tools:replace="android:exported"` directive:

| Receiver | Purpose and reason for private visibility |
| --- | --- |
| `.platform.PlatformClockReceiver` | System boot, package replacement and time/time-zone updates reschedule jobs/reminders and refresh widgets. No ordinary external sender is required. |
| `.night.BootReceiver` | System boot, package replacement, clock and exact-alarm-permission events reconcile local night state. No ordinary external sender is required. |
| `.night.AlarmChangedReceiver` | System next-alarm-clock changes record a pending retime for an active night. No ordinary external sender is required. |
| `.widget.QuickControlsReceiver` | Glance widget lifecycle/update delivery from Android and the app. Public broadcast access is unnecessary. |
| `.widget.SingleActionReceiver` | Glance widget lifecycle/update delivery from Android and the app. Public broadcast access is unnecessary. |

The overlay preserves the existing intent filters and widget-provider metadata.
The source manifest is unchanged because it is outside the authorised edit scope.
Consequently the original exports still appear in the source/debug declaration;
the hardening described here applies to the merged release artifact.

Android's [`<receiver>` documentation](https://developer.android.com/guide/topics/manifest/receiver-element#exported)
explicitly permits system and same-UID messages when `exported` is false. The
[Glance receiver declaration](https://developer.android.com/develop/ui/compose/glance/create-app-widget#declare-widget)
also shows a nonexported widget receiver. That page contains an earlier example
with `exported="true"`; the focused broadcast-receiver section and the platform's
receiver contract support the narrower visibility used here. These receivers do
not listen for Bluetooth broadcasts originating from a separate privileged app.

## Direct-intent review

Intent filters describe which implicit intents match a component. They do not
authenticate the sender or, by themselves, prevent a caller from naming an
exported component explicitly. See Android's [intent documentation](https://developer.android.com/guide/components/intents-filters)
and [`android:exported` guidance](https://developer.android.com/privacy-and-security/risks/android-exported).
The source comment above `BootReceiver` that protected broadcasts mean exporting
it “lets nothing else in” is too broad and is not used as the security argument.

Code inspection found explicit action allowlists in `PlatformClockReceiver`,
`BootReceiver` and `AlarmChangedReceiver`. Arbitrary action names therefore return
without their state-changing work. This audit does **not** claim an established
arbitrary-command exploit in those implementations; setting them nonexported
removes unnecessary exposure and leaves their validation in place. The two widget
receiver subclasses delegate their entry point to `GlanceAppWidgetReceiver`, with
no application-specific sender check. Their release exports are likewise removed.

The remaining public application entry points have these concrete boundaries:

| Entry point | Incoming data and reviewed behaviour |
| --- | --- |
| `MainActivity` | Launcher/Quick Settings preferences entry. The external `openTonight` boolean changes navigation. It does not itself issue a hardware command. |
| `HealthRationaleActivity` | Shows the static privacy-rationale resource in a dismissible dialog. No intent-supplied URL, record query or hardware command is executed. The legacy Health Connect provider needs an exported activity. |
| `HealthPermissionUsageActivity` alias | Targets the same rationale activity and requires `android.permission.START_VIEW_PERMISSION_USAGE`, matching the Android 14+ Health Connect integration. |
| `ShortcutActivity` | Accepts a known `NightActions.Command` enum and optional profile/captured identity. Every accepted command displays the confirmation dialog before starting the private service. `capturedConfirmation` does not skip that dialog. `NightActions.owned` rejects a device mismatch; `execute` rejects stale required night identity. External extras remain untrusted input, not authenticated notification provenance. |
| `SingleActionConfigActivity` | Required for widget-host configuration. Missing/invalid-sentinel widget ID cancels; only a visible user choice writes the selected action into widget preferences. It does not execute the selected BedJet command during configuration. This static review does not establish runtime handling of every malformed or stale host ID. |
| `ChargerJobService` | Exported only behind `android.permission.BIND_JOB_SERVICE` for the platform scheduler. |
| `NightTileService` | Exported only behind `android.permission.BIND_QUICK_SETTINGS_TILE` for System UI. |

Health Connect's [integration guide](https://developer.android.com/health-and-fitness/health-connect/get-started#show-privacy-policy)
documents the legacy activity and protected alias. Android's
[`JobService` contract](https://developer.android.com/reference/android/app/job/JobService#PERMISSION_BIND)
requires the job bind permission; the
[`TileService` contract](https://developer.android.com/reference/android/service/quicksettings/TileService)
requires the Quick Settings bind permission.

## Dependency entry points and exact artifacts

The downloaded AAR manifests were inspected, including Glance 1.1.1,
WorkManager 2.7.1, Health Connect 1.1.0, Room 2.2.5, core-remoteviews 1.1.0,
startup-runtime 1.1.1 and profileinstaller 1.3.1. These versions are the resolved
artifacts in this build, not an assumption based on the latest upstream source.
`app/build/reports/release-manifest-dependencies/artifacts.json` records AAR and
manifest SHA-256 values; adjacent XML files preserve their declarations.

`androidx.health.platform.client.impl.sdkservice.HealthDataSdkService` is exported
without a manifest permission. This is a deliberate legacy Health Connect callback
service, not an omitted component in the inventory. Inspection of the exact
`connect-client-1.1.0.aar` (`ce1601af0ef671ec6a76b0f29d2af22acc578e408c7107d08ea23067d3822593`)
using JDK `javap -c -p` found:

1. `onBind` accepts the SDK binding action and returns a Binder implementation.
2. All three methods—`setPermissionToken`, `getPermissionToken` and
   `getIsInForeground`—call `verifyPackageName` before scheduling their work.
3. That check obtains packages for `Binder.getCallingUid()`, requires the supplied
   package to belong to that UID, and then requires the package to equal
   `com.google.android.apps.healthdata`. An arbitrary caller cannot pass the check
   merely by putting that string in an intent or Binder argument.

The boundary is the Binder caller check; the action is only routing validation.
The checked methods do not independently verify a signing certificate. The public
bind entry remains available, so this audit does not claim rejection of every
third-party bind attempt or prove availability under abusive binding. Changing it
to private or inventing a new manifest permission would break the legacy provider
contract and is not part of this release hardening. The disassembly is retained in
`health-sdk-service-javap.txt` alongside the dependency evidence.

Glance's exported `GlanceRemoteViewsService` requires
`android.permission.BIND_REMOTEVIEWS`, which Android documents as
[signature/privileged](https://developer.android.com/reference/android/Manifest.permission#BIND_REMOTEVIEWS).
The core compatibility RemoteViews service has the same permission and defaults
to nonexported because it has no intent filter. WorkManager's exported
`SystemJobService` requires `BIND_JOB_SERVICE`. Its `DiagnosticsReceiver` and
AndroidX `ProfileInstallReceiver` require `android.permission.DUMP`; they are
tooling entry points, not unprotected application broadcasts. AndroidX documents
the [profile receiver's tooling purpose](https://developer.android.com/reference/androidx/profileinstaller/ProfileInstallReceiver)
and [WorkManager diagnostics](https://developer.android.com/develop/background-work/background-tasks/testing/persistent/debug).

The exact Glance receiver bytecode also handles its `DEBUG_UPDATE` and
`ACTION_TRIGGER_LAMBDA` actions before delegating to `AppWidgetProvider`.
Consequently an `APPWIDGET_UPDATE` filter is not an exhaustive description of what
an exported subclass can receive. The private release overlay closes that direct
external broadcast surface without changing widget actions or design. This is
static reachability evidence; no device exploit is claimed.

## Reproducible audit gate

After `:app:assembleRelease`, run:

```bash
python3 -B .github/workflows/release_manifest.py \
  --manifest app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml \
  --output app/build/reports/release-manifest
```

The helper uses an exact allowlist of component kind/name, effective exported
value and permission. It rejects newly introduced or missing components and
changed exports/permissions, including dependency components. It checks the Health
alias target, widget metadata, startup-provider authority, package/SDK and release
application flags. An absent `exported` attribute is reported as a default rather
than falsely described as explicit. Resource-controlled or initially disabled
components remain in the inventory; `enabled=false` is not treated as the
security boundary.

`report.json` preserves every component's actions, categories, enabled state,
metadata, provider permissions/URI-grant settings, all requested and declared
permissions, and the input manifest SHA-256. `report.md` renders the complete
component table. The release artifact evidence must retain these reports with the
manifest and packaged-APK checks.

## Built component inventory

Pending the actual merged release manifest. A successful helper run and the
observed inventory will replace this pending state before release review closes.
