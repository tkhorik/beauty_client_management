#!/usr/bin/env python3
"""Fail-closed Betterleaks wrapper with exact, expiring source exceptions.

Policy format: {"version": 1, "exceptions": [{"path": "relative/file",
"rule": "rule-id", "start_line": 1, "end_line": 1, "sha256": "...",
"reason": "Reviewed explanation of this specific false positive",
"review_by": "YYYY-MM-DD"}]}.

The digest covers raw bytes of the inclusive source-line span, including line
endings (bytes.splitlines(keepends=True)). Native baselines are never used.
No policy means no exceptions. Partial mode permits missing source files, not
invalid metadata or expired entries. Return codes: 0 clean, 1 findings, 2 error.
"""

import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tempfile


class ScanError(Exception):
    """An already-sanitized policy or scanner error."""


def _json(text):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ScanError("duplicate JSON object key")
            result[key] = value
        return result

    try:
        return json.loads(text, object_pairs_hook=unique)
    except (ValueError, UnicodeError):
        raise ScanError("invalid JSON") from None


def _path(value):
    if not isinstance(value, str) or not value or "\\" in value:
        raise ScanError("invalid relative source path")
    path = PurePosixPath(value)
    if (path.is_absolute() or path.as_posix() != value
            or any(part in {"", ".", ".."} for part in value.split("/"))
            or any(ord(char) < 32 or ord(char) == 127 for char in value)):
        raise ScanError("invalid relative source path")
    return value


def _identity(path, rule, start, end):
    _path(path)
    if not isinstance(rule, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", rule):
        raise ScanError("invalid rule identifier")
    if type(start) is not int or type(end) is not int or not 1 <= start <= end:
        raise ScanError("invalid source-line span")
    return path, rule, start, end


def _source(root, name, partial=False):
    source = root / name
    current = root
    for part in PurePosixPath(name).parts:
        current = current / part
        if current.is_symlink():
            raise ScanError("symlink source paths are not permitted")
    if not source.resolve().is_relative_to(root):
        raise ScanError("source path escapes scan directory")
    if partial and not source.exists():
        return None
    if not source.is_file():
        raise ScanError("source file is missing or not regular")
    return source.read_bytes().splitlines(keepends=True)


def _policy(root, policy_path, partial):
    if policy_path is None:
        return set()
    policy = _json(Path(policy_path).read_text(encoding="utf-8"))
    if (not isinstance(policy, dict) or set(policy) != {"version", "exceptions"}
            or type(policy["version"]) is not int or policy["version"] != 1
            or not isinstance(policy["exceptions"], list)):
        raise ScanError("invalid exception policy schema")
    fields = {"path", "rule", "start_line", "end_line", "sha256", "reason", "review_by"}
    seen = set()
    approved = set()
    today = datetime.datetime.now(datetime.timezone.utc).date()
    for entry in policy["exceptions"]:
        if not isinstance(entry, dict) or set(entry) != fields:
            raise ScanError("invalid exception entry fields")
        identity = _identity(entry["path"], entry["rule"], entry["start_line"], entry["end_line"])
        if identity in seen:
            raise ScanError("duplicate exception identity")
        seen.add(identity)
        if not isinstance(entry["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]):
            raise ScanError("invalid exception source digest")
        reason = entry["reason"]
        if (not isinstance(reason, str) or len(reason.strip()) < 20
                or len(reason.split()) < 4 or any(ord(char) < 32 for char in reason)):
            raise ScanError("exception requires a meaningful review reason")
        expiry = entry["review_by"]
        if not isinstance(expiry, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", expiry):
            raise ScanError("invalid exception review date")
        try:
            review_by = datetime.date.fromisoformat(expiry)
        except ValueError:
            raise ScanError("invalid exception review date") from None
        if review_by < today:
            raise ScanError("expired exception review date")
        lines = _source(root, entry["path"], partial)
        if lines is None:
            continue
        if entry["end_line"] > len(lines):
            raise ScanError("exception source-line span no longer exists")
        digest = hashlib.sha256(b"".join(lines[entry["start_line"] - 1:entry["end_line"]])).hexdigest()
        if digest != entry["sha256"]:
            raise ScanError("exception source content changed; review required")
        approved.add(identity)
    return approved


def _findings(root):
    with tempfile.TemporaryDirectory(prefix="beauty-secret-report-") as temporary:
        temporary = Path(temporary)
        report = temporary / "report.json"
        config = temporary / "default.toml"
        ignore = temporary / "empty.ignore"
        config.write_text("[extend]\nuseDefault = true\n", encoding="utf-8")
        ignore.write_text("", encoding="utf-8")
        # Explicit config/ignore paths and a scrubbed environment prevent ambient
        # rules, allowlists, baselines, or live-validation settings changing scope.
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(("BETTERLEAKS_", "GITLEAKS_"))}
        result = subprocess.run(
            ["betterleaks", "dir", ".", "--validation=false", "--redact=100",
             "--ignore-gitleaks-allow", "--no-banner", "--no-color", "--log-level=error",
             "--exit-code=1", "--config", str(config), "--gitleaks-ignore-path", str(ignore),
             "--report-format=json", "--report-path", str(report)],
            cwd=root, env=environment, capture_output=True, timeout=300, check=False)
        # Never replay scanner diagnostics: an error may contain source content.
        if result.returncode not in (0, 1) or result.stdout or result.stderr:
            raise ScanError("scanner failed or emitted unexpected diagnostics")
        if not report.is_file():
            raise ScanError("scanner did not produce a report")
        report_data = _json(report.read_text(encoding="utf-8"))
        if not isinstance(report_data, list):
            raise ScanError("scanner report must be a JSON list")
        if bool(report_data) != (result.returncode == 1):
            raise ScanError("scanner status and report disagree")
        findings = set()
        for finding in report_data:
            if not isinstance(finding, dict) or not {"File", "RuleID", "StartLine", "EndLine"} <= set(finding):
                raise ScanError("invalid scanner finding fields")
            identity = _identity(finding["File"], finding["RuleID"], finding["StartLine"], finding["EndLine"])
            lines = _source(root, identity[0])
            if identity[3] > len(lines):
                raise ScanError("scanner finding refers to nonexistent source lines")
            findings.add(identity)
        return findings


def scan(directory, policy_path, partial=False):
    """Scan a snapshot; print only unapproved path/line/rule or sanitized errors."""
    try:
        root = Path(directory).resolve(strict=True)
        if not root.is_dir():
            raise ScanError("scan target is not a directory")
        approved = _policy(root, policy_path, partial)
        findings = _findings(root)
        # A snapshot must not change while scanning. Recheck exact source bytes
        # before applying approvals, never trust a pre-scan digest alone.
        if policy_path is not None and _policy(root, policy_path, partial) != approved:
            raise ScanError("exception policy changed during scan")
        findings -= approved
        for path, rule, start, end in sorted(findings):
            print(f"{path}:{start}-{end}: {rule}")
        return 1 if findings else 0
    except ScanError as error:
        print(f"Secret scan failed: {error}", file=sys.stderr)
    except (OSError, UnicodeError, subprocess.SubprocessError, RuntimeError):
        print("Secret scan failed: unable to read input or execute scanner", file=sys.stderr)
    return 2
