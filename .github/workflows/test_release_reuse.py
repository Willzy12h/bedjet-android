#!/usr/bin/env python3
"""Host tests for choosing a pull request's checked APK for a release; no network or Android build."""

import hashlib
import json
import subprocess
import unittest
from pathlib import Path

import release_reuse as reuse

REPO = "owner/app"
MERGE = "a" * 40
HEAD = "b" * 40
TREE = "c" * 40
APK = b"unsigned apk bytes"


def pull(**changes):
    value = {"number": 7, "merged_at": "2026-11-17T00:00:00Z", "merge_commit_sha": MERGE, "base": {"ref": "main"},
             "head": {"sha": HEAD, "repo": {"full_name": REPO}}}
    value.update(changes)
    return value


def run(**changes):
    value = {"id": 50, "run_number": 9, "run_attempt": 1, "path": reuse.WORKFLOW_PATH, "head_sha": HEAD,
             "event": "pull_request", "head_repository": {"full_name": REPO}, "status": "completed", "conclusion": "success"}
    value.update(changes)
    return value


def metadata(**changes):
    value = {"source_tree": TREE, "signing_identity": "unsigned", "signer_sha256": None, "version_name": "1.2.0-beta.1",
             "version_code": 20261120, "apk_sha256": hashlib.sha256(APK).hexdigest()}
    value.update(changes)
    return value


class FakeGitHub:
    def __init__(self, pulls=None, runs=None, jobs=None, artifacts=None, tree=TREE):
        self.pulls = [pull()] if pulls is None else pulls
        self.runs = [run()] if runs is None else runs
        self.jobs = jobs if jobs is not None else [{"name": name, "conclusion": "success"} for name in reuse.REQUIRED_JOBS]
        self.artifacts = {reuse.unsigned_artifact(50, 1): metadata()} if artifacts is None else artifacts
        self.head_tree = tree

    def get(self, path):
        if path == f"commits/{MERGE}/pulls":
            return self.pulls
        if path.startswith("actions/workflows/build.yml/runs?"):
            return {"workflow_runs": self.runs}
        if path.startswith("actions/runs/") and "/jobs?filter=latest" in path:
            return {"jobs": self.jobs}
        raise AssertionError(path)

    def download(self, run_id, name, destination):
        if name not in self.artifacts:
            raise subprocess.CalledProcessError(1, "gh run download")
        Path(destination, "app-release-unsigned.apk").write_bytes(APK)
        Path(destination, "release-metadata.json").write_text(json.dumps(self.artifacts[name]))

    def tree(self, commit):
        if commit != HEAD:
            raise AssertionError(commit)
        return self.head_tree


def find(client):
    return reuse.find(client, REPO, MERGE, TREE, "1.2.0-beta.1", "20261120")


class ReleaseReuseTest(unittest.TestCase):
    def assertBuildsHere(self, client, reason):
        result = find(client)
        self.assertFalse(result["reuse"], result)
        self.assertIn(reason, result["reason"])

    def test_a_passed_up_to_date_pull_request_is_reused(self):
        result = find(FakeGitHub())
        self.assertTrue(result["reuse"], result)
        self.assertEqual((result["run_id"], result["artifact_attempt"], result["pull_request"]), (50, 1, 7))

    def test_a_branch_behind_main_builds_here(self):
        self.assertBuildsHere(FakeGitHub(tree="d" * 40), "not up to date")

    def test_a_fork_pull_request_builds_here(self):
        self.assertBuildsHere(FakeGitHub(pulls=[pull(head={"sha": HEAD, "repo": {"full_name": "someone/app"}})]),
                              "found 0")

    def test_a_push_without_a_merged_pull_request_builds_here(self):
        self.assertBuildsHere(FakeGitHub(pulls=[]), "found 0")
        self.assertBuildsHere(FakeGitHub(pulls=[pull(merge_commit_sha="e" * 40)]), "found 0")
        self.assertBuildsHere(FakeGitHub(pulls=[pull(base={"ref": "other"})]), "found 0")

    def test_the_newest_run_decides_even_when_an_older_one_passed(self):
        self.assertBuildsHere(FakeGitHub(runs=[run(), run(id=51, run_number=10, conclusion="failure")]), "did not succeed")

    def test_a_run_still_going_or_from_another_workflow_or_fork_builds_here(self):
        self.assertBuildsHere(FakeGitHub(runs=[run(status="in_progress", conclusion=None)]), "did not succeed")
        self.assertBuildsHere(FakeGitHub(runs=[run(path=".github/workflows/other.yml")]), "no pull request run")
        self.assertBuildsHere(FakeGitHub(runs=[run(head_repository={"full_name": "someone/app"})]), "no pull request run")

    def test_a_skipped_or_failed_job_builds_here(self):
        for conclusion in ("skipped", "failure", None):
            jobs = [{"name": name, "conclusion": conclusion if name == "gate" else "success"} for name in reuse.REQUIRED_JOBS]
            self.assertBuildsHere(FakeGitHub(jobs=jobs), "gate")
        jobs = [{"name": name, "conclusion": "success"} for name in reuse.REQUIRED_JOBS if name != "screenshots"]
        self.assertBuildsHere(FakeGitHub(jobs=jobs), "screenshots")

    def test_an_apk_from_an_earlier_attempt_is_found(self):
        client = FakeGitHub(runs=[run(run_attempt=2)], artifacts={reuse.unsigned_artifact(50, 1): metadata()})
        result = find(client)
        self.assertTrue(result["reuse"], result)
        self.assertEqual(result["artifact_attempt"], 1)

    def test_a_run_without_an_apk_builds_here(self):
        self.assertBuildsHere(FakeGitHub(artifacts={}), "no unsigned release APK")

    def test_an_apk_that_does_not_match_the_release_builds_here(self):
        for field, value in (("version_name", "1.2.0-beta.0"), ("version_code", 20261119), ("source_tree", "f" * 40),
                             ("signing_identity", "test-only"), ("signer_sha256", "0" * 64), ("apk_sha256", "0" * 64)):
            artifacts = {reuse.unsigned_artifact(50, 1): metadata(**{field: value})}
            self.assertBuildsHere(FakeGitHub(artifacts=artifacts), field)

    def test_a_github_error_builds_here(self):
        class Broken(FakeGitHub):
            def get(self, path):
                raise subprocess.CalledProcessError(1, "gh api")
        self.assertBuildsHere(Broken(), "CalledProcessError")


if __name__ == "__main__":
    unittest.main()
