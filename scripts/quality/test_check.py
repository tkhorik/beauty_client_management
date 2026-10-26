"""Regression coverage for read-only, partially staged commit checks."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import check


class StagedSnapshotTest(unittest.TestCase):
    def setUp(self):
        # Git hooks export GIT_DIR, GIT_INDEX_FILE, etc.; inherited, they would
        # redirect the temporary repo's git commands to the hook's repository.
        clean = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        environment = patch.dict(os.environ, clean, clear=True)
        environment.start()
        self.addCleanup(environment.stop)

    def test_snapshot_uses_index_and_preserves_unstaged_and_untracked_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repo = root / "repo"
            snapshot = root / "snapshot"
            repo.mkdir()
            snapshot.mkdir()
            subprocess.run(["git", "init", "--quiet", str(repo)], check=True)
            source = repo / "partially staged.txt"
            source.write_text("staged content\n")
            subprocess.run(["git", "add", "partially staged.txt"], cwd=repo, check=True)
            before = subprocess.check_output(["git", "ls-files", "--stage"], cwd=repo)
            source.write_text("unstaged replacement\n")
            (repo / "user-local.txt").write_text("local work\n")
            with patch.object(check, "ROOT", repo):
                # run() has its default cwd bound at definition time, so scope it
                # explicitly while exercising the real Git snapshot operation.
                original_run = check.run
                with patch.object(check, "run", side_effect=lambda args: original_run(args, cwd=repo)):
                    check.snapshot(snapshot, staged=True)
                self.assertEqual(check.git_files(staged=True), ["partially staged.txt"])
            self.assertEqual((snapshot / source.name).read_text(), "staged content\n")
            self.assertFalse((snapshot / "user-local.txt").exists())
            self.assertEqual(source.read_text(), "unstaged replacement\n")
            self.assertEqual((repo / "user-local.txt").read_text(), "local work\n")
            self.assertEqual(subprocess.check_output(["git", "ls-files", "--stage"], cwd=repo), before)


if __name__ == "__main__":
    unittest.main()
