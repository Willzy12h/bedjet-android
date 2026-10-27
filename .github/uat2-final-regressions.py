"""Retain all assertions while migrating the six observed full-suite failures."""
from pathlib import Path
T = Path('app/src/test/kotlin/io/github/willzy12h/nocturne/ui')
for name in ('AlarmExplanationTest.kt', 'AlarmSettingsNavigationTest.kt'):
    p = T / name
    text = p.read_text()
    old = 'Next alarm 06:30 · Unknown app — used'
    assert text.count(old) == 1, name
    p.write_text(text.replace(old, 'Next alarm 06:30 · Phone alarm (app not identified) — used'))
p = T / 'U06PrimaryLayoutTest.kt'
text = p.read_text()
old = 'ui.checkLayout(ui.compose.onNodeWithText(full), "Long owner and queue description")'
assert text.count(old) == 1
p.write_text(text.replace(old, 'ui.checkLayout(ui.compose.onNodeWithText(full), "Long owner and queue description", scroll = false)'))
print('Updated two exact alarm labels and the non-scrolling overlay fixture; all policy, bounds and text assertions retained.')
