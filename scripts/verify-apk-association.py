#!/usr/bin/env python3
"""Check the public association against an actual signed APK; never read private keys."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
signers = sorted((sdk / "build-tools").glob("*/apksigner"))
if not signers:
    sys.exit("Set ANDROID_HOME to an SDK containing apksigner")
output = subprocess.check_output([str(signers[-1]), "verify", "--print-certs", sys.argv[1]], text=True)
fingerprints = re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", output)
statements = json.loads((Path(__file__).resolve().parents[1] / "web/public/.well-known/assetlinks.json").read_text())
expected = {fp.replace(":", "").lower() for statement in statements
            if statement["target"]["package_name"] == "com.beauty.app"
            and "delegate_permission/common.handle_all_urls" in statement["relation"]
            for fp in statement["target"]["sha256_cert_fingerprints"]}
if not fingerprints or any(fp.lower() not in expected for fp in fingerprints):
    sys.exit("APK signing certificate is absent from assetlinks.json; update association before release")
print("APK signing certificate matches assetlinks.json")
