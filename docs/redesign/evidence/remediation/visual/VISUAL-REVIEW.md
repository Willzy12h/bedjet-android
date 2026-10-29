# Native visual self-review

Application/test source: `c9704ee91b4d5642a90f7cf0b7f9d22b9ce619dd`.
Capture source digest: `dbe37daa47686e6ff20c17d58191d5e7aca3738eb52cbd9e4457ed2834a8c4e8`.
Capture run: `captures-final-remediation`, 29 October 2026, 04:07:58–04:11:13 UTC; actual exit 0; 41 tests passed with no skips or failures.

## Review method

The full contact-sheet review covered Tonight, Profiles, the editor and its interactive sheets, all Settings destinations, History/check-in, setup/recovery, running-night confirmations and the actual composed widgets. Both normal size and 320dp with 200% text were inspected in Midnight Ink and Overcast, together with the seven-palette screen/sheet/drag coverage. The 45 numbered contact sheets index all 536 unique images in the 1,003-PNG capture set.

The last application changes repaired selected-state contrast and History actions, followed by the fresh-ViewModel setup-request fix. Comparing the previous reviewed capture manifest with this final manifest found 815 byte-identical PNGs and 188 changed PNGs representing 120 distinct images. All ten `review-delta/delta-*.jpg` sheets were inspected, alongside refreshed contact sheets 19–24, 33–37 and 39–43 and the final full-resolution History suggestion and Middle sheets. The unchanged images retain the prior inspection; this avoids pretending repeated backgrounds are new independent evidence. `review-delta/changed-captures.csv` preserves both hashes. Some changes are fixture snapshot dates or state, not code defects.

All ten `design-pairings/pairing-*.jpg` sheets were inspected against the 59 images in `reference/screenshots/as-published/` and the approved HTML. `DESIGN-CAPTURE-PAIRING.md` and the CSV distinguish a direct screen correspondence from a token/decision reference, illustrative wizard or widget mismatch. The overview sheet is only a convenient selection; it does not replace the full evidence.

## Results

- History Apply and Keep actions are vertically separated in both palettes at 320dp/200%. Their regression tests also check 48dp targets, full glyph bounds and actual Keep/Apply persistence outcomes.
- Overcast Middle values keep the complete °C unit; selected shape outlines, Add-step borders and selected chips remain distinguishable from their adjacent rendered fills. Secondary mode-limit text uses the selected readable foreground.
- Menus and floating surfaces are opaque. Forest, Graphite and Plum sheets use the agreed `#171A2C`; the Overcast drag row uses its light surface. Overcast decorative borders retain their approved hierarchy.
- Four independent native tint tests verify .10, .24, .09 and .08 against sampled bases across all seven palettes. The remembered-device duplicate tint is removed.
- Long labels wrap and scroll. At the most constrained size, a few names or long words wrap within a word; they are not truncated. Native layout/semantics tests supplement visual inspection. Top and bottom captures show real scroll viewports, so a label cut at a viewport edge is not by itself evidence of unreachable content.
- Existing receipt/safety explanations, profile sample data, plain versus illustrative gradient segments, platform chrome and functional widget actions differ from the static HTML. The pairing table and P8 audit explain these differences. No pixel-perfect parity is claimed.

No further actionable visual defect was identified after UI-19–22 were reproduced and repaired. This is the implementer's self-review, not Claude's independent sign-off or physical device validation. Physical BedJet, Samsung, TalkBack, installed widgets, keyboard/insets and overnight battery behaviour remain untested.
