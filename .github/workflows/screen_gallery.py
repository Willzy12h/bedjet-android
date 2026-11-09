#!/usr/bin/env python3
"""Builds the browsable screen gallery from the Robolectric gallery capture.

Input is the capture folder written by `SCREENSHOT_TAG=gallery` (ScreenshotCapture.gallery and
WidgetCaptureTest). Output is a folder holding README.md and screens/*.png, which the gallery
workflow publishes as the `gallery` branch.
"""
import argparse
import html
import shutil
from pathlib import Path

COLUMNS = 3
WIDGET_ORDER = ["2x1", "2x2", "3x2", "4x2", "4x3", "4x4"]
TILE_ORDER = ["TURBO", "HEAT", "COOL", "DRY", "BOOST", "START", "OFF"]


def read_captions(path):
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        name, section, caption = line.split("\t", 2)
        rows.append((name, section, caption))
    return rows


def table(cells):
    out = ["<table>"]
    for start in range(0, len(cells), COLUMNS):
        out.append("<tr>")
        for image, caption, width in cells[start:start + COLUMNS]:
            out.append(f'<td align="center" valign="top" width="{100 // COLUMNS}%">'
                       f'<img src="{html.escape(image)}" width="{width}" alt="{html.escape(caption)}"><br>'
                       f"<sub>{html.escape(caption)}</sub></td>")
        out.append("</tr>")
    out.append("</table>")
    return "\n".join(out)


def build(capture, output, commit, repository):
    gallery = capture / "gallery"
    rows = read_captions(gallery / "captions.tsv")
    if not rows:
        raise SystemExit("captions.tsv is empty")
    if output.exists():
        shutil.rmtree(output)
    screens = output / "screens"
    screens.mkdir(parents=True)

    sections = {}
    for name, section, caption in rows:
        source = gallery / f"{name}.png"
        if not source.is_file():
            raise SystemExit(f"missing capture {source}")
        shutil.copyfile(source, screens / source.name)
        sections.setdefault(section, []).append((f"screens/{source.name}", caption, 260))

    widgets = []
    for kind, order, label in (("quick", WIDGET_ORDER, "Quick controls {} "),
                               ("night", WIDGET_ORDER, "During a night {} "),
                               ("tile", TILE_ORDER, "1 × 1 tile: {}")):
        for key in order:
            source = capture / f"widget-dark-{kind}-{key}.png"
            if source.is_file():
                shutil.copyfile(source, screens / source.name)
                caption = label.format(key.replace("x", " × ") if kind != "tile" else key.title()).strip()
                widgets.append((f"screens/{source.name}", caption, 200 if kind != "tile" else 110))
    if widgets:
        sections["Home-screen widgets"] = widgets

    short = commit[:7]
    lines = [
        "# Nocturne screen gallery",
        "",
        "Every tab, page, sheet and dialog in the default **Midnight Ink** night palette, "
        "rendered with the demo BedJet so no real device is involved.",
        "",
        f"Generated automatically from [`{short}`](https://github.com/{repository}/commit/{commit}) "
        f"on `main`. Each push that changes the app re-renders this page. "
        f"[Back to the project](https://github.com/{repository}#readme).",
        "",
        "These are host renders (Robolectric) at 411 dp × 891 dp, not photographs of a phone, "
        "so fonts and shadows can differ slightly from a real device.",
        "",
        "## Contents",
        "",
    ]
    for section in sections:
        anchor = "".join(c for c in section.lower().replace(" ", "-") if c.isalnum() or c == "-")
        lines.append(f"- [{section}](#{anchor})")
    for section, cells in sections.items():
        lines += ["", f"## {section}", "", table(cells)]
    lines.append("")
    (output / "README.md").write_text("\n".join(lines), encoding="utf-8")
    print(f"{sum(len(c) for c in sections.values())} images in {len(sections)} sections")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--repository", required=True)
    args = parser.parse_args()
    build(args.capture, args.output, args.commit, args.repository)


if __name__ == "__main__":
    main()
