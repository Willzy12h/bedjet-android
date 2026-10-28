# Claude review of `gpt/nocturne-redesign`

Scope: the full diff from `claude/nocturne-redesign-foundation` to `gpt/nocturne-redesign` (ca04ff1), checked against
the approved design (`reference/`), `BUILD-PACKETS.md`, `CODE-MAP.md` and `reference/RECONCILIATION.md`. Fixes are on
`claude/nocturne-redesign-review`. This is the implementer-side review. It is **not** the final independent sign-off.

## Fixed

| # | Finding | Fix |
|---|---|---|
| F1 | Bug. `materialScheme()` mapped Material's `surface`, `surfaceContainer`, `surfaceVariant`, `surfaceContainerHighest` and the container roles to the translucent glass tokens. On the dark palettes, menus and floating surfaces that use them by default (the Tonight recovery "More" menu, the busy overlay, default cards) were see-through over content. | The glass tokens are composited over the ground inside `materialScheme()`. In-flow tiles still draw the translucent tokens directly. |
| F2 | Bug. The recovery "More" `DropdownMenu` in `NightRecoveryControls.kt` used the default container, unlike the Profiles menu. | It now uses `menu`, an `outline` border and `ink` text. |
| F4 | New profiles started from a preset had their own after-wake steps replaced by the Cooldown (Winter warm lost its 30-minute Heat step). | Decision: a preset keeps its own after-wake steps; the Cooldown only fills a preset that has none. |
| F3 | Backup compatibility. The redesign adds `nightPalette`, `dayPalette`, `cooldownOfferAnswered` and `Middle.quietFan`, and `encodeDefaults` writes them into every backup. An older app's strict parser rejects unknown fields as "damaged", not as "from a newer version". | `BackupCodec.SCHEMA` is now 3, with a no-op 2 → 3 migration. Older versions now say "update the app first", and the Backups page notes that exported files may not import into older versions. This version still reads schema 1 and 2, and the parser is otherwise unchanged. Tests are in `MigrationsTest`. |
| R2 | One tint strength (0.14/0.16) was used for every tile. | `TintLevel` gives Tile 0.10, On 0.24, Step 0.09 and Profile 0.08. Timeline and editor steps use Step, profile cards Profile, and running quick tiles On. Selected profile cards get a 1.5dp accent border. The contrast test now covers every level over the surface. |
| R7 | Dark menus used the sheet colour, and Forest, Graphite and Plum had invented sheet colours. | Menus use #232740 in every dark palette. Forest, Graphite and Plum now use the design's inherited #171A2C, as `DESIGN-REFERENCE.md` states, until design supplies their own. |
| R8 | The dark `bad` colour was #FF6B6B. | Changed to #FF9B91. Overcast keeps the design's #C62F2A. |
| R9 | Overcast used one hairline (#AAB0BA) for both dividers and borders. | `hairline` is now 12% ink (dividers). The new `edge` token (#AAB0BA) draws tile borders on Overcast, and `outline` (edge, or hairline on the dark palettes) is used for cards, menus and Material `outline`. |
| R13 | The GitHub row was worded "Source code on GitHub". | Changed to the design's "Nocturne on GitHub" / "Project page and downloads". The URL and the failure behaviour are unchanged. |
| R18 | The theme chips were in enum order. | They are now displayed as System / Light / Dark / Scheduled. The stored enum is unchanged. |

## Kept as Codex implemented, with the reason

- **R1** Ember bounds stay at Codex's 30/34/38° (decision; not the design's 30.5/36/41°).
- **R3** Wrapping subtitles: this is a brief decision.
- **R4** 2dp bar corners: the curve is a per-step bar chart, not the design's segmented strip, so 10/3dp corners would round short bars into blobs.
- **R5** No gradients: the stepped bars already show the ramp.
- **R6** Seven dots with a tick in both rows, with a name label.
- **R11** Fan-shape sliders.
- **R14** Widget stripe rows that keep every existing action.
- **R15** Turbo shown as fixed text.
- **R16** "Long heat" wording.
- **R17** Cooldown offer card.
- **R12/R19–R21** Design-only items were not invented.

## Decisions (2026-10-28)

1. Cooldown at 22°, fan 30%, 10 minutes, and the offer card wording: kept as built.
2. Presets keep their own after-wake steps; the Cooldown is added only where a preset has none (F4).
3. Ember bounds: 30/34/38°, unrounded, as Codex built them (R1).
4. Sheet colours: keep the design. Forest, Graphite and Plum use the design's inherited #171A2C. **Open request to design**: own sheet colours for those three palettes.
5. Overcast `bad` #C62F2A: kept.
6. Backup schema 3: accepted. Pre-redesign versions say the backup is from a newer version, and the Backups page warns that exported files may not import into older versions.
7. No hardware, Samsung or TalkBack testing before sign-off. Accepted on the assumption that BLE, alarms and accessibility behave as they did in previous versions.

The final independent sign-off is still outstanding.

## Verification

- Full gate on 0feceab: 1707 app tests, 5 failed. Two tests asserted the old behaviour (translucent widget surface, Cooldown replacing a preset's after-wake) and were updated. `DemoImportPreviewInvalidationTest` failed under full-suite load and passed when re-run alone; it is timing-sensitive.
- The three failing classes pass after the fix. `:app:assembleDebug` and `:app:lintDebug` pass: 0 errors, the same 24 warnings in the same categories as `GPT-HANDOVER.md`.
- Left for Codex: the opt-in screenshot captures (`P8-AUDIT.md`) for Midnight Ink and Overcast, a test-only fix for the timing in `DemoImportPreviewInvalidationTest`, and one full-gate re-run.
