#!/usr/bin/env python3
"""Assert release manifest registration and runtime trust use the requested website origin."""
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

origin = sys.argv[1].rstrip('/')
expected = urlsplit(origin)
api_origin = sys.argv[2] if len(sys.argv) > 2 else 'https://api.independent.example/'
root = Path(__file__).resolve().parents[1] / 'android/app/build'
android = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(root / 'intermediates/merged_manifest/release/AndroidManifest.xml')
filters = [node for node in manifest.findall('.//intent-filter') if node.get(android + 'autoVerify') == 'true']
assert len(filters) == 1, 'Exactly one verified release link filter is required'
data = filters[0].find('data')
assert data is not None
assert data.get(android + 'host') == expected.hostname
assert data.get(android + 'scheme') == 'https'
assert not any(android + attr in data.attrib for attr in ('port', 'path', 'pathPrefix', 'pathPattern'))
config = (root / 'generated/source/buildConfig/release/com/beauty/app/BuildConfig.java').read_text()
assert re.search(r'APP_WEB_BASE_URL = "' + re.escape(origin) + r'"', config)
assert re.search(r'API_BASE_URL = "' + re.escape(api_origin) + r'"', config)
print('Verified manifest host and runtime website origin; API configuration is independent')
