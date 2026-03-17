#!/usr/bin/env python3
"""Tests for host_py/fetchgate.py

Run from the project root:
    python3 host_py/test_fetchgate.py

No external dependencies — standard library only.
"""

import io
import json
import struct
import sys
import unittest
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fetchgate import FetchGate, FetchGateError


# ── Helpers ───────────────────────────────────────────────────────────────────

def nm_encode(obj: dict) -> bytes:
    """Encode a dict as a Native Messaging frame."""
    data = json.dumps(obj).encode("utf-8")
    return struct.pack("<I", len(data)) + data


def make_fg(*responses) -> tuple:
    """
    Return (fg, out_buf) where fg is a FetchGate wired to fake in-memory
    streams. responses are dicts that Firefox would send back.
    Bypasses __init__ to avoid touching sys.stdin / sys.stdout.
    """
    in_buf  = io.BytesIO(b"".join(nm_encode(r) for r in responses))
    out_buf = io.BytesIO()
    fg = FetchGate.__new__(FetchGate)
    fg._in  = in_buf
    fg._out = out_buf
    fg._seq = 0
    return fg, out_buf


def read_sent(out_buf: io.BytesIO) -> dict:
    """Decode the first NM frame that was written to out_buf."""
    out_buf.seek(0)
    raw = out_buf.read()
    length = struct.unpack("<I", raw[:4])[0]
    return json.loads(raw[4:4 + length].decode("utf-8"))


# ── NM framing: _write ────────────────────────────────────────────────────────

class TestWrite(unittest.TestCase):

    def test_produces_4_byte_little_endian_length_header(self):
        fg, out = make_fg()
        fg._write({"k": "v"})
        out.seek(0)
        header = out.read(4)
        length = struct.unpack("<I", header)[0]
        rest   = out.read()
        self.assertEqual(length, len(rest))

    def test_length_header_is_little_endian_not_big_endian(self):
        fg, out = make_fg()
        fg._write({"key": "value"})
        out.seek(0)
        header = out.read(4)
        payload_len = len(json.dumps({"key": "value"}).encode("utf-8"))
        self.assertEqual(struct.unpack("<I", header)[0], payload_len)
        # Verify it is NOT big-endian (they differ for any multi-byte length)
        if payload_len >= 256:
            self.assertNotEqual(struct.unpack(">I", header)[0], payload_len)

    def test_payload_is_valid_utf8_json(self):
        fg, out = make_fg()
        obj = {"method": "POST", "url": "/api", "body": "héllo"}
        fg._write(obj)
        out.seek(0)
        length = struct.unpack("<I", out.read(4))[0]
        parsed = json.loads(out.read(length).decode("utf-8"))
        self.assertEqual(parsed, obj)

    def test_raises_fetchgate_error_when_payload_exceeds_1mb(self):
        fg, _ = make_fg()
        with self.assertRaises(FetchGateError):
            fg._write({"body": "x" * (1024 * 1024 + 1)})

    def test_does_not_raise_at_exact_1mb_limit(self):
        fg, _ = make_fg()
        # Build a payload that is exactly 1 048 576 bytes when JSON-encoded
        # Key + overhead = ~12 bytes, so value length = 1 048 576 - 12
        target = 1_048_576
        key = "b"
        # Use json.dumps to measure overhead accurately (it adds ": " separators)
        overhead = len(json.dumps({key: ""}).encode("utf-8"))
        value = "x" * (target - overhead)
        obj = {key: value}
        data = json.dumps(obj).encode("utf-8")
        self.assertEqual(len(data), target)
        fg._write(obj)  # must not raise


# ── NM framing: _read ─────────────────────────────────────────────────────────

class TestRead(unittest.TestCase):

    def _fg_with_raw_input(self, raw: bytes) -> FetchGate:
        fg = FetchGate.__new__(FetchGate)
        fg._in  = io.BytesIO(raw)
        fg._out = io.BytesIO()
        fg._seq = 0
        return fg

    def test_round_trips_simple_object(self):
        fg, _ = make_fg({"status": 200, "body": "hello"})
        self.assertEqual(fg._read(), {"status": 200, "body": "hello"})

    def test_round_trips_non_ascii(self):
        fg, _ = make_fg({"body": "日本語"})
        self.assertEqual(fg._read(), {"body": "日本語"})

    def test_returns_none_on_empty_stream(self):
        fg = self._fg_with_raw_input(b"")
        self.assertIsNone(fg._read())

    def test_returns_none_on_partial_length_header(self):
        fg = self._fg_with_raw_input(b"\x05\x00")  # only 2 of 4 header bytes
        self.assertIsNone(fg._read())

    def test_returns_none_on_truncated_payload(self):
        fg = self._fg_with_raw_input(struct.pack("<I", 100) + b"x" * 10)
        self.assertIsNone(fg._read())

    def test_returns_none_when_length_exceeds_1mb_cap(self):
        fg = self._fg_with_raw_input(struct.pack("<I", 2 * 1024 * 1024))
        self.assertIsNone(fg._read())

    def test_returns_none_on_malformed_json(self):
        bad = b"this is not json"
        fg = self._fg_with_raw_input(struct.pack("<I", len(bad)) + bad)
        self.assertIsNone(fg._read())

    def test_reads_multiple_sequential_frames(self):
        fg, _ = make_fg({"n": 1}, {"n": 2}, {"n": 3})
        self.assertEqual(fg._read(), {"n": 1})
        self.assertEqual(fg._read(), {"n": 2})
        self.assertEqual(fg._read(), {"n": 3})
        self.assertIsNone(fg._read())  # EOF


# ── fetch(): envelope and ID correlation ─────────────────────────────────────

class TestFetch(unittest.TestCase):

    def test_envelope_contains_fg_id_as_first_field(self):
        fg, out = make_fg({"__fg_id": 1, "status": 200, "body": "ok"})
        fg.fetch({"url": "/"})
        env = read_sent(out)
        self.assertIn("__fg_id", env)
        self.assertEqual(env["__fg_id"], 1)
        # __fg_id must be first — check raw JSON starts with it
        out.seek(0)
        raw_json = out.read()[4:].decode("utf-8")
        self.assertTrue(raw_json.startswith('{"__fg_id":'))

    def test_envelope_wraps_spec_as_json_string_in_req_field(self):
        spec = {"method": "GET", "url": "/api/data"}
        fg, out = make_fg({"__fg_id": 1, "status": 200, "body": "ok"})
        fg.fetch(spec)
        env = read_sent(out)
        self.assertIn("req", env)
        self.assertIsInstance(env["req"], str)   # must be a string, not an object
        self.assertEqual(json.loads(env["req"]), spec)

    def test_fg_id_stripped_from_returned_response(self):
        fg, _ = make_fg({"__fg_id": 1, "status": 200, "body": "hello"})
        resp = fg.fetch({"url": "/"})
        self.assertNotIn("__fg_id", resp)
        self.assertEqual(resp["status"], 200)
        self.assertEqual(resp["body"], "hello")

    def test_seq_increments_per_call(self):
        fg, out = make_fg(
            {"__fg_id": 1, "status": 200, "body": "a"},
            {"__fg_id": 2, "status": 201, "body": "b"},
        )
        fg.fetch({"url": "/a"})
        out.seek(0); out.truncate(0)
        fg.fetch({"url": "/b"})
        env2 = read_sent(out)
        self.assertEqual(env2["__fg_id"], 2)

    def test_stale_reply_discarded_correct_one_returned(self):
        # Firefox sends a reply for id=99 before the one for id=1
        fg, _ = make_fg(
            {"__fg_id": 99, "status": 500, "body": "stale"},
            {"__fg_id": 1,  "status": 200, "body": "correct"},
        )
        resp = fg.fetch({"url": "/"})
        self.assertEqual(resp["status"], 200)
        self.assertEqual(resp["body"], "correct")

    def test_multiple_stale_replies_before_match(self):
        fg, _ = make_fg(
            {"__fg_id": 7,  "body": "stale-a"},
            {"__fg_id": 42, "body": "stale-b"},
            {"__fg_id": 1,  "status": 200, "body": "match"},
        )
        resp = fg.fetch({"url": "/"})
        self.assertEqual(resp["body"], "match")

    def test_error_response_returned_not_raised(self):
        fg, _ = make_fg({"__fg_id": 1, "error": "no tab is armed"})
        resp = fg.fetch({"url": "/"})
        self.assertEqual(resp, {"error": "no tab is armed"})

    def test_raises_fetchgate_error_on_eof_before_reply(self):
        fg, _ = make_fg()  # no responses — EOF immediately
        with self.assertRaises(FetchGateError):
            fg.fetch({"url": "/"})

    def test_raises_fetchgate_error_on_malformed_frame_from_firefox(self):
        bad = b"not json at all"
        fg = FetchGate.__new__(FetchGate)
        fg._in  = io.BytesIO(struct.pack("<I", len(bad)) + bad)
        fg._out = io.BytesIO()
        fg._seq = 0
        with self.assertRaises(FetchGateError):
            fg.fetch({"url": "/"})

    def test_spec_with_all_fields(self):
        spec = {
            "method": "POST",
            "url": "/submit",
            "headers": {"Content-Type": "application/json"},
            "body": '{"x":1}',
            "credentials": "include",
        }
        fg, out = make_fg({"__fg_id": 1, "status": 201, "body": ""})
        fg.fetch(spec)
        env = read_sent(out)
        self.assertEqual(json.loads(env["req"]), spec)

    def test_spec_with_non_ascii_url(self):
        spec = {"url": "/search?q=日本語"}
        fg, out = make_fg({"__fg_id": 1, "status": 200, "body": ""})
        fg.fetch(spec)
        env = read_sent(out)
        decoded = json.loads(env["req"])
        self.assertEqual(decoded["url"], "/search?q=日本語")


# ── sys.stdout redirect ───────────────────────────────────────────────────────

class TestStdoutRedirect(unittest.TestCase):

    def test_init_redirects_sys_stdout_to_sys_stderr(self):
        original_stdout = sys.stdout
        original_stdin  = sys.stdin
        try:
            fake_out_buf = io.BytesIO()
            fake_in_buf  = io.BytesIO()

            class FakeOut:
                buffer = fake_out_buf

            class FakeIn:
                buffer = fake_in_buf

            sys.stdout = FakeOut()
            sys.stdin  = FakeIn()

            fg = FetchGate()

            self.assertIs(sys.stdout, sys.stderr)
        finally:
            sys.stdout = original_stdout
            sys.stdin  = original_stdin

    def test_sys_dunder_stdout_preserved_after_redirect(self):
        """sys.__stdout__ must still point to the real original stdout."""
        # Python always keeps __stdout__ as the original; verify we haven't
        # broken this by redirecting sys.stdout in __init__.
        self.assertIsNotNone(sys.__stdout__)
        # After any FetchGate construction in other tests, __stdout__ should
        # still be a writable stream (not stderr, which is read-only in some envs)
        self.assertTrue(hasattr(sys.__stdout__, "write"))


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    loader = unittest.TestLoader()
    suite  = loader.loadTestsFromModule(sys.modules[__name__])
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    sys.exit(0 if result.wasSuccessful() else 1)
