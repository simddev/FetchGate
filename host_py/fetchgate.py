"""FetchGate Python native host library.

Your Python script IS the native host process — Firefox launches it via the
native messaging manifest when you arm a tab. Import this module, create a
FetchGate instance, and call fetch() to send authenticated requests through
the armed tab.

Usage
-----
    #!/usr/bin/env python3
    import sys
    from fetchgate import FetchGate

    fg = FetchGate()
    # After construction, sys.stdout is redirected to sys.stderr so that
    # accidental print() calls cannot corrupt the Native Messaging stream.
    # Use sys.__stdout__ to write results to real stdout (e.g. for piping).
    resp = fg.fetch({"method": "GET", "url": "/api/data"})
    sys.__stdout__.write(resp["body"])

Then point ~/.mozilla/native-messaging-hosts/fetchgate.json at your script.
See fetchgate_py.json in the project root for the manifest template.

Notes
-----
- stdout is captured for the Native Messaging binary stream. FetchGate
  redirects sys.stdout to sys.stderr on construction so that accidental
  print() calls do not corrupt the stream. Use sys.__stdout__ to write
  to real stdout after construction.
- Firefox launches (and owns) the process lifetime. The script runs once
  and exits; to re-run it, click the toolbar button again to re-arm.
- Responses with {"error": "..."} are returned normally, not raised.
  Only a lost NM connection raises FetchGateError.
- There is no request timeout. fetch() blocks until the extension replies
  or the NM connection is closed. If the browser network request hangs
  (e.g. a slow or unresponsive server), the script will block indefinitely.
"""

import json
import struct
import sys
from typing import Optional


class FetchGateError(Exception):
    """Raised when the Native Messaging connection to Firefox is lost."""


class FetchGate:
    """Send fetch() requests through the armed Firefox tab.

    Speaks Firefox's Native Messaging protocol (4-byte little-endian length
    header + UTF-8 JSON payload) directly on stdin/stdout.
    """

    _MAX_BYTES = 1_048_576  # Firefox's hard 1 MB NM message cap

    def __init__(self) -> None:
        # Capture the real stdin/stdout before anything else touches them.
        self._out = sys.stdout.buffer
        self._in  = sys.stdin.buffer
        self._seq = 0

        # Redirect text stdout to stderr so accidental print() calls cannot
        # corrupt the binary Native Messaging stream — same reason as the
        # System.setOut(System.err) call in the Java host's Main.java.
        # The original stdout is still accessible via sys.__stdout__.
        sys.stdout = sys.stderr  # type: ignore[assignment]

    def fetch(self, spec: dict) -> dict:
        """Send a fetch request through the armed tab and return the response.

        Parameters
        ----------
        spec : dict
            url        (required) — absolute URL or path relative to tab origin
            method     (optional) — HTTP method, defaults to GET
            headers    (optional) — dict of additional request headers
            body       (optional) — request body string (for POST/PUT)
            credentials(optional) — "same-origin" (default), "include", "omit"

        Returns
        -------
        dict
            On success: {"status": 200, "statusText": "OK", "headers": {...}, "body": "..."}
            On error:   {"error": "..."}

        Raises
        ------
        FetchGateError
            If the Native Messaging connection to Firefox is lost.
        """
        self._seq += 1
        req_id = self._seq

        self._write({"__fg_id": req_id, "req": json.dumps(spec)})

        while True:
            msg = self._read()
            if msg is None:
                raise FetchGateError("Native Messaging connection closed by Firefox")
            if msg.get("__fg_id") == req_id:
                del msg["__fg_id"]
                return msg
            # Wrong ID — stale reply from a previous request; discard.

    # ── Internal NM framing ───────────────────────────────────────────────────

    def _write(self, obj: dict) -> None:
        data = json.dumps(obj).encode("utf-8")
        if len(data) > self._MAX_BYTES:
            raise FetchGateError(f"Outgoing message too large: {len(data)} bytes")
        self._out.write(struct.pack("<I", len(data)))
        self._out.write(data)
        self._out.flush()

    def _read(self) -> Optional[dict]:
        # sys.stdin.buffer is a BufferedReader; BufferedReader.read(n) blocks
        # until exactly n bytes are available (non-interactive pipe), so short
        # reads only occur on genuine EOF — not on partial arrival.
        header = self._in.read(4)
        if len(header) < 4:
            return None  # EOF — Firefox closed the connection
        length = struct.unpack("<I", header)[0]
        if length > self._MAX_BYTES:
            return None  # malformed / oversized frame
        payload = self._in.read(length)
        if len(payload) < length:
            return None  # truncated frame
        try:
            return json.loads(payload.decode("utf-8"))
        except json.JSONDecodeError:
            return None  # malformed JSON frame — treat as lost connection
