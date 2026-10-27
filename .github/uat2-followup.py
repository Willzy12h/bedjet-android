from pathlib import Path
A='app/src/main/kotlin/io/github/willzy12h/nocturne/'
T='app/src/test/kotlin/io/github/willzy12h/nocturne/'
def edit(path, old, new, count=1):
    p=Path(path);s=p.read_text();assert s.count(old)==count,(path,s.count(old),old[:80]);p.write_text(s.replace(old,new))

# A standalone, initially empty fixture needs a stable host viewport just like real Tonight.
edit(T+'ui/UatLayoutAndSetupTest.kt',
     'NocturneTheme(model.settings.value, model.now()) { SetupChecklistCard(model) }',
     'NocturneTheme(model.settings.value, model.now()) { Box(Modifier.fillMaxSize()) { SetupChecklistCard(model) } }')
edit(T+'ui/UatLayoutAndSetupTest.kt',
     'assertFalse("No clipped or ellipsised label: $label", results.single().hasVisualOverflow)',
     'assertFalse("No clipped label: $label; size=${results.single().size}; lines=${results.single().lineCount}; width=${results.single().didOverflowWidth}; height=${results.single().didOverflowHeight}", results.single().hasVisualOverflow)')
edit(A+'ui/QuickDefaultsSection.kt',
     'Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)',
     'Text(label, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)')

# Review inventory: source/test references only, never app data, credentials or runner settings.
for p in sorted(Path('app/src').rglob('*.kt')):
    lines=p.read_text().splitlines()
    if any('fun reportedQuickAction' in line for line in lines):
        print('\n=== Existing receipt policy:',p,'===')
        for i,line in enumerate(lines,1): print(f'{i}: {line}')
    if '/test/' in str(p):
        matches=[(i,line) for i,line in enumerate(lines,1) if any(x in line for x in
            ('R.string.feature_warm','expectedBoostActionLabel','boostLabel(', 'quick-active-', 'hasText("Warm")'))]
        for i,line in matches: print(f'UI selector {p}:{i}: {line}')
print('UAT follow-up applied; all behavioural assertions remain required.')
