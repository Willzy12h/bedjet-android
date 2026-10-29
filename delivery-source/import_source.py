"""Import one hash-pinned Nocturne Git bundle into one new staging ref.

Runs only in the temporary delivery workflow. It uses checkout's normal Git
credential helper; no signing secret or token value is read by this script.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess

REPOSITORY = "Willzy12h/bedjet-android"
IMPORT_BRANCH = "gpt/nocturne-independent-import-20261029"
BASE_COMMIT = "e4be250fb9f340800fa89aea8e358d5bddbf5054"
AUDITED_COMMIT = "146501a69814f28d3879ca835bb93d2442b455a1"
VERIFIED_COMMIT = "3fd7f043308f10a8524ab29e75c96f5cd2eea292"


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def main():
    assert os.environ["GITHUB_REPOSITORY"] == REPOSITORY
    assert os.environ["GITHUB_REF"] == "refs/heads/" + IMPORT_BRANCH
    root = Path("delivery-source")
    raw = (root / "manifest.json").read_bytes()
    assert hashlib.sha256(raw).hexdigest() == os.environ["EXPECTED_MANIFEST_SHA256"]
    manifest = json.loads(raw)
    target = manifest["target_commit"]
    assert re.fullmatch("[0-9a-f]{40}", target)
    assert manifest["repository"] == REPOSITORY
    assert manifest["prerequisite_commit"] == BASE_COMMIT
    assert manifest["audited_commit"] == AUDITED_COMMIT
    assert manifest["verified_application_commit"] == VERIFIED_COMMIT
    assert manifest["bundle_ref"] == "refs/heads/gpt/nocturne-independent-fixes"
    staging = "refs/heads/gpt/nocturne-imported-source-" + target[:12]
    assert manifest["staging_ref"] == staging
    assert run("git", "remote", "get-url", "origin") in (
        "https://github.com/" + REPOSITORY,
        "https://github.com/" + REPOSITORY + ".git",
    )

    bundle = Path(os.environ["RUNNER_TEMP"]) / "nocturne-verified-source.bundle"
    digest = hashlib.sha256()
    total = 0
    expected_names = {"manifest.json", "import_source.py"}
    with bundle.open("xb") as output:
        for index, part in enumerate(manifest["parts"], 1):
            name = f"source-part-{index:04d}.bin"
            assert part["file"] == name
            expected_names.add(name)
            data = (root / name).read_bytes()
            assert len(data) == part["bytes"] and 0 < len(data) <= 2 * 1024 * 1024
            assert hashlib.sha256(data).hexdigest() == part["sha256"]
            digest.update(data)
            total += len(data)
            output.write(data)
    assert {path.name for path in root.iterdir()} == expected_names
    assert total == manifest["bundle_bytes"]
    assert digest.hexdigest() == manifest["bundle_sha256"]
    assert run("git", "bundle", "list-heads", str(bundle)) == target + " " + manifest["bundle_ref"]
    subprocess.run(["git", "bundle", "verify", str(bundle)], check=True)
    subprocess.run(["git", "bundle", "unbundle", str(bundle)], check=True)
    assert run("git", "rev-parse", target + "^{tree}") == manifest["target_tree"]
    for ancestor in (BASE_COMMIT, AUDITED_COMMIT, VERIFIED_COMMIT):
        subprocess.run(["git", "merge-base", "--is-ancestor", ancestor, target], check=True)
    assert run("git", "rev-list", "--reverse", target, "^" + BASE_COMMIT).splitlines() == manifest["imported_commits"]
    subprocess.run(["git", "fsck", "--connectivity-only", "--no-reflogs", target], check=True)

    source = hashlib.sha256()
    paths = run("git", "ls-tree", "-r", "--name-only", target).splitlines()
    for name in sorted(paths):
        if name.startswith(("app/", "core/", "gradle/", ".github/")) or name in (
            "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
            "docs/redesign/verification.init.gradle",
        ):
            contents = subprocess.check_output(["git", "show", target + ":" + name])
            source.update(name.encode() + b"\0" + contents + b"\0")
    assert source.hexdigest() == manifest["source_sha256"]

    old = subprocess.run(["git", "ls-remote", "--exit-code", "--heads", "origin", staging],
                         text=True, capture_output=True)
    if old.returncode == 0:
        assert old.stdout.strip() == target + "\t" + staging, "Staging ref already has another identity"
        print("Exact staging ref already present; no remote change required")
    else:
        assert old.returncode == 2, "Unable to establish remote ref absence"
        subprocess.run(["git", "push", "--porcelain", "--atomic", "origin", target + ":" + staging], check=True)
    assert run("git", "ls-remote", "--heads", "origin", staging) == target + "\t" + staging
    result = {"status": "imported", "repository": REPOSITORY,
              "source_commit": target, "source_tree": manifest["target_tree"],
              "source_sha256": source.hexdigest(), "staging_ref": staging,
              "bundle_sha256": digest.hexdigest(), "bundle_bytes": total,
              "commits": manifest["imported_commits"], "force_push": False,
              "signing_secrets_used": False}
    Path(os.environ["RUNNER_TEMP"], "nocturne-import-result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
