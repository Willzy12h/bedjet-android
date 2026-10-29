#!/usr/bin/env python3
"""Preserve a successful native capture run, with hashes and contact sheets.

Requires Pillow. Run after the documented 41-case opt-in command, supplying its
process receipt and copied JUnit reports. Old files left in the output directory
are explicitly listed and excluded by the run's timestamps, never silently mixed
into new evidence. PNGs are copied unchanged; contact sheets are thumbnails only.
"""
import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import textwrap
import xml.etree.ElementTree as ET
import zipfile

from PIL import Image, ImageDraw, ImageFont, ImageOps


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--captures", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--reports", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    receipt = json.loads(args.receipt.read_text())
    assert receipt["exit_code"] == 0, "Only a successful run can be delivered as final evidence"
    assert receipt["source_tree_sha256"] == receipt["source_tree_sha256_after"], "Source changed during captures"
    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    for path in args.reports.glob("TEST-*.xml"):
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
    assert totals == {"tests": 41, "failures": 0, "errors": 0, "skipped": 0}, totals
    start = datetime.fromisoformat(receipt["started"]).timestamp()
    finish = datetime.fromisoformat(receipt["finished"]).timestamp()
    args.output.mkdir(parents=True, exist_ok=True)
    contacts = args.output / "contact-sheets"
    contacts.mkdir(exist_ok=True)
    rows, excluded, unique = [], [], {}
    for path in sorted(args.captures.rglob("*.png")):
        name = path.relative_to(args.captures).as_posix()
        if not start <= path.stat().st_mtime <= finish + 1:
            excluded.append(name)
            continue
        data = path.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        with Image.open(path) as im:
            im.load()  # Corrupt or incomplete PNGs must fail packaging.
            width, height = im.size
        row = {"file": name, "width_px": width, "height_px": height,
               "sha256": digest, "bytes": len(data),
               "modified_utc": datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat()}
        rows.append(row)
        unique.setdefault(digest, row)
    assert rows, "No current-run images found"

    # Keep individual Git evidence archives below the 50 MiB large-file warning.
    packs, current, current_size = [], [], 0
    for row in rows:
        if current and current_size + row["bytes"] > 32 * 1024 * 1024:
            packs.append(current)
            current, current_size = [], 0
        current.append(row)
        current_size += row["bytes"]
    if current:
        packs.append(current)
    for number, pack in enumerate(packs, 1):
        archive = f"captures-{number:02d}.zip"
        with zipfile.ZipFile(args.output / archive, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zipped:
            for row in pack:
                zipped.write(args.captures / row["file"], "remediation/" + row["file"])
                row["archive"] = archive
            if number == len(packs):
                for path in sorted(args.captures.rglob("*.json")):
                    if start <= path.stat().st_mtime <= finish + 1:
                        zipped.write(path, "remediation/" + path.relative_to(args.captures).as_posix())

    try:
        font = ImageFont.truetype("DejaVuSans.ttf", 12)
        title_font = ImageFont.truetype("DejaVuSans.ttf", 19)
    except OSError:
        font = title_font = ImageFont.load_default()
    entries = list(unique.values())
    cells_per_page, columns = 12, 4
    cell_w, cell_h, margin, title_h = 260, 620, 14, 48
    pages = (len(entries) + cells_per_page - 1) // cells_per_page
    for page in range(pages):
        canvas = Image.new("RGB", (columns * cell_w + margin * 2, 3 * cell_h + title_h + margin), "#eef0f4")
        draw = ImageDraw.Draw(canvas)
        draw.text((margin, 12), f"Nocturne remediation • native captures • {page + 1}/{pages}", fill="#172033", font=title_font)
        for cell, row in enumerate(entries[page * cells_per_page:(page + 1) * cells_per_page]):
            x = margin + cell % columns * cell_w
            y = title_h + cell // columns * cell_h
            with Image.open(args.captures / row["file"]) as im:
                preview = ImageOps.contain(im.convert("RGBA"), (cell_w - 16, cell_h - 100))
                canvas.paste(preview, (x + (cell_w - 16 - preview.width) // 2, y), preview)
            label = "\n".join(textwrap.wrap(row["file"], width=34, break_long_words=True))
            draw.multiline_text((x, y + cell_h - 94), label, fill="#172033", font=font, spacing=2)
            row["contact_sheet"] = f"contact-sheets/contact-{page + 1:02d}.jpg"
            row["contact_cell"] = cell + 1
        canvas.save(contacts / f"contact-{page + 1:02d}.jpg", quality=88, optimize=True)
    for row in rows:
        representative = unique[row["sha256"]]
        row["contact_sheet"] = representative["contact_sheet"]
        row["contact_cell"] = representative["contact_cell"]
    with (args.output / "capture-manifest.csv").open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    summary = {"receipt": receipt, "junit": totals, "pngs": len(rows),
               "unique_pngs": len(unique), "contact_sheets": pages, "archives": len(packs),
               "excluded_previous_run_files": excluded}
    (args.output / "capture-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps({key: value for key, value in summary.items() if key not in ("receipt", "excluded_previous_run_files")}, indent=2))
    print(f"Excluded {len(excluded)} explicitly listed files from earlier runs")


if __name__ == "__main__":
    main()
