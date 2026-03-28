#!/usr/bin/env python3
"""Example FetchGate Python script.

This script IS the native host — Firefox launches it when you arm a tab.

Setup
-----
1. Make this file (or your own copy) executable:
       chmod +x example.py

2. Edit fetchgate_py.json (project root): set "path" to the absolute path
   of this script.

3. Install the manifest:
       cp fetchgate_py.json ~/.mozilla/native-messaging-hosts/fetchgate.json

4. In Firefox/LibreWolf, navigate to the site you want to scrape and click
   the FetchGate toolbar button. Firefox will launch this script immediately.
"""

import sys
import os

# Allow running from any working directory.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fetchgate import FetchGate, FetchGateError

fg = FetchGate()
# After FetchGate(), sys.stdout is redirected to sys.stderr to protect the
# NM stream. Use sys.__stdout__ to write results to real stdout (for piping).

# --- put your fetch calls below ---

try:
    # ── Fetch mode: run fetch() in the tab ────────────────────────────────────
    resp = fg.fetch({"method": "GET", "url": "/robots.txt"})

    if "error" in resp:
        print(f"Error: {resp['error']}", file=sys.stderr)
        sys.exit(1)

    print(f"Status : {resp['status']} {resp['statusText']}", file=sys.stderr)
    print(f"Body   : {len(resp['body'])} chars", file=sys.stderr)

    # Write the body to real stdout (e.g. for piping to a file or another tool).
    # sys.__stdout__ always points to the original stdout regardless of the
    # sys.stdout redirect that FetchGate() performs.
    sys.__stdout__.write(resp["body"])
    sys.__stdout__.flush()

    # ── JS mode: execute arbitrary JavaScript in the tab ─────────────────────
    # Use this to access APIs, read the DOM, or run any async code.
    # The code runs as an async function body — use return and await freely.
    js_resp = fg.fetch({"js": """
        const r = await fetch('/robots.txt');
        const text = await r.text();
        return text;
    """})

    if "error" in js_resp:
        print(f"JS error: {js_resp['error']}", file=sys.stderr)
        sys.exit(1)

    print(f"JS result length: {len(js_resp['result'])} chars", file=sys.stderr)

    # For JSON APIs, parse the result:
    # import json
    # data = json.loads(js_resp["result"])

except FetchGateError as e:
    print(f"Connection lost: {e}", file=sys.stderr)
    sys.exit(1)
