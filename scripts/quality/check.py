#!/usr/bin/env python3
"""Shared, fail-closed quality gate. Invoked through `mise run check`."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from secret_scan import scan

ROOT = Path(__file__).resolve().parents[2]


def run(args, cwd=ROOT, **kwargs):
    print(f"+ {' '.join(map(str, args))}", flush=True)
    subprocess.run(args, cwd=cwd, check=True, **kwargs)


def git_files(staged=False):
    args = (["diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z"]
            if staged else ["ls-files", "--cached", "--others", "--exclude-standard", "-z"])
    output = subprocess.check_output(["git", *args], cwd=ROOT)
    return sorted(set(os.fsdecode(p) for p in output.split(b"\0") if p))


def snapshot(destination, staged=False):
    if staged:
        # Read the index without stashing, modifying it, or replacing user files.
        run(["git", "checkout-index", "--all", f"--prefix={destination}{os.sep}"])
    else:
        for name in git_files():
            source = ROOT / name
            if source.is_file() and not source.is_symlink():
                target = destination / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)


def scan_secrets(directory, policy_path, partial=False):
    result = scan(directory, policy_path, partial=partial)
    if result:
        raise RuntimeError(f"Secret scan failed (exit {result})")


def secrets():
    with tempfile.TemporaryDirectory(prefix="beauty-secrets-") as temporary:
        directory = Path(temporary)
        snapshot(directory)
        scan_secrets(directory, ROOT / "config/quality/secret-exceptions.json")


def workflows(directory=ROOT, files=()):
    run(["jactionlint", "--strict-exit", "--no-online", "--shellcheck=shellcheck", *files],
        cwd=directory)


def compose(directory=ROOT):
    run(["check-jsonschema", "--schemafile", "config/schemas/compose-spec.json",
         "docker-compose.yml"], cwd=directory)


def staged():
    files = git_files(staged=True)
    if not files:
        return
    with tempfile.TemporaryDirectory(prefix="beauty-staged-") as temporary:
        directory = Path(temporary)
        snapshot(directory, staged=True)
        # Scan only added/modified staged content, never unstaged secret values.
        with tempfile.TemporaryDirectory(prefix="beauty-staged-secrets-") as secret_tmp:
            secret_dir = Path(secret_tmp)
            for name in files:
                source = directory / name
                if source.is_file() and not source.is_symlink():
                    target = secret_dir / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source, target)
            scan_secrets(secret_dir, directory / "config/quality/secret-exceptions.json", partial=True)
        web = [str(Path(name).relative_to("web")) for name in files
               if name.startswith("web/") and Path(name).suffix in {".ts", ".tsx", ".js", ".jsx"}]
        if web:
            run([str(ROOT / "web/node_modules/.bin/oxlint"), "--deny-warnings", "--", *web],
                cwd=directory / "web")
        if any(name.startswith(".github/") and name.endswith((".yml", ".yaml")) for name in files):
            # The index snapshot deliberately has no .git directory. Pass the
            # workflow paths explicitly instead of relying on Git discovery.
            workflow_files = sorted(str(path.relative_to(directory))
                                    for path in (directory / ".github/workflows").glob("*")
                                    if path.suffix in {".yml", ".yaml"})
            if workflow_files:
                workflows(directory, workflow_files)
        if "docker-compose.yml" in files or any(name.startswith("config/schemas/") for name in files):
            compose(directory)


def web():
    for command in (["npm", "ci"], ["npm", "run", "lint"], ["npm", "test"], ["npm", "run", "build"]):
        run(command, cwd=ROOT / "web")


def gradle(project, *tasks, **kwargs):
    run(["bash", "./gradlew", "--no-daemon", "--console=plain", *tasks], cwd=ROOT / project, **kwargs)


def backend():
    # Preserve the existing CI retry budget for the documented shared-database
    # isolation flake. Locally stop at the first failure for diagnosis.
    attempts = 8 if os.environ.get("CI") == "true" else 1
    for attempt in range(1, attempts + 1):
        try:
            gradle("backend", "build", timeout=600)
            break
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            if attempt == attempts:
                raise
            print(f"Backend build failed on CI attempt {attempt}/{attempts}; retrying.", flush=True)
    gradle("backend", "detekt")


def android():
    gradle("android", "assembleDebug", "testDebugUnitTest")
    for origin in ("https://one.example:8443", "https://two.example"):
        gradle("android", "processReleaseMainManifest", "generateReleaseBuildConfig",
               f"-PappWebBaseUrl={origin}", "-PreleaseApiBaseUrl=https://api.independent.example/")
        run([sys.executable, "../scripts/verify-app-link-config.py", origin], cwd=ROOT / "android")
    for extra, expected in (
        ([], "Release builds require -PappWebBaseUrl"),
        (["-PappWebBaseUrl=http://site.example"], "appWebBaseUrl must be an HTTPS origin"),
    ):
        result = subprocess.run(
            ["bash", "./gradlew", "--no-daemon", "--console=plain", "generateReleaseBuildConfig", *extra],
            cwd=ROOT / "android", stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if result.returncode == 0 or expected not in result.stdout:
            print(result.stdout, file=sys.stderr)
            raise RuntimeError(f"Expected rejected release origin: {expected}")
        print(f"PASS: {expected}", flush=True)
    gradle("android", "detekt")


def security():
    lockfiles = ["web/package-lock.json", "backend/gradle.lockfile", "android/app/gradle.lockfile"]
    for name in lockfiles:
        if not (ROOT / name).is_file():
            raise RuntimeError(f"Required dependency inventory is missing: {name}")
    run(["osv-scanner", "scan", "source",
         *[arg for name in lockfiles for arg in ("--lockfile", name)]])


def quality():
    run([sys.executable, "-m", "unittest", "discover", "-s", "scripts/quality", "-p", "test_*.py"])
    run(["hk", "check", "--all", "--check"])


def kotlin():
    gradle("backend", "detekt")
    gradle("android", "detekt")


def local():
    quality()
    web()
    kotlin()


def main():
    checks = {"quality": quality, "web": web, "backend": backend, "android": android,
              "security": security, "local": local, "staged": staged,
              "workflows": workflows, "compose": compose, "secrets": secrets,
              "kotlin": kotlin}
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scope", nargs="?", default="all", choices=["all", *checks])
    args = parser.parse_args()
    if args.scope == "all":
        failures = []
        for name in ("quality", "web", "backend", "android", "security"):
            try:
                checks[name]()
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired, RuntimeError) as error:
                failures.append(name)
                print(f"FAILED {name}: {error}", file=sys.stderr)
        if failures:
            raise RuntimeError(f"Failed gates: {', '.join(failures)}")
    else:
        checks[args.scope]()


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, RuntimeError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
