# Release merged-manifest audit

## Scope and evidence

Review base: `4fe524375fa2652ff3b97f3c1e942b35bf280155` on `gpt/nocturne-release`.
This review covers application and dependency manifest components, their effective
exported values, component permissions, widget metadata and the code behind public
entry points. The release audit must run on the **built merged release manifest**;
reviewing `app/src/main/AndroidManifest.xml` alone is insufficient.

The actual merged release manifest passed the audit with **39 components, 12
exported components and two widget receivers**, with no issues reported. The
release implementation used for this build is
`b0e4e1b093c738ae5dde9d7106250c0c39ef8bc7`, version
`0.2.0-release-test.20261023`, version code `2`. The built manifest SHA-256 is
`4f985ff884569e5b3f5cdf6478c583be0e9937ffa2bfc943259fc2e6462e3701`.

The evidence is `app/build/reports/release-manifest/report.json` and `report.md`,
produced from
`app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml`.
The inventory includes 17 application and 22 dependency components. The five
release-only visibility overrides are present, and a structural comparison with
the source declaration confirms that all five retain their original intent
filters and metadata.

No installation, explicit-intent probe on Android, widget-host execution, Samsung
test, Health Connect provider test or Bluetooth operation is claimed by this
static audit. The manifest evidence is a merged release build result; packaged-APK
identity, signature and minification evidence is recorded in the release gate
report.

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

The built overlay preserves the existing intent filters and widget-provider
metadata, verified against the source declarations for all five receivers.
The source manifest is unchanged because it is outside the authorised edit scope.
Consequently the original exports still appear in the source/debug declaration;
the hardening described here applies to the merged release artefact.

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

## Dependency entry points and exact artefacts

The downloaded AAR manifests were inspected, including Glance 1.1.1,
WorkManager 2.7.1, Health Connect 1.1.0, Room 2.2.5, core-remoteviews 1.1.0,
startup-runtime 1.1.1 and profileinstaller 1.3.1. These versions are the resolved
artefacts in this build, not an assumption based on the latest upstream source.
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
component table. The release artefact evidence must retain these reports with the
manifest and packaged-APK checks.

## Built component inventory

The following is the complete observed inventory from the merged release
manifest. `false (default)` identifies the one component whose effective
visibility follows the platform default because it declares no intent filter;
every other exported value is explicit. Widgets are receivers and are counted
once in the 39-component total. `None` means no manifest component permission;
the explanations above describe additional code-level checks.

| Kind | Application | Dependency | Total |
| --- | ---: | ---: | ---: |
| Activity | 5 | 2 | 7 |
| Activity alias | 1 | 0 | 1 |
| Receiver (including two widgets) | 8 | 12 | 20 |
| Service | 3 | 7 | 10 |
| Provider | 0 | 1 | 1 |
| **Total** | **17** | **22** | **39** |

| Kind / origin | Component | Exported | Component permission | Justification / boundary |
| --- | --- | --- | --- | --- |
| activity / dependency | `androidx.glance.appwidget.action.ActionTrampolineActivity` | false | None | Glance internal action dispatch behind app-owned PendingIntents. |
| activity / dependency | `androidx.glance.appwidget.action.InvisibleActionTrampolineActivity` | false | None | Glance internal action dispatch behind app-owned PendingIntents. |
| activity / application | `io.github.willzy12h.nocturne.platform.HealthRationaleActivity` | true | None | Health Connect through Android 13 opens the static privacy rationale; no health records or command execution. |
| activity / application | `io.github.willzy12h.nocturne.platform.PlatformActionActivity` | false | None | Private widget/reminder PendingIntent entry; never expose direct command extras. |
| activity / application | `io.github.willzy12h.nocturne.ui.MainActivity` | true | None | Launcher and Quick Settings preferences entry; incoming openTonight only selects the Tonight UI. |
| activity / application | `io.github.willzy12h.nocturne.ui.ShortcutActivity` | true | None | Launcher/Samsung routines entry; enum validation and visible confirmation precede commands, with device/night checks downstream. |
| activity / application | `io.github.willzy12h.nocturne.widget.SingleActionConfigActivity` | true | None | Widget host opens visible configuration; invalid sentinel ID exits and a user choice is needed before preferences change. |
| activity-alias / application | `io.github.willzy12h.nocturne.platform.HealthPermissionUsageActivity` | true | `android.permission.START_VIEW_PERMISSION_USAGE` | Android 14+ Health permission-usage UI; platform-protected alias to the rationale activity. |
| provider / dependency | `androidx.startup.InitializationProvider` | false | None | AndroidX startup initialisation inside the app process; not a public data provider. |
| receiver / dependency | `androidx.glance.appwidget.MyPackageReplacedReceiver` | false | None | Glance package-update refresh from Android. |
| receiver / dependency | `androidx.glance.appwidget.UnmanagedSessionReceiver` | false | None | Glance internal session lifecycle dispatch. |
| receiver / dependency | `androidx.glance.appwidget.action.ActionCallbackBroadcastReceiver` | false | None | Glance internal action callback dispatch. |
| receiver / dependency | `androidx.profileinstaller.ProfileInstallReceiver` | true | `android.permission.DUMP` | AndroidX profile installation/benchmark tooling, protected by platform DUMP permission. |
| receiver / dependency | `androidx.work.impl.background.systemalarm.ConstraintProxy$BatteryChargingProxy` | false | None | WorkManager system battery constraint observation. |
| receiver / dependency | `androidx.work.impl.background.systemalarm.ConstraintProxy$BatteryNotLowProxy` | false | None | WorkManager system battery constraint observation. |
| receiver / dependency | `androidx.work.impl.background.systemalarm.ConstraintProxy$NetworkStateProxy` | false | None | WorkManager system network constraint observation. |
| receiver / dependency | `androidx.work.impl.background.systemalarm.ConstraintProxy$StorageNotLowProxy` | false | None | WorkManager system storage constraint observation. |
| receiver / dependency | `androidx.work.impl.background.systemalarm.ConstraintProxyUpdateReceiver` | false | None | WorkManager internal constraint-proxy updates. |
| receiver / dependency | `androidx.work.impl.background.systemalarm.RescheduleReceiver` | false | None | WorkManager system reboot/clock rescheduling; no public sender required. |
| receiver / dependency | `androidx.work.impl.diagnostics.DiagnosticsReceiver` | true | `android.permission.DUMP` | WorkManager diagnostics for privileged/shell tooling, protected by platform DUMP permission. |
| receiver / dependency | `androidx.work.impl.utils.ForceStopRunnable$BroadcastReceiver` | false | None | WorkManager internal force-stop/rescheduling alarm. |
| receiver / application | `io.github.willzy12h.nocturne.night.AlarmChangedReceiver` | false | None | System next-alarm-change delivery remains available; no ordinary external sender is needed. |
| receiver / application | `io.github.willzy12h.nocturne.night.BootReceiver` | false | None | System boot/package/clock/exact-alarm events remain deliverable; application action allowlist is additional validation. |
| receiver / application | `io.github.willzy12h.nocturne.night.NightAlarmReceiver` | false | None | App-scheduled identity-bearing night/retry PendingIntents only. |
| receiver / application | `io.github.willzy12h.nocturne.platform.AutomationCancelReceiver` | false | None | Immutable in-app notification PendingIntents only. |
| receiver / application | `io.github.willzy12h.nocturne.platform.BedtimeReminderReceiver` | false | None | App-scheduled immutable reminder PendingIntent only. |
| receiver / application | `io.github.willzy12h.nocturne.platform.PlatformClockReceiver` | false | None | System boot/package/clock events remain deliverable; no ordinary external sender is needed. |
| receiver (widget) / application | `io.github.willzy12h.nocturne.widget.QuickControlsReceiver` | false | None | App widget provider; Android system and app-owned widget updates do not require public broadcasts. |
| receiver (widget) / application | `io.github.willzy12h.nocturne.widget.SingleActionReceiver` | false | None | App widget provider; Android system and app-owned widget updates do not require public broadcasts. |
| service / dependency | `androidx.core.widget.RemoteViewsCompatService` | false (default) | `android.permission.BIND_REMOTEVIEWS` | Core RemoteViews compatibility binding; default nonexported with platform bind permission. |
| service / dependency | `androidx.glance.appwidget.GlanceRemoteViewsService` | true | `android.permission.BIND_REMOTEVIEWS` | Glance collection RemoteViews binding for widget hosts; platform bind permission excludes ordinary callers. |
| service / dependency | `androidx.health.platform.client.impl.sdkservice.HealthDataSdkService` | true | None | Legacy Health Connect provider binds across packages; every SDK Binder operation verifies the calling UID owns com.google.android.apps.healthdata. |
| service / dependency | `androidx.room.MultiInstanceInvalidationService` | false | None | Room internal database invalidation across the app's own processes. |
| service / dependency | `androidx.work.impl.background.systemalarm.SystemAlarmService` | false | None | WorkManager internal scheduling service. |
| service / dependency | `androidx.work.impl.background.systemjob.SystemJobService` | true | `android.permission.BIND_JOB_SERVICE` | WorkManager system JobScheduler binding; platform bind permission excludes ordinary callers. |
| service / dependency | `androidx.work.impl.foreground.SystemForegroundService` | false | None | WorkManager internal foreground-work coordinator. |
| service / application | `io.github.willzy12h.nocturne.platform.ChargerJobService` | true | `android.permission.BIND_JOB_SERVICE` | System JobScheduler must bind; platform bind permission excludes ordinary callers. |
| service / application | `io.github.willzy12h.nocturne.platform.NightTileService` | true | `android.permission.BIND_QUICK_SETTINGS_TILE` | System Quick Settings must bind; platform tile bind permission excludes ordinary callers. |
| service / application | `io.github.willzy12h.nocturne.service.BedJetService` | false | None | Internal owner of hardware operations; external apps cannot start or bind it directly. |

### Alias, provider, widget and enabled-state details

- The Health permission-usage alias targets
  `io.github.willzy12h.nocturne.platform.HealthRationaleActivity`.
- The single provider is `androidx.startup.InitializationProvider`, with authority
  `io.github.willzy12h.nocturne.androidx-startup`; it is nonexported, has no read
  or write permission and does not grant URI permissions. Its metadata registers
  WorkManager, EmojiCompat, process lifecycle and profile installer initialisers.
- `QuickControlsReceiver` retains `android.appwidget.provider=@xml/quick_controls_widget`;
  `SingleActionReceiver` retains `android.appwidget.provider=@xml/single_action_widget`.
  Both retain `android.appwidget.action.APPWIDGET_UPDATE` and are explicitly
  nonexported in this release.
- WorkManager's four constraint-proxy receivers and `RescheduleReceiver` begin
  disabled. `ConstraintProxyUpdateReceiver`, `SystemAlarmService`,
  `SystemJobService` and `SystemForegroundService` use resource-controlled
  enabled values. All remain in this inventory because enabled state is mutable
  and does not replace an export or permission boundary.
- Room's `MultiInstanceInvalidationService` is direct-boot aware and nonexported.
  All other listed components have effective `directBootAware=false`.

### Requested and declared permissions

The merged manifest requests these **15 permissions**. Requested permissions
grant capabilities to the application; they do not by themselves protect incoming
component calls. Component permissions are listed separately in the inventory.

| Requested permission | Observed attributes |
| --- | --- |
| `android.permission.BLUETOOTH_SCAN` | `usesPermissionFlags=neverForLocation` |
| `android.permission.BLUETOOTH_CONNECT` | No additional attributes |
| `android.permission.POST_NOTIFICATIONS` | No additional attributes |
| `android.permission.FOREGROUND_SERVICE` | No additional attributes |
| `android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE` | No additional attributes |
| `android.permission.SCHEDULE_EXACT_ALARM` | `maxSdkVersion=32` |
| `android.permission.USE_EXACT_ALARM` | No additional attributes |
| `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | No additional attributes |
| `android.permission.RECEIVE_BOOT_COMPLETED` | No additional attributes |
| `android.permission.INTERNET` | No additional attributes |
| `android.permission.BLUETOOTH_ADVERTISE` | No additional attributes |
| `android.permission.health.READ_SLEEP` | No additional attributes |
| `android.permission.WAKE_LOCK` | No additional attributes |
| `android.permission.ACCESS_NETWORK_STATE` | No additional attributes |
| `io.github.willzy12h.nocturne.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | No additional attributes |

The app declares
`io.github.willzy12h.nocturne.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` with
`protectionLevel=signature` and requests that same permission. The audit checks
the full requested-permission set and this declaration. The built package is
`io.github.willzy12h.nocturne`, minimum SDK `31`, target SDK `35`;
`allowBackup=false`, with `debuggable` and `testOnly` absent and therefore false.

### Result and remaining verification

**Static merged-manifest audit: PASS.** No missing or unexpected component,
export mismatch, permission mismatch, alias/provider/widget mismatch, or release
application-flag issue was reported. All five scoped visibility changes are
present and their filter/metadata contracts are preserved.

The unpermissioned legacy Health SDK service remains a reviewed, code-guarded
cross-package entry point. Its caller checks were verified in the exact resolved
AAR, but no live provider bind or adversarial Android Binder test was performed.
The screenshot fixture does not exercise widget hosting, system job dispatch,
Quick Settings binding or the five changed receivers. Physical-device and
platform-integration checks remain part of release acceptance.
