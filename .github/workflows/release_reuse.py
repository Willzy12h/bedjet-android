#!/usr/bin/env python3
"""Find the pull request run that already passed every check on the exact tree merged into main.

A merge into main normally runs every check again. When the merged code is byte-for-byte the tree that a pull request
from this repository already passed, the merge doesn't repeat them; when it releases, it signs and publishes that
run's unsigned APK, which is the same build (--require-apk). Every condition below must hold; anything else, including an
error talking to GitHub, means "build it again here" and never skips a check without that evidence.
"""

import argparse
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

sys.dont_write_bytecode = True
from release_artifacts import sha256, version_code

WORKFLOW_PATH = ".github/workflows/build.yml"
# Every job of the pull request run that must have passed. Their names are this workflow's job names.
REQUIRED_JOBS = ("prepare", "gate", "release-build", "screenshots", "build")
SHA = re.compile(r"[0-9a-f]{40}")


def unsigned_artifact(run_id, attempt):
    return f"nocturne-unsigned-{run_id}-{attempt}"


class GitHub:
    def __init__(self, repository):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ValueError("Invalid GitHub repository")
        self.repository = repository

    def get(self, path):
        return json.loads(subprocess.check_output(["gh", "api", f"repos/{self.repository}/{path}"], text=True))

    def download(self, run_id, name, destination):
        subprocess.run(["gh", "run", "download", str(run_id), "--repo", self.repository, "--name", name,
                        "--dir", str(destination)], check=True)

    def tree(self, commit):
        return self.get(f"git/commits/{commit}")["tree"]["sha"]


def merged_pull_request(client, repository, commit):
    candidates = [pr for pr in client.get(f"commits/{commit}/pulls")
                  if pr.get("merged_at") and pr.get("merge_commit_sha") == commit
                  and (pr.get("base") or {}).get("ref") == "main"
                  and ((pr.get("head") or {}).get("repo") or {}).get("full_name") == repository]
    if len(candidates) != 1:
        raise LookupError(f"expected one pull request from {repository} merged as {commit}, found {len(candidates)}")
    head = candidates[0]["head"]["sha"]
    if not SHA.fullmatch(str(head)):
        raise LookupError("pull request head is not a commit")
    return candidates[0]["number"], head


def passed_run(client, repository, head):
    runs = client.get(f"actions/workflows/build.yml/runs?event=pull_request&head_sha={head}&per_page=20")
    runs = [run for run in runs.get("workflow_runs", [])
            if run.get("path") == WORKFLOW_PATH and run.get("head_sha") == head and run.get("event") == "pull_request"
            and (run.get("head_repository") or {}).get("full_name") == repository]
    if not runs:
        raise LookupError("no pull request run of this workflow for the merged head")
    # The newest run for the head is the one its checks reported; an older green run doesn't count over a newer red one.
    run = max(runs, key=lambda item: (item.get("run_number", 0), item.get("run_attempt", 0)))
    if run.get("status") != "completed" or run.get("conclusion") != "success":
        raise LookupError(f"run {run.get('id')} did not succeed")
    jobs = client.get(f"actions/runs/{run['id']}/jobs?filter=latest&per_page=100").get("jobs", [])
    outcomes = {job.get("name"): job.get("conclusion") for job in jobs}
    missing = [name for name in REQUIRED_JOBS if outcomes.get(name) != "success"]
    if missing:
        raise LookupError(f"run {run['id']} did not pass every check: {', '.join(missing)}")
    return run["id"], run["run_attempt"]


def check_unsigned(directory, tree, version_name, code):
    directory = Path(directory)
    metadata = json.loads((directory / "release-metadata.json").read_text())
    apk = directory / "app-release-unsigned.apk"
    expected = {"source_tree": tree, "signing_identity": "unsigned", "signer_sha256": None,
                "version_name": version_name, "version_code": version_code(code), "apk_sha256": sha256(apk)}
    wrong = sorted(key for key, value in expected.items() if metadata.get(key) != value)
    if wrong:
        raise LookupError(f"unsigned APK does not match this release: {', '.join(wrong)}")
    return metadata


def find(client, repository, commit, tree, version_name, code, require_apk=True):
    """Returns the reusable run and why, or why not. Never raises for a missing or unsuitable run."""
    try:
        number, head = merged_pull_request(client, repository, commit)
        head_tree = client.tree(head)
        if head_tree != tree:
            raise LookupError(f"pull request #{number} was not up to date with main when it passed, so its tree differs")
        run_id, attempt = passed_run(client, repository, head)
        if not require_apk:
            return {"reuse": True, "pull_request": number, "head": head, "run_id": run_id, "artifact_attempt": attempt,
                    "apk_sha256": None, "reason": f"pull request #{number} run {run_id} passed this exact tree"}
        metadata = None
        # A re-run of only some jobs keeps the APK from the attempt that built it, so look back through attempts.
        for built in range(attempt, 0, -1):
            with tempfile.TemporaryDirectory() as temporary:
                try:
                    client.download(run_id, unsigned_artifact(run_id, built), temporary)
                except subprocess.CalledProcessError:
                    continue
                metadata = check_unsigned(temporary, tree, version_name, code)
                attempt = built
                break
        if metadata is None:
            raise LookupError(f"run {run_id} has no unsigned release APK")
        return {"reuse": True, "pull_request": number, "head": head, "run_id": run_id, "artifact_attempt": attempt,
                "apk_sha256": metadata["apk_sha256"], "reason": f"pull request #{number} run {run_id} passed this exact tree"}
    except Exception as error:  # Anything unexpected also means "build it here".
        return {"reuse": False, "reason": f"{type(error).__name__}: {error}"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--tree", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--github-output", required=True)
    parser.add_argument("--require-apk", action="store_true", help="this merge releases, so it needs the run's APK")
    args = parser.parse_args()
    result = find(GitHub(args.repository), args.repository, args.commit, args.tree, args.version_name, args.version_code,
                  args.require_apk)
    report = Path(args.report)
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(result, indent=2) + "\n")
    with open(args.github_output, "a") as output:
        output.write(f"reuse-run-id={result['run_id'] if result['reuse'] else ''}\n")
        output.write(f"reuse-artifact-attempt={result['artifact_attempt'] if result['reuse'] else ''}\n")
    # A warning, so a merge that never manages to reuse a pull request run is noticed.
    print(("Reusing: " if result["reuse"] else "::warning::Checking and building here: ") + result["reason"])


if __name__ == "__main__":
    main()
