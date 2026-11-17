"""Sign an UNSIGNED release APK with the app's private release key, using an Android key-rotation lineage so users who
installed an earlier debug-signed build can update in place (APK Signature Scheme v3, Android 9+).

    python sign_release.py <island|clock> <unsigned.apk> <signed-out.apk> [--min-sdk N]

* The private key lives OUTSIDE this repository: ~/.houseli-keystore/<app>/ (keystore.properties holds the passwords).
  Nothing secret is read from, written to, or printed in this repository. Passwords reach apksigner through the child
  process environment only (never on a command line, never in a log).
* The lineage file (signing/<app>.lineage) holds only PUBLIC certificates plus the old key's signature over the new one.
  It is created once by --make-lineage from the old (debug) key and must be kept for every future release.
* v3 only (needs Android 9+): with a lineage, apksigner forces any v1/v2 signature to be made by the OLDEST key, i.e. the debug key.
  Shipping v3 only means the debug key signs nothing in the release.
* Refuses to sign if the result would still be debug-signed or debuggable.
"""
import argparse, glob, os, re, subprocess, sys
HOME = os.path.expanduser('~')
KS_ROOT = os.path.join(HOME, '.houseli-keystore')
DEBUG_KS = os.path.join(HOME, '.android', 'debug.keystore')          # the OLD identity of the published v1.0 APKs (public, well-known password)
HERE = os.path.dirname(os.path.abspath(__file__))
SDK = glob.glob(os.path.join(HOME, 'AppData', 'Local', 'Android', 'Sdk', 'build-tools', '*'))
SDK = sorted(SDK, key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r'\D+', os.path.basename(p))])[-1]
APKSIGNER, AAPT2 = os.path.join(SDK, 'apksigner.bat'), os.path.join(SDK, 'aapt2.exe')

def props(app):
    d = {}
    for line in open(os.path.join(KS_ROOT, app, 'keystore.properties'), encoding='utf8'):
        if '=' in line: k, v = line.rstrip('\n').split('=', 1); d[k] = v
    return d

def run(cmd, env=None):
    r = subprocess.run(cmd, capture_output=True, text=True, env={**os.environ, **(env or {})})
    return r.returncode, (r.stdout + r.stderr)

def main():
    ap = argparse.ArgumentParser(); ap.add_argument('app', choices=['island', 'clock']); ap.add_argument('unsigned'); ap.add_argument('out')
    ap.add_argument('--min-sdk', type=int, default=None); ap.add_argument('--make-lineage', action='store_true')
    a = ap.parse_args()
    p = props(a.app); env = {'HOUSELI_STORE_PW': p['storePassword'], 'HOUSELI_KEY_PW': p['keyPassword']}
    lineage = os.path.join(HERE, 'signing', f'{a.app}.lineage'); os.makedirs(os.path.dirname(lineage), exist_ok=True)
    if a.make_lineage or not os.path.exists(lineage):
        if os.path.exists(lineage): sys.exit('lineage already exists; refusing to overwrite it')
        rc, out = run([APKSIGNER, 'rotate', '--out', lineage,
                       '--old-signer', '--ks', DEBUG_KS, '--ks-pass', 'pass:android', '--ks-key-alias', 'androiddebugkey',
                       '--new-signer', '--ks', p['storeFile'], '--ks-pass', 'env:HOUSELI_STORE_PW', '--key-pass', 'env:HOUSELI_KEY_PW', '--ks-key-alias', p['keyAlias']], env)
        if rc: sys.exit('lineage creation failed:\n' + out.replace(p['storePassword'], '***'))
        print('lineage created:', lineage)
    # the manifest must say debuggable=false
    rc, badge = run([AAPT2, 'dump', 'badging', a.unsigned])
    if 'application-debuggable' in badge: sys.exit('refusing: the APK is debuggable')
    cmd = [APKSIGNER, 'sign', '--ks', p['storeFile'], '--ks-pass', 'env:HOUSELI_STORE_PW', '--key-pass', 'env:HOUSELI_KEY_PW', '--ks-key-alias', p['keyAlias'],
           '--lineage', lineage, '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'false', '--v3-signing-enabled', 'true', '--rotation-min-sdk-version', '28', '--out', a.out]
    if a.min_sdk: cmd += ['--min-sdk-version', str(a.min_sdk)]
    rc, out = run(cmd + [a.unsigned], env)
    if rc: sys.exit('signing failed:\n' + out.replace(p['storePassword'], '***'))
    rc, out = run([APKSIGNER, 'verify', '--verbose', '--print-certs', a.out])
    print(out.strip())
    if 'Android Debug' in out: sys.exit('refusing: output is debug-signed')

main()
