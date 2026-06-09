#!/usr/bin/env python3
"""
AMO upload script  -  submits extension, updates description, uploads screenshots.

Usage:
    export AMO_KEY=user:xxxxx:nnn
    export AMO_SECRET=xxxxxxxxxxxxxxxx
    python3 amo_upload.py

Requires: Python 3.6+, no external dependencies.
"""

import base64
import hashlib
import hmac
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request
import zipfile

ADDON_SLUG = 'fetchgate'
VERSION    = '0.2.6'
CHANNEL    = 'listed'
AMO_BASE   = 'https://addons.mozilla.org/api/v5'

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
EXT_DIR    = os.path.join(SCRIPT_DIR, 'extension')
DOCS_DIR   = os.path.join(SCRIPT_DIR, 'docs')
OUT_XPI    = os.path.join(SCRIPT_DIR, f'fetchgate-{VERSION}.xpi')

AMO_DESCRIPTION = """\
FetchGate bridges an external process to a live, logged-in browser tab  -  run \
authenticated fetch() calls or arbitrary JavaScript inside the tab, inheriting \
its full session state.

When you are logged into a website, the browser holds session state (cookies, \
tokens, TLS client certificates) that is not directly accessible to an external \
program. FetchGate bridges that gap by turning an armed browser tab into a \
programmable endpoint for your code.

Two modes:

• Fetch mode  -  your code sends a request spec; the extension executes the \
corresponding fetch() inside the active tab and returns the full HTTP response, \
inheriting all session state automatically. No credentials need to be extracted \
or replayed.

• JS mode  -  your code sends a JavaScript snippet; the extension executes it as \
an async function body inside the tab and returns the result. This lets you \
traverse the DOM, call internal APIs, or run logic that depends on live page state.

Target use case: extracting your own data from websites that have accounts but \
no public API, or that actively block third-party HTTP clients.

Control:

• Toolbar popup  -  four states: Disarmed, Armed, Host disconnected, Armed on \
other tab. Each state shows a single context-appropriate action button.

• Keyboard shortcut  -  default Ctrl+Shift+F, fully configurable from the popup. \
Includes a verification step that confirms the key is actually delivered by \
Firefox and the OS (some combinations are silently captured before the extension \
sees them).

Three native host implementations:

• Java host  -  persistent TCP server on localhost:9919; callers connect in any language.

• Python TCP host  -  drop-in Python replacement for the Java host; no JDK required.

• Python embedded host  -  your Python script IS the host; Firefox launches it \
when you arm a tab.

Platform: GNU/Linux only.
Source and full documentation: https://github.com/simddev/FetchGate\
"""


# ── JWT ───────────────────────────────────────────────────────────────────────

def _b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode()

def make_jwt(key, secret):
    now = int(time.time())
    header  = _b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    payload = _b64(json.dumps({"iss": key, "iat": now, "exp": now + 60}).encode())
    sig     = _b64(hmac.new(secret.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest())
    return f"{header}.{payload}.{sig}"


# ── HTTP helpers ──────────────────────────────────────────────────────────────

def _send(make_req):
    """Retry on 429, calling make_req() fresh each time so the JWT is never stale."""
    for _ in range(6):
        req = make_req()
        try:
            with urllib.request.urlopen(req) as r:
                raw = r.read()
                return json.loads(raw) if raw.strip() else {}
        except urllib.error.HTTPError as e:
            err = e.read()
            if e.code == 429:
                try:
                    wait = int(json.loads(err).get('detail', '').split()[-2]) + 2
                except Exception:
                    wait = 30
                print(f"  Rate limited  -  waiting {wait}s...")
                time.sleep(wait)
                continue
            print(f"  HTTP {e.code}: {err[:500]}", file=sys.stderr)
            raise
    raise RuntimeError("Exceeded retry limit on 429")


def api(method, path, jwt_fn, body=None, content_type=None):
    url = f"{AMO_BASE}{path}"
    def make_req():
        headers = {"Authorization": f"JWT {jwt_fn()}"}
        if content_type:
            headers["Content-Type"] = content_type
        data = body if isinstance(body, bytes) else (body.encode() if body else None)
        return urllib.request.Request(url, data=data, headers=headers, method=method)
    return _send(make_req)


def api_multipart(method, path, jwt_fn, fields, files):
    boundary = b'----FGBoundary' + str(int(time.time())).encode()
    buf = io.BytesIO()
    for name, value in fields:
        buf.write(b'--' + boundary + b'\r\n')
        buf.write(f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode())
        buf.write(value.encode() + b'\r\n')
    for name, filename, ct, data in files:
        buf.write(b'--' + boundary + b'\r\n')
        buf.write(f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'.encode())
        buf.write(f'Content-Type: {ct}\r\n\r\n'.encode())
        buf.write(data + b'\r\n')
    buf.write(b'--' + boundary + b'--\r\n')
    body = buf.getvalue()
    ct   = f'multipart/form-data; boundary={boundary.decode()}'
    url  = f"{AMO_BASE}{path}"
    def make_req():
        headers = {"Authorization": f"JWT {jwt_fn()}", "Content-Type": ct}
        return urllib.request.Request(url, data=body, headers=headers, method=method)
    return _send(make_req)


# ── Steps ─────────────────────────────────────────────────────────────────────

def build_zip():
    print("Building extension zip...")
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as zf:
        for fname in sorted(os.listdir(EXT_DIR)):
            if fname.startswith('.'):
                continue
            fpath = os.path.join(EXT_DIR, fname)
            if os.path.isfile(fpath):
                zf.write(fpath, fname)
                print(f"  + {fname}")
    return buf.getvalue()


def upload_xpi(zip_data, jwt_fn):
    print("Uploading XPI to AMO...")
    result = api_multipart('POST', '/addons/upload/', jwt_fn,
                           fields=[('channel', CHANNEL)],
                           files=[('upload', f'fetchgate-{VERSION}.zip',
                                   'application/zip', zip_data)])
    uuid = result['uuid']
    print(f"  UUID: {uuid}")

    print("Waiting for processing...")
    deadline = time.time() + 120
    while time.time() < deadline:
        r = api('GET', f'/addons/upload/{uuid}/', jwt_fn)
        processed = r.get('processed', False)
        print(f"  processed={processed}")
        if processed:
            if not r.get('valid'):
                print(f"  Validation errors: {json.dumps(r.get('validation', {}), indent=2)}", file=sys.stderr)
                sys.exit(1)
            print("  Valid.")
            return uuid
        time.sleep(5)
    raise TimeoutError("Timed out waiting for upload processing")


def create_version(uuid, jwt_fn):
    print("Creating new version on AMO...")
    result = api('POST', f'/addons/addon/{ADDON_SLUG}/versions/', jwt_fn,
                 body=json.dumps({'upload': uuid, 'channel': CHANNEL}),
                 content_type='application/json')
    version_id = result['id']
    print(f"  Version ID: {version_id}")
    return version_id


def wait_for_public(version_id, jwt_fn):
    print("Waiting for version to be signed (up to 10 min)...")
    deadline = time.time() + 600
    while time.time() < deadline:
        r = api('GET', f'/addons/addon/{ADDON_SLUG}/versions/{version_id}/', jwt_fn)
        file_info = r.get('file', {})
        status = file_info.get('status', '?')
        print(f"  file.status={status}")
        if status == 'public':
            url = file_info.get('url')
            print(f"  Signed URL: {url}")
            return url
        if status in ('disabled', 'deleted'):
            print(f"  File entered terminal state: {status}", file=sys.stderr)
            sys.exit(1)
        time.sleep(10)
    raise TimeoutError("Timed out waiting for file to go public  -  it may be in manual review. Check AMO.")


def download_signed(url, jwt_fn):
    print(f"Downloading signed XPI → {OUT_XPI}")
    req = urllib.request.Request(url, headers={'Authorization': f'JWT {jwt_fn()}'})
    with urllib.request.urlopen(req) as r, open(OUT_XPI, 'wb') as f:
        f.write(r.read())
    print(f"  {os.path.getsize(OUT_XPI):,} bytes saved.")


def update_description(jwt_fn):
    print("Updating listing description...")
    api('PATCH', f'/addons/addon/{ADDON_SLUG}/', jwt_fn,
        body=json.dumps({'description': {'en-US': AMO_DESCRIPTION}}),
        content_type='application/json')
    print("  Done.")


def upload_screenshots(jwt_fn):
    shots = [
        ('popup_disarmed.png',    'FetchGate popup  -  disarmed state'),
        ('popup_armed.png',       'FetchGate popup  -  armed, host connected'),
        ('popup_armed_other.png', 'FetchGate popup  -  armed on a different tab'),
    ]

    # Remove existing previews to avoid duplicates on re-run
    print("Removing existing screenshots...")
    result = api('GET', f'/addons/addon/{ADDON_SLUG}/', jwt_fn)
    for p in result.get('previews', []):
        pid = p['id']
        print(f"  Deleting preview {pid}")
        api('DELETE', f'/addons/addon/{ADDON_SLUG}/previews/{pid}/', jwt_fn)

    for i, (fname, caption) in enumerate(shots):
        fpath = os.path.join(DOCS_DIR, fname)
        with open(fpath, 'rb') as f:
            data = f.read()
        if i > 0:
            time.sleep(20)
        print(f"Uploading screenshot {i+1}/{len(shots)}: {fname}")
        r = api_multipart('POST', f'/addons/addon/{ADDON_SLUG}/previews/', jwt_fn,
                          fields=[('position', str(i)),
                                  ('caption[en-US]', caption)],
                          files=[('image', fname, 'image/png', data)])
        print(f"  Preview ID: {r.get('id')}")


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    key    = os.environ.get('AMO_KEY')
    secret = os.environ.get('AMO_SECRET')
    if not key or not secret:
        print("Error: AMO_KEY and AMO_SECRET must be set.", file=sys.stderr)
        print("  export AMO_KEY=user:xxxxx:nnn", file=sys.stderr)
        print("  export AMO_SECRET=your_secret", file=sys.stderr)
        sys.exit(1)

    jwt_fn = lambda: make_jwt(key, secret)

    zip_data   = build_zip()
    uuid       = upload_xpi(zip_data, jwt_fn)
    version_id = create_version(uuid, jwt_fn)
    url        = wait_for_public(version_id, jwt_fn)
    download_signed(url, jwt_fn)
    update_description(jwt_fn)
    upload_screenshots(jwt_fn)

    print(f"\nAll done.")
    print(f"  Signed XPI : {OUT_XPI}")
    print(f"  AMO listing: https://addons.mozilla.org/en-US/firefox/addon/{ADDON_SLUG}/")


if __name__ == '__main__':
    main()
