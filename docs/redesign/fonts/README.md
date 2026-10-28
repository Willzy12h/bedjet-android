# Font provenance

The supplied Nocturne design reference `source/fonts/` contains Google Fonts WOFF2
subsets. The app uses Figtree 400, 500, 600 and 700 and Bricolage Grotesque 700.
For each weight, the `*-latin.woff2` and `*-latin-ext.woff2` subsets were instantiated
as static fonts and merged. Bricolage's optical size axis was set to 14; Figtree's
weight axis was set to the corresponding weight. The resulting fonts cover 347 and
374 Unicode code points respectively; the platform supplies fallback for other glyphs.
The files are under `app/src/main/res/font/`.

| Asset | Bytes | SHA-256 |
|---|---:|---|
| `figtree_regular.ttf` | 37,184 | `9618ad96f5747e1b0850f67c5e295feca07051b9d6ee5fc84c052054e756e74e` |
| `figtree_medium.ttf` | 37,176 | `33f244806278e6a05ded195c4f107d34c5f670d8fa6aabd8e04a3675c4daae35` |
| `figtree_semibold.ttf` | 37,260 | `fad460e75464c49883b8c2af1931b7999db417fc70529a1a801f8d96b8ea56d1` |
| `figtree_bold.ttf` | 37,244 | `ce57fe5a7ceaeaeb809faa7bde399926133a1a5c5a1d85b4607e1a2fb0bfd475` |
| `bricolage_bold.ttf` | 70,912 | `826fc2c807d1cfbbbd23d1d760af191a3d0c4e80906a600ae53570a22fdf010b` |

The upstream Google Fonts OFL files are retained here as `FIGTREE-OFL.txt` (Git blob
`80b12bb3d0c0942676657ef6d125daf4a1e1346d`) and `BRICOLAGE-OFL.txt` (Git blob
`a397658a5c4ab27c349964553b7caca61a4346f6`). Neither states a reserved font name.
Both font copyright notices and the OFL must also be available in the app distribution.

To reproduce, use Python fontTools with its WOFF2/Brotli support and the supplied
reference bundle. For each pair, instantiate and save the subsets, then merge them:

```python
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.merge import Merger

# source is the supplied reference's source/fonts directory; family and weight
# are Figtree with 400/500/600/700, or BricolageGrotesque with 700.
parts = []
for subset in ("latin", "latin-ext"):
    font = TTFont(source / f"{family}-{weight}-{subset}.woff2")
    axes = {"wght": weight}
    if family == "BricolageGrotesque":
        axes["opsz"] = 14
    instantiateVariableFont(font, axes, inplace=True)
    font.flavor = None
    part = f"/tmp/{family}-{weight}-{subset}.ttf"
    font.save(part)
    parts.append(part)
merged = Merger().merge(parts)
merged.save(output_path)
```

The exact output checksum may vary with fontTools version and timestamp settings;
compare glyph coverage and render as well as hashes when regenerating.
