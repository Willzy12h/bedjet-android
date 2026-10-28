# Claude review of `gpt/nocturne-redesign`

Scope: the full diff from `claude/nocturne-redesign-foundation` to `gpt/nocturne-redesign` (ca04ff1), checked against
the approved design (`reference/`), `BUILD-PACKETS.md`, `CODE-MAP.md` and `reference/RECONCILIATION.md`. Fixes are on
`claude/nocturne-redesign-review`. This is the implementer-side review. It is **not** the final independent sign-off.

## Fixed

| # | Finding | Fix |
|---|---|---|
| F1 | Bug. `materialScheme()` mapped Material's `surface`, `surfaceContainer`, `surfaceVariant`, `surfaceContainerHighest` and the container roles to the translucent glass tokens. On the dark palettes, menus and floating surfaces that use them by default (the Tonight recovery "More" menu, the busy overlay, default cards) were see-through over content. | The glass tokens are composited over the ground inside `materialScheme()`. In-flow tiles still draw the translucent tokens directly. |
| F2 | Bug. The recovery "More" `DropdownMenu` in `NightRecoveryControls.kt` used the default container, unlike the Profiles menu. | It now uses `menu`, an `outline` border and `ink` text. |
| F3 | Backup compatibility. The redesign adds `nightPalette`, `dayPalette`, `cooldownOfferAnswered` and `Middle.quietFan`, and `encodeDefaults` writes them into every backup. An older app's strict parser rejects unknown fields as "damaged", not as "from a newer version". | `BackupCodec.SCHEMA` is now 3, with a no-op 2 → 3 migration. Older versions now say "update the app first". This version still reads schema 1 and 2, and the parser is otherwise unchanged. Tests are in `MigrationsTest`. |
| R1 | The Ember bounds (30/34/38°) disagreed with the design's scale. | Changed to 30.5/36/41° (half-degrees 61/72/82), as drawn. `HeatScaleTest` is updated. |
| R2 | One tint strength (0.14/0.16) was used for every tile. | `TintLevel` gives Tile 0.10, On 0.24, Step 0.09 and Profile 0.08. Timeline and editor steps use Step, profile cards Profile, and running quick tiles On. Selected profile cards get a 1.5dp accent border. The contrast test now covers every level over the surface. |
| R7 | Dark menus used the sheet colour. | They now use #232740 in every dark palette. The invented sheet colours are unchanged and still need sign-off. |
| R8 | The dark `bad` colour was #FF6B6B. | Changed to #FF9B91. Overcast keeps #C62F2A, which is an assumption. |
| R9 | Overcast used one hairline (#AAB0BA) for both dividers and borders. | `hairline` is now 12% ink (dividers). The new `edge` token (#AAB0BA) draws tile borders on Overcast, and `outline` (edge, or hairline on the dark palettes) is used for cards, menus and Material `outline`. |
| R13 | The GitHub row was worded "Source code on GitHub". | Changed to the design's "Nocturne on GitHub" / "Project page and downloads". The URL and the failure behaviour are unchanged. |
| R18 | The theme chips were in enum order. | They are now displayed as System / Light / Dark / Scheduled. The stored enum is unchanged. |

## Kept as Codex implemented, with the reason

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

## Needs sign-off

- Cooldown at 22°, and the offer card's wording.
- New profiles started from a preset get the Cooldown in place of the preset's own after-wake. This follows the design's "every new profile" literally. Winter warm's after-wake is a 30-minute Heat step, so check that this is what you want.
- Whether 30.5° is Hot (R1).
- The sheet colours invented for the dark palettes (R7), and Overcast's `bad` (R8).
- The policy that backups are not readable by pre-redesign versions (F3).
- Everything listed under Limits in `GPT-HANDOVER.md`: there has been no hardware, Samsung, TalkBack or on-device verification.
