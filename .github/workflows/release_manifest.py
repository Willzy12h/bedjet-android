#!/usr/bin/env python3
"""Audit the built release manifest, including dependency components.

The allowlist is intentionally exact: a new component or changed export/permission
requires review. Intent filters are inventory, never an access-control boundary.
"""

import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET


A = "{http://schemas.android.com/apk/res/android}"
PACKAGE = "io.github.willzy12h.nocturne"
KINDS = {"activity", "activity-alias", "receiver", "service", "provider"}

# kind, name, exported, effective component permission, reason for visibility
POLICY_ROWS = [
    ("activity", ".ui.MainActivity", True, None, "Launcher and Quick Settings preferences entry; incoming openTonight only selects the Tonight UI."),
    ("activity", ".platform.PlatformActionActivity", False, None, "Private widget/reminder PendingIntent entry; never expose direct command extras."),
    ("activity", ".platform.HealthRationaleActivity", True, None, "Health Connect through Android 13 opens the static privacy rationale; no health records or command execution."),
    ("activity-alias", ".platform.HealthPermissionUsageActivity", True, "android.permission.START_VIEW_PERMISSION_USAGE", "Android 14+ Health permission-usage UI; platform-protected alias to the rationale activity."),
    ("activity", ".ui.ShortcutActivity", True, None, "Launcher/Samsung routines entry; enum validation and visible confirmation precede commands, with device/night checks downstream."),
    ("activity", ".widget.SingleActionConfigActivity", True, None, "Widget host opens visible configuration; invalid sentinel ID exits and a user choice is needed before preferences change."),
    ("activity", ".widget.WidgetConfirmationActivity", False, None, "Private direct widget PendingIntent opens only the translucent confirmation; captured target/night are checked again by the command owner."),
    ("service", ".platform.ChargerJobService", True, "android.permission.BIND_JOB_SERVICE", "System JobScheduler must bind; platform bind permission excludes ordinary callers."),
    ("service", ".platform.NightTileService", True, "android.permission.BIND_QUICK_SETTINGS_TILE", "System Quick Settings must bind; platform tile bind permission excludes ordinary callers."),
    ("service", ".service.BedJetService", False, None, "Internal owner of hardware operations; external apps cannot start or bind it directly."),
    ("receiver", ".platform.PlatformClockReceiver", False, None, "System boot/package/clock events remain deliverable; no ordinary external sender is needed."),
    ("receiver", ".platform.AutomationCancelReceiver", False, None, "Immutable in-app notification PendingIntents only."),
    ("receiver", ".platform.BedtimeReminderReceiver", False, None, "App-scheduled immutable reminder PendingIntent only."),
    ("receiver", ".night.NightAlarmReceiver", False, None, "App-scheduled identity-bearing night/retry PendingIntents only."),
    ("receiver", ".night.BootReceiver", False, None, "System boot/package/clock/exact-alarm events remain deliverable; application action allowlist is additional validation."),
    ("receiver", ".night.AlarmChangedReceiver", False, None, "System next-alarm-change delivery remains available; no ordinary external sender is needed."),
    ("receiver", ".widget.QuickControlsReceiver", False, None, "App widget provider; Android system and app-owned widget updates do not require public broadcasts."),
    ("receiver", ".widget.SingleActionReceiver", False, None, "App widget provider; Android system and app-owned widget updates do not require public broadcasts."),
    ("activity", "androidx.glance.appwidget.action.ActionTrampolineActivity", False, None, "Glance internal action dispatch behind app-owned PendingIntents."),
    ("activity", "androidx.glance.appwidget.action.InvisibleActionTrampolineActivity", False, None, "Glance internal action dispatch behind app-owned PendingIntents."),
    ("receiver", "androidx.glance.appwidget.action.ActionCallbackBroadcastReceiver", False, None, "Glance internal action callback dispatch."),
    ("receiver", "androidx.glance.appwidget.UnmanagedSessionReceiver", False, None, "Glance internal session lifecycle dispatch."),
    ("receiver", "androidx.glance.appwidget.MyPackageReplacedReceiver", False, None, "Glance package-update refresh from Android."),
    ("service", "androidx.glance.appwidget.GlanceRemoteViewsService", True, "android.permission.BIND_REMOTEVIEWS", "Glance collection RemoteViews binding for widget hosts; platform bind permission excludes ordinary callers."),
    ("service", "androidx.core.widget.RemoteViewsCompatService", False, "android.permission.BIND_REMOTEVIEWS", "Core RemoteViews compatibility binding; default nonexported with platform bind permission."),
    ("service", "androidx.health.platform.client.impl.sdkservice.HealthDataSdkService", True, None, "Legacy Health Connect provider binds across packages; every SDK Binder operation verifies the calling UID owns com.google.android.apps.healthdata."),
    ("provider", "androidx.startup.InitializationProvider", False, None, "AndroidX startup initialization inside the app process; not a public data provider."),
    ("service", "androidx.room.MultiInstanceInvalidationService", False, None, "Room internal database invalidation across the app's own processes."),
    ("service", "androidx.work.impl.background.systemalarm.SystemAlarmService", False, None, "WorkManager internal scheduling service."),
    ("service", "androidx.work.impl.background.systemjob.SystemJobService", True, "android.permission.BIND_JOB_SERVICE", "WorkManager system JobScheduler binding; platform bind permission excludes ordinary callers."),
    ("service", "androidx.work.impl.foreground.SystemForegroundService", False, None, "WorkManager internal foreground-work coordinator."),
    ("receiver", "androidx.work.impl.utils.ForceStopRunnable$BroadcastReceiver", False, None, "WorkManager internal force-stop/rescheduling alarm."),
    ("receiver", "androidx.work.impl.background.systemalarm.ConstraintProxy$BatteryChargingProxy", False, None, "WorkManager system battery constraint observation."),
    ("receiver", "androidx.work.impl.background.systemalarm.ConstraintProxy$BatteryNotLowProxy", False, None, "WorkManager system battery constraint observation."),
    ("receiver", "androidx.work.impl.background.systemalarm.ConstraintProxy$StorageNotLowProxy", False, None, "WorkManager system storage constraint observation."),
    ("receiver", "androidx.work.impl.background.systemalarm.ConstraintProxy$NetworkStateProxy", False, None, "WorkManager system network constraint observation."),
    ("receiver", "androidx.work.impl.background.systemalarm.RescheduleReceiver", False, None, "WorkManager system reboot/clock rescheduling; no public sender required."),
    ("receiver", "androidx.work.impl.background.systemalarm.ConstraintProxyUpdateReceiver", False, None, "WorkManager internal constraint-proxy updates."),
    ("receiver", "androidx.work.impl.diagnostics.DiagnosticsReceiver", True, "android.permission.DUMP", "WorkManager diagnostics for privileged/shell tooling, protected by platform DUMP permission."),
    ("receiver", "androidx.profileinstaller.ProfileInstallReceiver", True, "android.permission.DUMP", "AndroidX profile installation/benchmark tooling, protected by platform DUMP permission."),
]


def qualify(name, package=PACKAGE):
    if name.startswith("."):
        return package + name
    return package + "." + name if "." not in name else name


POLICY = {(kind, qualify(name)): (exported, permission, reason)
          for kind, name, exported, permission, reason in POLICY_ROWS}
REVIEWED_PERMISSIONS = {"android.permission." + name for name in (
    "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE", "POST_NOTIFICATIONS",
    "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_CONNECTED_DEVICE", "SCHEDULE_EXACT_ALARM",
    "USE_EXACT_ALARM", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "RECEIVE_BOOT_COMPLETED",
    "INTERNET", "health.READ_SLEEP", "WAKE_LOCK", "ACCESS_NETWORK_STATE",
)} | {PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}


def audit(path):
    root = ET.parse(path).getroot()
    application = root.find("application")
    if application is None:
        raise ValueError("Manifest has no application")
    sdk = root.find("uses-sdk")
    issues = []
    if root.get("package") != PACKAGE:
        issues.append(f"Unexpected package: {root.get('package')}")
    if sdk is None or sdk.get(A + "minSdkVersion") != "31" or sdk.get(A + "targetSdkVersion") != "35":
        issues.append("Expected merged release SDK declarations minSdk=31, targetSdk=35")
    for name, expected in (("debuggable", "false"), ("testOnly", "false"), ("allowBackup", "false")):
        actual = application.get(A + name, "false" if name != "allowBackup" else "true")
        if actual != expected:
            issues.append(f"Application {name}: expected {expected}, found {actual}")
    requested = {node.get(A + "name") for node in root if node.tag.startswith("uses-permission")}
    if requested != REVIEWED_PERMISSIONS:
        issues.append(f"Requested permission inventory changed: added={sorted(requested - REVIEWED_PERMISSIONS)}, missing={sorted(REVIEWED_PERMISSIONS - requested)}")
    declarations = {node.get(A + "name"): node.get(A + "protectionLevel") for node in root.findall("permission")}
    if declarations != {PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION": "signature"}:
        issues.append("Declared permission inventory/protection levels changed")
    exact_alarm = next((node for node in root.findall("uses-permission") if node.get(A + "name") == "android.permission.SCHEDULE_EXACT_ALARM"), None)
    if exact_alarm is None or exact_alarm.get(A + "maxSdkVersion") != "32":
        issues.append("SCHEDULE_EXACT_ALARM must remain limited to API 32")

    components = []
    seen = set()
    for element in application:
        if element.tag not in KINDS:
            continue
        name = qualify(element.get(A + "name", ""), root.get("package", PACKAGE))
        key = (element.tag, name)
        if key in seen:
            issues.append(f"Duplicate component: {key}")
        seen.add(key)
        declared = element.get(A + "exported")
        if declared not in (None, "true", "false"):
            issues.append(f"Unresolved exported attribute for {name}: {declared}")
        # Providers default to false at this app's minSdk (31); other components
        # historically default according to the presence of intent filters.
        exported = declared == "true" if declared is not None else (element.tag != "provider" and element.find("intent-filter") is not None)
        permission = element.get(A + "permission", application.get(A + "permission")) or None
        policy = POLICY.get(key)
        if policy is None:
            issues.append(f"Unreviewed component: {element.tag} {name}")
        elif (exported, permission) != policy[:2]:
            issues.append(f"{name}: expected exported={policy[0]}, permission={policy[1]!r}; found exported={exported}, permission={permission!r}")
        metadata = {entry.get(A + "name"): entry.get(A + "resource", entry.get(A + "value")) for entry in element.findall("meta-data")}
        components.append({
            "type": element.tag, "name": name, "origin": "application" if name.startswith(PACKAGE + ".") else "dependency",
            "exported": exported, "exported_declared": declared, "permission": permission,
            "enabled": element.get(A + "enabled", "true"), "directBootAware": element.get(A + "directBootAware", "false"),
            "actions": sorted({entry.get(A + "name") for entry in element.findall("intent-filter/action")}),
            "categories": sorted({entry.get(A + "name") for entry in element.findall("intent-filter/category")}),
            "metadata": metadata, "widget": "android.appwidget.provider" in metadata,
            "targetActivity": element.get(A + "targetActivity"), "authorities": element.get(A + "authorities"),
            "readPermission": element.get(A + "readPermission", permission), "writePermission": element.get(A + "writePermission", permission),
            "grantUriPermissions": element.get(A + "grantUriPermissions", "false"),
            "justification": policy[2] if policy else "UNREVIEWED",
        })
    for kind, name in sorted(set(POLICY) - seen):
        issues.append(f"Expected audited component missing: {kind} {name}")
    by_name = {component["name"]: component for component in components}
    alias = by_name.get(PACKAGE + ".platform.HealthPermissionUsageActivity")
    if alias and qualify(alias["targetActivity"] or "") != PACKAGE + ".platform.HealthRationaleActivity":
        issues.append("Health permission usage alias target changed")
    provider = by_name.get("androidx.startup.InitializationProvider")
    if provider and (provider["authorities"] != PACKAGE + ".androidx-startup" or provider["grantUriPermissions"] != "false"):
        issues.append("AndroidX startup provider authorities/URI-grant policy changed")
    for name, resource in (("QuickControlsReceiver", "@xml/quick_controls_widget"), ("SingleActionReceiver", "@xml/single_action_widget")):
        widget = by_name.get(PACKAGE + ".widget." + name)
        if widget and widget["metadata"].get("android.appwidget.provider") != resource:
            issues.append(f"Widget metadata changed for {name}")
    return {
        "manifest": str(path), "manifest_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "package": root.get("package"), "versionName": root.get(A + "versionName"), "versionCode": root.get(A + "versionCode"),
        "sdk": {key.removeprefix(A): value for key, value in (sdk.attrib if sdk is not None else {}).items()},
        "application": {key.removeprefix(A): value for key, value in application.attrib.items()},
        "uses_permissions": [{key.removeprefix(A): value for key, value in node.attrib.items()}
                             for node in root if node.tag.startswith("uses-permission")],
        "defined_permissions": [{key.removeprefix(A): value for key, value in node.attrib.items()} for node in root.findall("permission")],
        "components": sorted(components, key=lambda item: (item["type"], item["name"])),
        "pass": not issues, "issues": issues,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path, help="Built, merged release manifest XML (not src/main)")
    parser.add_argument("--output", type=Path, default=Path("app/build/reports/release-manifest"))
    args = parser.parse_args()
    report = audit(args.manifest)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    rows = report["components"]
    markdown = ["# Built release manifest audit", "", f"Input: `{args.manifest}`", "",
                f"SHA-256: `{report['manifest_sha256']}`", "",
                f"Result: {'PASS' if report['pass'] else 'FAIL'}; {len(rows)} components, "
                f"{sum(row['exported'] for row in rows)} exported, {sum(row['widget'] for row in rows)} widgets.", "",
                "Intent filters describe routing, not authorization. Disabled/resource-controlled components remain audited.", "",
                "| Type | Component | Exported | Permission | Reason |", "| --- | --- | --- | --- | --- |"]
    for row in rows:
        visibility = str(row["exported"]).lower() + (" (default)" if row["exported_declared"] is None else "")
        markdown.append(f"| {row['type']}{' (widget)' if row['widget'] else ''} | `{row['name']}` | {visibility} | `{row['permission'] or 'none'}` | {row['justification']} |")
    markdown.extend(["", "## Requested permissions", ""])
    for item in report["uses_permissions"]:
        markdown.append(f"- `{item['name']}`" + (f" (maxSdkVersion={item['maxSdkVersion']})" if 'maxSdkVersion' in item else ""))
    if report["issues"]:
        markdown.extend(["", "## Failures", ""] + [f"- {issue}" for issue in report["issues"]])
    (args.output / "report.md").write_text("\n".join(markdown) + "\n")
    print(f"Release manifest {'PASS' if report['pass'] else 'FAIL'}: {len(rows)} components; report {args.output / 'report.json'}")
    for issue in report["issues"]:
        print(f"ERROR: {issue}")
    return 0 if report["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
