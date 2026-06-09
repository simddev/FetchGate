#!/usr/bin/env python3
"""Tests for host_py/fetchgate_tcp_host.py

Run from the project root:
    python3 host_py/test_fetchgate_tcp_host.py

No external dependencies  -  standard library only.
"""

import json
import os
import socket
import sys
import threading
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fetchgate import FetchGateError, FetchGateSizeError
from fetchgate_tcp_host import _send, handle_client


# ── Helpers ───────────────────────────────────────────────────────────────────


class MockFetchGate:
    """Minimal FetchGate stand-in that returns pre-configured responses."""

    def __init__(self, responses: list):
        self._responses = list(responses)
        self.calls: list[dict] = []

    def fetch(self, spec: dict) -> dict:
        self.calls.append(spec)
        if not self._responses:
            raise FetchGateError("no responses configured")
        r = self._responses.pop(0)
        if isinstance(r, Exception):
            raise r
        return r


def run_handle_client(
    raw_request: bytes,
    fg: MockFetchGate,
    nm_dead: threading.Event | None = None,
) -> dict | None:
    """Send raw_request to handle_client in a thread; return parsed response.

    Uses socket.socketpair() so no network port is needed. Returns None if
    handle_client closed the connection without sending any data.
    """
    if nm_dead is None:
        nm_dead = threading.Event()
    lock = threading.Lock()
    server_sock, client_sock = socket.socketpair()
    try:
        client_sock.sendall(raw_request)
        t = threading.Thread(
            target=handle_client,
            args=(server_sock, fg, lock, nm_dead),
        )
        t.start()

        buf = b""
        while True:
            chunk = client_sock.recv(65536)
            if not chunk:
                break  # server closed connection
            buf += chunk
            if b"\n" in buf:
                break
        t.join(timeout=2)
        return json.loads(buf.decode().strip()) if buf.strip() else None
    finally:
        client_sock.close()


def req(obj: dict) -> bytes:
    """Encode a dict as a newline-terminated TCP request frame."""
    return (json.dumps(obj) + "\n").encode()


# ── _send ─────────────────────────────────────────────────────────────────────


class TestSend(unittest.TestCase):

    def test_writes_json_with_newline(self):
        a, b = socket.socketpair()
        try:
            _send(a, {"status": 200, "body": "ok"})
            a.close()
            data = b.recv(1024)
            self.assertTrue(data.endswith(b"\n"))
            self.assertEqual(json.loads(data.decode().strip()), {"status": 200, "body": "ok"})
        finally:
            b.close()

    def test_closed_socket_does_not_raise(self):
        a, b = socket.socketpair()
        b.close()
        _send(a, {"k": "v"})  # must not raise
        a.close()

    def test_non_ascii_encoded_correctly(self):
        a, b = socket.socketpair()
        try:
            _send(a, {"name": "Mléčné"})
            a.close()
            data = b.recv(1024)
            parsed = json.loads(data.decode().strip())
            self.assertEqual(parsed["name"], "Mléčné")
        finally:
            b.close()


# ── handle_client: happy path ─────────────────────────────────────────────────


class TestHandleClientHappyPath(unittest.TestCase):

    def test_valid_request_is_forwarded_and_response_returned(self):
        fg = MockFetchGate([{"status": 200, "body": "hello"}])
        resp = run_handle_client(req({"method": "GET", "url": "/"}), fg)
        self.assertEqual(resp, {"status": 200, "body": "hello"})

    def test_request_spec_passed_to_fg_fetch_unchanged(self):
        spec = {"method": "POST", "url": "/api", "body": "data"}
        fg = MockFetchGate([{"status": 201, "body": ""}])
        run_handle_client(req(spec), fg)
        self.assertEqual(fg.calls[0], spec)

    def test_error_response_from_fg_passed_through_as_is(self):
        """FetchGate returns {"error": "..."} for browser-side errors  -  pass it through."""
        fg = MockFetchGate([{"error": "no tab is armed"}])
        resp = run_handle_client(req({"url": "/"}), fg)
        self.assertEqual(resp, {"error": "no tab is armed"})

    def test_non_ascii_response_preserved(self):
        fg = MockFetchGate([{"status": 200, "body": "Mléčné a chlazené"}])
        resp = run_handle_client(req({"url": "/"}), fg)
        self.assertEqual(resp["body"], "Mléčné a chlazené")

    def test_js_mode_spec_forwarded(self):
        fg = MockFetchGate([{"result": "page title"}])
        resp = run_handle_client(req({"js": "return document.title"}), fg)
        self.assertEqual(resp, {"result": "page title"})


# ── handle_client: bad requests ───────────────────────────────────────────────


class TestHandleClientBadRequests(unittest.TestCase):

    def test_malformed_json_returns_error(self):
        fg = MockFetchGate([])
        resp = run_handle_client(b"not json at all\n", fg)
        self.assertIn("error", resp)
        self.assertIn("Malformed", resp["error"])
        self.assertEqual(fg.calls, [])  # fetch() never called

    def test_invalid_utf8_returns_error(self):
        fg = MockFetchGate([])
        resp = run_handle_client(b"\xff\xfe\n", fg)
        self.assertIn("error", resp)
        self.assertEqual(fg.calls, [])

    def test_oversized_request_returns_error_without_setting_nm_dead(self):
        # A request that exceeds the 1 MB limit must be rejected before fg.fetch()
        # is called. If it reached fg.fetch(), _write() would raise FetchGateError
        # and handle_client would set nm_dead, killing the host for all future clients
        # even though the NM connection to Firefox is perfectly alive.
        nm_dead = threading.Event()
        lock = threading.Lock()
        fg = MockFetchGate([{"status": 200, "body": "should not reach"}])
        server_sock, client_sock = socket.socketpair()

        t = threading.Thread(
            target=handle_client,
            args=(server_sock, fg, lock, nm_dead),
        )
        t.start()

        # 1 MB + 1 byte of data followed by a newline.
        try:
            client_sock.sendall(b"x" * (1_048_576 + 1) + b"\n")
        except OSError:
            pass  # server may close the socket before all bytes are consumed

        buf = b""
        client_sock.settimeout(2.0)
        try:
            while True:
                chunk = client_sock.recv(65536)
                if not chunk:
                    break
                buf += chunk
                if b"\n" in buf:
                    break
        except OSError:
            pass
        finally:
            client_sock.close()

        t.join(timeout=2)

        self.assertTrue(buf.strip(), "server must send an error response")
        resp = json.loads(buf.decode().strip())
        self.assertIn("error", resp)
        self.assertFalse(nm_dead.is_set(), "nm_dead must NOT be set for an oversized request")
        self.assertEqual(fg.calls, [])

    def test_client_disconnect_before_newline_returns_nothing(self):
        """Connection closed before a complete request  -  no response expected."""
        fg = MockFetchGate([])
        nm_dead = threading.Event()
        lock = threading.Lock()
        server_sock, client_sock = socket.socketpair()
        try:
            # Close without sending anything  -  server gets immediate EOF.
            client_sock.close()
            t = threading.Thread(
                target=handle_client,
                args=(server_sock, fg, lock, nm_dead),
            )
            t.start()
            t.join(timeout=2)
            self.assertFalse(t.is_alive())
            self.assertEqual(fg.calls, [])
        except OSError:
            pass


# ── handle_client: NM failure ─────────────────────────────────────────────────


class TestHandleClientNMFailure(unittest.TestCase):

    def test_fetchgate_size_error_returns_error_without_setting_nm_dead(self):
        # FetchGateSizeError means the envelope was too large for NM  -  the
        # connection to Firefox is still alive. nm_dead must NOT be set.
        fg = MockFetchGate([FetchGateSizeError("Outgoing message too large: 1048700 bytes")])
        nm_dead = threading.Event()
        resp = run_handle_client(req({"url": "/"}), fg, nm_dead=nm_dead)
        self.assertIn("error", resp)
        self.assertFalse(nm_dead.is_set())

    def test_fetchgate_error_sets_nm_dead_and_returns_error(self):
        fg = MockFetchGate([FetchGateError("NM pipe closed")])
        nm_dead = threading.Event()
        resp = run_handle_client(req({"url": "/"}), fg, nm_dead=nm_dead)
        self.assertIn("error", resp)
        self.assertTrue(nm_dead.is_set())

    def test_nm_dead_already_set_returns_error_without_calling_fetch(self):
        fg = MockFetchGate([{"status": 200, "body": "should not reach"}])
        nm_dead = threading.Event()
        nm_dead.set()
        resp = run_handle_client(req({"url": "/"}), fg, nm_dead=nm_dead)
        self.assertIn("error", resp)
        self.assertIn("gone", resp["error"])
        self.assertEqual(fg.calls, [])  # fetch() never called

    def test_nm_dead_set_by_first_request_reported_on_second(self):
        nm_dead = threading.Event()
        lock = threading.Lock()

        fg1 = MockFetchGate([FetchGateError("NM gone")])
        fg2 = MockFetchGate([{"status": 200, "body": "should not reach"}])

        # First request kills the NM connection.
        a1, b1 = socket.socketpair()
        b1.sendall(req({"url": "/first"}))
        t1 = threading.Thread(target=handle_client, args=(a1, fg1, lock, nm_dead))
        t1.start()
        buf = b""
        while True:
            chunk = b1.recv(1024)
            if not chunk: break
            buf += chunk
            if b"\n" in buf: break
        t1.join(timeout=2)
        b1.close()
        resp1 = json.loads(buf.decode().strip())
        self.assertIn("error", resp1)
        self.assertTrue(nm_dead.is_set())

        # Second request should fail fast without touching fg2.
        a2, b2 = socket.socketpair()
        b2.sendall(req({"url": "/second"}))
        t2 = threading.Thread(target=handle_client, args=(a2, fg2, lock, nm_dead))
        t2.start()
        buf = b""
        while True:
            chunk = b2.recv(1024)
            if not chunk: break
            buf += chunk
            if b"\n" in buf: break
        t2.join(timeout=2)
        b2.close()
        resp2 = json.loads(buf.decode().strip())
        self.assertIn("error", resp2)
        self.assertEqual(fg2.calls, [])


# ── Entry point ───────────────────────────────────────────────────────────────


if __name__ == "__main__":
    loader = unittest.TestLoader()
    suite = loader.loadTestsFromModule(sys.modules[__name__])
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    sys.exit(0 if result.wasSuccessful() else 1)
