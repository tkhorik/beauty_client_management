"""Behavioral tests; scanner findings and source content are synthetic only."""

import contextlib
import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import secret_scan


class SecretScanTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="beauty-secret-test-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = (self.base / "snapshot").resolve()
        self.root.mkdir()
        self.source = self.root / "fixture.txt"
        self.source.write_bytes(b"synthetic alpha only\nsecond source line\n")
        self.policy = self.base / "policy.json"
        self.entry = {
            "path": "fixture.txt", "rule": "test-rule", "start_line": 1, "end_line": 1,
            "sha256": hashlib.sha256(b"synthetic alpha only\n").hexdigest(),
            "reason": "Synthetic fixture reviewed for this isolated unit test",
            "review_by": (datetime.datetime.now(datetime.timezone.utc).date()
                          + datetime.timedelta(days=30)).isoformat(),
        }
        self.finding = {"File": "fixture.txt", "RuleID": "test-rule", "StartLine": 1,
                        "EndLine": 1, "Secret": "NEVER_PRINT_SOURCE_VALUE",
                        "Match": "NEVER_PRINT_SOURCE_CONTEXT"}
        self.output = io.StringIO()
        self.error = io.StringIO()

    def write_policy(self, entries=None):
        self.policy.write_text(json.dumps({"version": 1, "exceptions": entries if entries is not None
                                          else [self.entry]}), encoding="utf-8")

    def invoke(self, findings=None, status=None, policy=True, partial=False,
               raw_report=None, missing=False, stdout=b"", stderr=b""):
        findings = [self.finding] if findings is None else findings
        status = int(bool(findings)) if status is None else status

        def scanner(command, **kwargs):
            self.assertEqual(command[:3], ["betterleaks", "dir", "."])
            for flag in ("--validation=false", "--redact=100", "--ignore-gitleaks-allow"):
                self.assertIn(flag, command)
            self.assertNotIn("--baseline-path", command)
            self.assertNotIn("--disable-rule", command)
            self.assertEqual(kwargs["cwd"], self.root)
            self.assertTrue(kwargs["capture_output"])
            self.assertFalse(any(key.startswith(("BETTERLEAKS_", "GITLEAKS_"))
                                 for key in kwargs["env"]))
            config = Path(command[command.index("--config") + 1])
            self.assertEqual(config.read_text(), "[extend]\nuseDefault = true\n")
            ignore = Path(command[command.index("--gitleaks-ignore-path") + 1])
            self.assertEqual(ignore.read_text(), "")
            if not missing:
                report = Path(command[command.index("--report-path") + 1])
                report.write_text(raw_report if raw_report is not None else json.dumps(findings))
            return subprocess.CompletedProcess(command, status, stdout, stderr)

        with mock.patch.object(secret_scan.subprocess, "run", side_effect=scanner), \
                contextlib.redirect_stdout(self.output), contextlib.redirect_stderr(self.error):
            return secret_scan.scan(self.root, self.policy if policy else None, partial)

    def test_no_policy_reports_only_location_and_rule(self):
        self.assertEqual(self.invoke(policy=False), 1)
        self.assertEqual(self.output.getvalue(), "fixture.txt:1-1: test-rule\n")
        self.assertNotIn("NEVER_PRINT", self.output.getvalue() + self.error.getvalue())

    def test_clean_report_passes(self):
        self.assertEqual(self.invoke(findings=[], policy=False), 0)

    def test_exact_approved_source_passes(self):
        self.write_policy()
        self.assertEqual(self.invoke(), 0)

    def test_same_location_same_length_substitution_fails(self):
        self.write_policy()
        self.source.write_bytes(b"synthetic bravo only\nsecond source line\n")
        self.assertEqual(self.invoke(), 2)
        self.assertIn("content changed", self.error.getvalue())

    def test_source_change_during_scanner_execution_fails(self):
        self.write_policy()

        def changed_source(root):
            self.source.write_bytes(b"synthetic bravo only\nsecond source line\n")
            return {("fixture.txt", "test-rule", 1, 1)}

        with mock.patch.object(secret_scan, "_findings", side_effect=changed_source), \
                contextlib.redirect_stderr(self.error):
            self.assertEqual(secret_scan.scan(self.root, self.policy), 2)

    def test_line_endings_are_bound(self):
        self.write_policy()
        self.source.write_bytes(b"synthetic alpha only\r\nsecond source line\n")
        self.assertEqual(self.invoke(), 2)

    def test_exception_does_not_cover_other_rule_path_or_line(self):
        self.write_policy()
        (self.root / "other.txt").write_bytes(self.source.read_bytes())
        for changed in ({"RuleID": "different-rule"}, {"File": "other.txt"},
                        {"StartLine": 2, "EndLine": 2}, {"EndLine": 2}):
            with self.subTest(changed=changed):
                self.assertEqual(self.invoke(findings=[dict(self.finding, **changed)]), 1)

    def test_multiline_exception_binds_every_line(self):
        self.entry["end_line"] = 2
        self.entry["sha256"] = hashlib.sha256(self.source.read_bytes()).hexdigest()
        self.write_policy()
        self.finding["EndLine"] = 2
        self.assertEqual(self.invoke(), 0)
        self.source.write_bytes(b"synthetic alpha only\nchanged source line\n")
        self.assertEqual(self.invoke(), 2)

    def test_bad_dates_fail_even_when_absent_in_partial_snapshot(self):
        self.source.unlink()
        for value in ("2000-01-01", "2099-02-30", "20990101", "not-a-date", None):
            with self.subTest(value=value):
                self.entry["review_by"] = value
                self.write_policy()
                self.assertEqual(self.invoke(findings=[], partial=True), 2)

    def test_partial_allows_absent_policy_file_not_matching_another_path(self):
        self.write_policy()
        self.source.rename(self.root / "other.txt")
        finding = dict(self.finding, File="other.txt")
        self.assertEqual(self.invoke(findings=[finding], partial=True), 1)
        self.assertEqual(self.invoke(findings=[], partial=True), 0)
        self.assertEqual(self.invoke(findings=[]), 2)

    def test_partial_checks_content_of_present_files(self):
        self.write_policy()
        self.source.write_bytes(b"different staged content\n")
        self.assertEqual(self.invoke(findings=[], partial=True), 2)

    def test_missing_policy_is_error_not_implicit_approval(self):
        self.assertEqual(self.invoke(findings=[]), 2)

    def test_bad_policy_fields_and_duplicates_fail(self):
        for field in tuple(self.entry):
            with self.subTest(missing=field):
                entry = self.entry.copy()
                del entry[field]
                self.write_policy([entry])
                self.assertEqual(self.invoke(), 2)
        self.write_policy([self.entry, self.entry])
        self.assertEqual(self.invoke(), 2)
        self.policy.write_text('{"version":1,"version":1,"exceptions":[]}')
        self.assertEqual(self.invoke(), 2)

    def test_invalid_entry_types_and_short_reason_fail(self):
        for field, value in (("start_line", True), ("start_line", 0), ("end_line", -1),
                             ("sha256", "not-a-digest"), ("reason", "TODO"),
                             ("rule", "wild*"), ("rule", "bad\nrule")):
            with self.subTest(field=field, value=value):
                self.write_policy([dict(self.entry, **{field: value})])
                self.assertEqual(self.invoke(), 2)

    def test_policy_and_report_paths_cannot_escape(self):
        for path in ("../fixture.txt", "/fixture.txt", "./fixture.txt", "a/../fixture.txt",
                     "a//fixture.txt", "a\\fixture.txt", "bad\nfile"):
            with self.subTest(path=path):
                self.write_policy([dict(self.entry, path=path)])
                self.assertEqual(self.invoke(), 2)
                self.assertEqual(self.invoke(findings=[dict(self.finding, File=path)], policy=False), 2)

    def test_symlink_policy_and_report_paths_fail(self):
        (self.root / "link.txt").symlink_to(self.source)
        self.write_policy([dict(self.entry, path="link.txt")])
        self.assertEqual(self.invoke(), 2)
        self.assertEqual(self.invoke(findings=[dict(self.finding, File="link.txt")], policy=False), 2)

    def test_unexpected_scanner_statuses_fail(self):
        for status in (-9, 2, 127):
            with self.subTest(status=status):
                self.assertEqual(self.invoke(status=status, policy=False), 2)

    def test_status_and_report_must_agree(self):
        self.assertEqual(self.invoke(status=0, policy=False), 2)
        self.assertEqual(self.invoke(findings=[], status=1, policy=False), 2)

    def test_invalid_or_missing_reports_fail(self):
        for report in ("", "not JSON", "null", "{}", "[null]", "[{}]",
                       '[{"File":"fixture.txt","File":"other.txt"}]'):
            with self.subTest(report=report):
                self.assertEqual(self.invoke(raw_report=report, policy=False), 2)
        self.assertEqual(self.invoke(missing=True, policy=False), 2)
        self.assertEqual(self.invoke(findings=[dict(self.finding, EndLine=99)], policy=False), 2)

    def test_diagnostics_never_leak_or_pass(self):
        for diagnostics in ({"stdout": b"NEVER_PRINT_DIAGNOSTICS"},
                            {"stderr": b"NEVER_PRINT_DIAGNOSTICS"}):
            self.assertEqual(self.invoke(findings=[], policy=False, **diagnostics), 2)
        self.assertNotIn("NEVER_PRINT", self.output.getvalue() + self.error.getvalue())

    def test_scanner_exception_never_leaks(self):
        for error in (OSError("NEVER_PRINT_EXEC_ERROR"),
                      subprocess.TimeoutExpired("NEVER_PRINT_COMMAND", 300)):
            with mock.patch.object(secret_scan.subprocess, "run", side_effect=error), \
                    contextlib.redirect_stderr(self.error):
                self.assertEqual(secret_scan.scan(self.root, None), 2)
        self.assertNotIn("NEVER_PRINT", self.error.getvalue())

    def test_ambient_scanner_configuration_is_scrubbed(self):
        with mock.patch.dict(os.environ, {"BETTERLEAKS_CONFIG": "/untrusted/config",
                                         "GITLEAKS_CONFIG_TOML": "untrusted"}):
            self.assertEqual(self.invoke(findings=[], policy=False), 0)


if __name__ == "__main__":
    unittest.main()
