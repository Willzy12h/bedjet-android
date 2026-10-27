from pathlib import Path
source = Path('.github/security-fix.py').read_text()
anchor = '    assert text.count(old) == count, (path, \'anchor count\', text.count(old), old[:100])'
assert source.count(anchor) == 1
source = source.replace(anchor, '''    if path.endswith('/ui/features/FeatureSections.kt') and old == '    val scope=rememberCoroutineScope()':
        assert text.count(old) == 2
        p.write_text(text.replace(old, new, 1))
        return
''' + anchor)
exec(compile(source, '.github/security-fix.py', 'exec'))
