#!/usr/bin/env python3
"""FetchGate Python TCP host.

A pure-Python drop-in replacement for the Java host. This script runs as the
Firefox Native Messaging host while simultaneously exposing the same TCP
interface on localhost:9919 that the Java host provides. Any tool built for
the Java host (e.g. hunter.py, fg.py) works without changes.

Architecture
------------

    Tool (hunter.py, fg.py, ...)
        ↓ newline-delimited JSON over TCP  localhost:9919
    This script  (fetchgate_tcp_host.py)
        ↓ Firefox Native Messaging  stdin/stdout  (launched by Firefox)
    Background script → content script → fetch() / JS in the tab

Setup
-----
1. Make this file executable:
       chmod +x host_py/fetchgate_tcp_host.py

2. Edit fetchgate_tcp_py.json (project root): set "path" to the absolute path
   of this file.

3. Install the manifest — mutually exclusive with fetchgate.json (Java) and
   fetchgate_py.json (embedded Python):
       cp fetchgate_tcp_py.json ~/.mozilla/native-messaging-hosts/fetchgate.json

4. Arm a tab in Firefox — click the toolbar button. Firefox launches this
   script, which prints "Listening on localhost:9919" to stderr (visible in
   the Firefox Browser Console) and waits for TCP connections.

Notes
-----
- Only one NM request is in flight at a time — NM is serial by design.
  Concurrent TCP clients are accepted and handled in threads; each waits for
  the lock before forwarding to the browser.
- When Firefox closes the NM connection, the next fg.fetch() call raises
  FetchGateError and nm_dead is set — subsequent TCP clients receive an error.
  If no client is connected at that moment, the process continues running
  until killed (e.g. when Firefox itself exits and the OS reclaims the process).
- Do not write to sys.stdout after constructing FetchGate() — it is redirected
  to sys.stderr to protect the NM binary stream. All print() calls here go to
  stderr, which is what the Firefox Browser Console shows.
- No request timeout. If a request hangs, the TCP client can impose its own
  socket timeout on the client side.
"""

import json
import socket
import sys
import threading
from pathlib import Path

# Allow importing fetchgate.py from the same directory, regardless of cwd.
sys.path.insert(0, str(Path(__file__).parent))
from fetchgate import FetchGate, FetchGateError, FetchGateSizeError

TCP_HOST = "localhost"
TCP_PORT = 9919
RECV_SIZE = 65536
MAX_RECV_BYTES = 1_048_576  # reject before calling fg.fetch() to avoid false nm_dead


def _send(conn: socket.socket, obj: dict) -> None:
    """Write a JSON object as a newline-terminated frame to the TCP socket.

    Silently ignores OSError — the client may have disconnected before the
    response arrived, and there is nothing useful to do in that case.
    """
    try:
        conn.sendall((json.dumps(obj) + "\n").encode())
    except OSError:
        pass


def handle_client(
    conn: socket.socket,
    fg: FetchGate,
    lock: threading.Lock,
    nm_dead: threading.Event,
) -> None:
    """Serve one TCP connection: read a JSON request, proxy it via NM, reply.

    Called in a daemon thread per connection. The lock serialises access to the
    NM channel — only one fetch() runs at a time. nm_dead is set permanently
    once the NM connection to Firefox is lost so subsequent requests fail fast
    without attempting a broken fetch().
    """
    try:
        # Read bytes until a newline arrives — same framing the Java host uses.
        # bytearray.extend() is O(chunk) rather than O(total), and find() stops
        # at the first newline so a pipelined second request is not consumed.
        buf = bytearray()
        line = None
        while True:
            chunk = conn.recv(RECV_SIZE)
            if not chunk:
                return  # client disconnected before sending a complete request
            buf.extend(chunk)
            if len(buf) > MAX_RECV_BYTES:
                _send(conn, {"error": "Request too large (exceeds 1 MB)"})
                return
            nl = buf.find(b"\n")
            if nl >= 0:
                line = bytes(buf[:nl])
                break

        try:
            req = json.loads(line.decode())
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            _send(conn, {"error": f"Malformed request: {exc}"})
            return

        with lock:
            if nm_dead.is_set():
                _send(conn, {"error": "NM connection to Firefox is gone"})
                return
            try:
                resp = fg.fetch(req)
            except FetchGateSizeError as exc:
                # Request too large for NM — connection is alive, do NOT set nm_dead.
                _send(conn, {"error": str(exc)})
                return
            except FetchGateError as exc:
                # NM pipe is broken — mark it so future clients fail fast.
                nm_dead.set()
                _send(conn, {"error": str(exc)})
                return

        _send(conn, resp)
    except OSError:
        pass
    finally:
        conn.close()


def serve(fg: FetchGate) -> None:
    """Bind the TCP server and dispatch each incoming connection to a thread."""
    lock = threading.Lock()
    nm_dead = threading.Event()

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            srv.bind((TCP_HOST, TCP_PORT))
        except OSError as exc:
            print(
                f"Cannot bind to {TCP_HOST}:{TCP_PORT}: {exc}\n"
                "  → Is the Java host or another instance already running?",
                file=sys.stderr,
            )
            sys.exit(1)
        srv.listen()
        print(f"Listening on {TCP_HOST}:{TCP_PORT}", file=sys.stderr)

        while True:
            try:
                conn, _ = srv.accept()
            except OSError:
                break
            threading.Thread(
                target=handle_client,
                args=(conn, fg, lock, nm_dead),
                daemon=True,
            ).start()


if __name__ == "__main__":
    fg = FetchGate()
    # FetchGate() redirects sys.stdout → sys.stderr. All print() calls below
    # this point go to stderr — the Firefox Browser Console shows them.
    serve(fg)
