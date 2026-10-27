#!/usr/bin/env python3
"""Inventory real Robolectric captures and compare the existing, historical PNGs.

Coverage/fixture mismatches fail. Pixel differences are measured and reported,
not accepted as a visual review or hidden behind a similarity threshold.
Requires Pillow; does not create, replace or reinterpret missing baselines.
"""

import argparse
import hashlib
import html
import json
from pathlib import Path

from PIL import Image, ImageChops, ImageEnhance, ImageStat


LOOKS = ("dark", "light", "simple-dark", "simple-light", "combined-320dp-200pct")
SCREENS = (
    "01-tonight", "01b-tonight-lower", "02-profiles", "03-remote",
    "04-history", "05-settings", "06-tonight-running",
)
GAP_LOOKS = LOOKS[:4] + tuple(f"compact-{look}" for look in LOOKS[:4])
GAP_SCREENS = (
    "01-slot-pin", "02-wake-expiry", "03-wake-picker", "04-resume",
    "05-checkin-dialog", "06-checkin-suggestion", "07-setup-chooser", "08-unreadable-banner",
)
GAP_DIALOG_SCREENS = ("01-slot-pin", "03-wake-picker", "05-checkin-dialog", "06-checkin-suggestion")
EXPECTED = {f"{look}-{screen}.png" for look in LOOKS for screen in SCREENS} | {
    "features-01-manual-connection-release.png", "features-02-platform-settings.png",
    "features-03-history-export.png",
} | {f"gaps-{look}-{screen}.png" for look in GAP_LOOKS for screen in GAP_SCREENS}
EXPECTED_BASELINE = {f"{look}-{screen}.png" for look in LOOKS[:4] for screen in SCREENS}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_inventory(actual, baseline):
    errors = [f"Missing after capture: {name}" for name in sorted(EXPECTED - actual)]
    errors += [f"Unexpected after capture (possibly stale): {name}" for name in sorted(actual - EXPECTED)]
    errors += [f"Missing historical baseline: {name}" for name in sorted(EXPECTED_BASELINE - baseline)]
    errors += [f"Unexpected historical baseline: {name}" for name in sorted(baseline - EXPECTED_BASELINE)]
    return errors


def validate_metadata(path, size):
    data = json.loads(path.read_text())
    gap = path.name.startswith("gaps-")
    look = path.name.removeprefix("gaps-").removeprefix("compact-")
    combined = path.name.startswith(("combined-320dp-200pct-", "gaps-compact-"))
    setup = gap and path.stem.endswith(("07-setup-chooser", "08-unreadable-banner"))
    dialog = gap and path.stem.endswith(GAP_DIALOG_SCREENS)
    expected = {
        "renderer": "Robolectric native graphics", "sdk": 34, "tag": "after",
        "clockInstant": "2026-10-22T22:30:00Z", "clockZone": "Europe/London",
        "screenWidthDp": 320 if combined else 411, "screenHeightDp": 891,
        "fontScale": 2.0 if combined else 1.0, "densityDpi": 480,
        "pixelWidth": size[0], "pixelHeight": size[1], "demoMode": not setup,
        "theme": "LIGHT" if look.startswith(("light-", "simple-light-")) else "DARK",
        "simpleView": look.startswith("simple-"),
    }
    errors = [f"{path.name}: {key} expected {value!r}, found {data.get(key)!r}"
              for key, value in expected.items() if data.get(key) != value]
    expected_size = (expected["screenWidthDp"] * 3, expected["screenHeightDp"] * 3)
    # Gap dialogs capture their own decor view. Its content height can change with text;
    # configuration, pixel metadata, full screen width and the screen-height bound still apply.
    # Legacy and gap activity captures retain exact full-screen dimensions.
    if dialog:
        if size[0] != expected_size[0] or not 0 < size[1] <= expected_size[1]:
            errors.append(f"{path.name}: dialog image size expected width {expected_size[0]} and height 1..{expected_size[1]}, found {size}")
    elif size != expected_size:
        errors.append(f"{path.name}: image size expected {expected_size}, found {size}")
    return data, errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=Path("docs/screenshots/baseline"))
    parser.add_argument("--after", type=Path, default=Path("app/build/screenshots/after"))
    parser.add_argument("--output", type=Path, default=Path("app/build/reports/release-screenshots"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    compared = args.output / "comparisons"
    compared.mkdir(exist_ok=True)
    actual = {path.name for path in args.after.glob("*.png")}
    baseline = {path.name for path in args.baseline.glob("*.png")}
    errors = validate_inventory(actual, baseline)

    rows = []
    panels = []
    for name in sorted(actual & EXPECTED):
        source = args.after / name
        before = args.baseline / name
        with Image.open(source) as opened:
            after = opened.convert("RGB")
        row = {"name": name, "after_size": list(after.size), "after_sha256": sha256(source)}
        try:
            row["fixture"], fixture_errors = validate_metadata(source.with_suffix(".json"), after.size)
            errors.extend(fixture_errors)
        except (OSError, ValueError) as error:
            errors.append(f"{name}: invalid/missing capture metadata: {error}")

        # The historical baseline was stored at roughly half the renderer size.
        # Downsample only the review copy; retain full-size after PNGs unchanged.
        if before.exists():
            with Image.open(before) as opened:
                old = opened.convert("RGB")
            resized = after.resize(old.size, Image.Resampling.LANCZOS)
            delta = ImageChops.difference(old, resized)
            channels = delta.split()
            largest = ImageChops.lighter(ImageChops.lighter(channels[0], channels[1]), channels[2])
            histogram = largest.histogram()
            pixels = old.width * old.height
            row.update({
                "status": "COMPARED", "baseline_size": list(old.size),
                "baseline_sha256": sha256(before),
                "changed_pixels": pixels - histogram[0],
                "changed_percent": round(100 * (pixels - histogram[0]) / pixels, 4),
                "changed_over_8_percent": round(100 * sum(histogram[9:]) / pixels, 4),
                "mean_absolute_rgb_difference": round(sum(ImageStat.Stat(delta).mean) / 3, 4),
                "difference_bounds": largest.getbbox(),
            })
            old.save(compared / f"before-{name}")
            resized.save(compared / f"after-{name}")
            ImageEnhance.Brightness(delta).enhance(5).save(compared / f"diff-{name}")
            panels.append(f'<section><h2>{html.escape(name)}</h2><p>{row["changed_percent"]}% of normalized pixels differ; '
                          f'{row["changed_over_8_percent"]}% differ by more than 8/255 in any RGB channel.</p>'
                          '<div class="comparison">' + ''.join(
                              f'<figure><figcaption>{label}</figcaption><img src="comparisons/{prefix}-{name}" alt="{label} {name}"></figure>'
                              for prefix, label in (("before", "Historical baseline"), ("after", "After (normalized)"), ("diff", "Absolute difference ×5"))) + '</div></section>')
        else:
            row["status"] = "MISSING_BASELINE" if name in EXPECTED_BASELINE else "UNBASELINED"
            explanation = ("MISSING_BASELINE: this historical counterpart is required and its absence fails the evidence gate."
                           if name in EXPECTED_BASELINE else
                           "UNBASELINED: no corresponding historical PNG exists. This is a new after capture, not a before/after comparison.")
            after.resize((after.width // 2, after.height // 2), Image.Resampling.LANCZOS).save(compared / f"after-{name}")
            panels.append(f'<section><h2>{html.escape(name)}</h2><p>{explanation}</p>'
                          f'<img src="comparisons/after-{name}" alt="After capture {name}"></section>')
        rows.append(row)

    report = {
        "renderer": "Robolectric native graphics; demo BedJet; not physical-device evidence",
        "comparison": "After resized to historical baseline dimensions using Pillow LANCZOS. Differences include source, fixture, clock, state and resampling changes; no automatic visual pass threshold.",
        "expected_after": len(EXPECTED), "actual_after": len(actual),
        "baseline_count": len(baseline), "compared": sum(r["status"] == "COMPARED" for r in rows),
        "unbaselined": sum(r["status"] == "UNBASELINED" for r in rows),
        "missing_historical_baselines": len(EXPECTED_BASELINE - baseline),
        "coverage_and_fixture_pass": not errors, "errors": errors, "captures": rows,
    }
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    markdown = ["# Release screenshot comparison", "", report["renderer"], "", report["comparison"], "",
                f"Coverage/fixture check: {'PASS' if not errors else 'FAIL'}. Expected {len(EXPECTED)}, found {len(actual)}; "
                f"compared {report['compared']}; unbaselined {report['unbaselined']}.", "",
                "| Capture | Comparison | Changed pixels (%) | >8/255 (%) |", "| --- | --- | ---: | ---: |"]
    for row in rows:
        markdown.append(f"| `{row['name']}` | {row['status']} | {row.get('changed_percent', '—')} | {row.get('changed_over_8_percent', '—')} |")
    if errors:
        markdown.extend(["", "## Errors", ""] + [f"- {error}" for error in errors])
    (args.output / "report.md").write_text("\n".join(markdown) + "\n")
    (args.output / "index.html").write_text(
        '<!doctype html><html lang="en"><meta charset="utf-8"><title>Release screenshot comparison</title>'
        '<style>body{font:16px system-ui;margin:24px;background:#eee;color:#111}section{margin:40px 0;border-top:1px solid #aaa}'
        '.comparison{display:flex;gap:16px;align-items:flex-start}figure{margin:0;flex:1;min-width:0}img{width:100%;max-width:420px;height:auto}'
        'figcaption{font-weight:bold;margin:8px 0}</style><h1>Release screenshot comparison</h1><p>'
        + html.escape(report["renderer"]) + '</p><p>' + html.escape(report["comparison"]) + '</p>'
        + ''.join(panels) + '</html>')
    print(f"Screenshot coverage/fixture {'PASS' if not errors else 'FAIL'}: {len(actual)}/{len(EXPECTED)} captures, "
          f"{report['compared']} compared, {report['unbaselined']} unbaselined. Report: {args.output / 'report.json'}")
    for error in errors:
        print(f"ERROR: {error}")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
