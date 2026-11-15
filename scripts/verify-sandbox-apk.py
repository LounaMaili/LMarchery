#!/usr/bin/env python3
"""Inspect built APKs only. No ADB, installation, keystore access or device data."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def command(arguments):
    result = subprocess.run([str(a) for a in arguments], capture_output=True, text=True, timeout=120)
    require(result.returncode == 0, f"{arguments[0]} failed: {result.stderr.strip()}")
    return result.stdout


def inspect(apk, package, label, tools):
    require(apk.is_file(), f"Missing APK: {apk}")
    manifest = ET.fromstring(command([tools['analyzer'], 'manifest', 'print', apk]))
    app = manifest.find('application')
    require(app is not None, 'Missing application')
    require(manifest.get('package') == package, f"Unexpected package in {apk}: {manifest.get('package')}")
    require(ANDROID + 'sharedUserId' not in manifest.attrib, 'Shared UID is forbidden')
    require(app.get(ANDROID + 'name') == 'com.lmarchery.app.LMarcheryApplication', 'Unexpected application class')
    require(app.get(ANDROID + 'debuggable') == 'true', 'Validation APK must be debuggable')
    require(app.get(ANDROID + 'allowBackup') == 'false', 'Automatic backup must remain disabled')
    require(app.get(ANDROID + 'taskAffinity') in (None, package), 'Shared task affinity is forbidden')
    permissions = sorted(p.get(ANDROID + 'name') for p in manifest.findall('uses-permission'))
    require(not {'android.permission.INTERNET', 'android.permission.CAMERA'} & set(permissions), 'V1 privacy permissions changed')
    for permission in manifest.findall('permission'):
        require(permission.get(ANDROID + 'name', '').startswith(package + '.'), 'Permission namespace collision')
    providers = []
    for provider in app.findall('provider'):
        authorities = provider.get(ANDROID + 'authorities', '').split(';')
        require(all(a.startswith(package + '.') for a in authorities), 'Provider authority collision')
        providers.extend(authorities)
    launchers = []
    for activity in app.findall('activity') + app.findall('activity-alias'):
        for intent in activity.findall('intent-filter'):
            actions = {a.get(ANDROID + 'name') for a in intent.findall('action')}
            categories = {a.get(ANDROID + 'name') for a in intent.findall('category')}
            if 'android.intent.action.MAIN' in actions and 'android.intent.category.LAUNCHER' in categories:
                require(activity.get(ANDROID + 'exported') == 'true', 'Launcher must be exported')
                launchers.append(activity.get(ANDROID + 'name'))
    require(launchers == ['com.lmarchery.app.MainActivity'], f'Unexpected launchers: {launchers}')
    badging = command([tools['aapt'], 'dump', 'badging', apk])
    require(f"application-label:'{label}'" in badging.splitlines(), f'Unexpected resolved label in {apk}')
    signing = command([tools['signer'], 'verify', '--verbose', '--print-certs', apk])
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', signing)
    require(len(certs) == 1, 'Exactly one verified signer is required')
    sdk = manifest.find('uses-sdk')
    require(sdk is not None, 'Missing SDK contract')
    return {
        'apk': str(apk), 'package': package, 'label': label,
        'application': app.get(ANDROID + 'name'), 'launchers': launchers,
        'debuggable': True, 'allow_backup': False, 'shared_uid': False,
        'permissions': permissions, 'provider_authorities': sorted(providers),
        'min_sdk': sdk.get(ANDROID + 'minSdkVersion'), 'target_sdk': sdk.get(ANDROID + 'targetSdkVersion'),
        'version_code': manifest.get(ANDROID + 'versionCode'), 'version_name': manifest.get(ANDROID + 'versionName'),
        'signer_sha256': certs[0].lower(), 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
        'bytes': apk.stat().st_size,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME'))
    parser.add_argument('--normal', type=Path, default=Path('app/build/outputs/apk/debug/app-debug.apk'))
    parser.add_argument('--sandbox', type=Path, default=Path('app/build/outputs/apk/sandbox/app-sandbox.apk'))
    args = parser.parse_args()
    require(args.sdk is not None, 'ANDROID_HOME or --sdk is required')
    sdk = Path(args.sdk)
    tools = {'analyzer': sdk / 'cmdline-tools/latest/bin/apkanalyzer',
             'aapt': sdk / 'build-tools/34.0.0/aapt', 'signer': sdk / 'build-tools/34.0.0/apksigner'}
    for tool in tools.values():
        require(tool.is_file() and os.access(tool, os.X_OK), f'Missing executable: {tool}')
    normal = inspect(args.normal, 'com.lmarchery', 'LMarchery', tools)
    sandbox = inspect(args.sandbox, 'com.lmarchery.sandbox', 'LMarchery TEST', tools)
    for field in ['application', 'launchers', 'min_sdk', 'target_sdk', 'version_code', 'signer_sha256']:
        require(normal[field] == sandbox[field], f'Debug/sandbox mismatch: {field}')
    require(sandbox['version_name'] == normal['version_name'] + '-sandbox', 'Unexpected sandbox version')
    require(sandbox['min_sdk'] == '26' and sandbox['target_sdk'] == '35', 'SDK contract changed')
    print(json.dumps({'status': 'PASS', 'normal': normal, 'sandbox': sandbox,
                     'device_uid_and_storage': 'NOT_TESTED; verify on authorized device before installation'}, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError, ET.ParseError) as error:
        print(f'APK isolation verification failed: {error}', file=sys.stderr)
        sys.exit(1)
