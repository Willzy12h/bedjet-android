#!/usr/bin/env python3
"""Prepare the exact Robolectric SDKs used by verification without dropping legacy security coverage.

Run once with network access before the offline gate. Files are verified against the committed
SHA-512 pins (published Maven Central checksums), then atomically installed in the local Maven cache.
This does not warm Gradle's separate plugin/runtime cache; run the documented online compile first.
"""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile
from urllib.request import urlopen

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--maven-repository', type=Path, default=Path.home() / '.m2/repository')
parser.add_argument('--check-only', action='store_true', help='Verify the cache without downloading files')
args = parser.parse_args()
pins = json.loads(Path(__file__).with_name('robolectric-sdk-pins.json').read_text())
base = 'https://maven-central.storage-download.googleapis.com/maven2'
for sdk, pin in pins.items():
    version = pin['version']
    relative = Path('org/robolectric/android-all-instrumented') / version
    for extension, expected in pin['sha512'].items():
        target = args.maven_repository / relative / f'android-all-instrumented-{version}.{extension}'
        def valid():
            if not target.is_file(): return False
            with target.open('rb') as source: return hashlib.file_digest(source, 'sha512').hexdigest() == expected
        if not valid():
            if args.check_only: raise SystemExit(f'Missing or corrupt API {sdk} artifact: {target}')
            target.parent.mkdir(parents=True, exist_ok=True)
            temporary = None
            try:
                with urlopen(f'{base}/{relative}/{target.name}', timeout=60) as response, tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as output:
                    temporary = Path(output.name)
                    digest = hashlib.sha512()
                    while chunk := response.read(1024 * 1024):
                        digest.update(chunk); output.write(chunk)
                if digest.hexdigest() != expected: raise SystemExit(f'SHA-512 mismatch: {target.name}')
                temporary.replace(target)
            finally:
                if temporary is not None: temporary.unlink(missing_ok=True)
        print(f'API {sdk}: verified {target.name}')
