import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Tests for NativeMessaging — the Firefox Native Messaging framing protocol.
 *
 * The protocol is: [4-byte little-endian length][UTF-8 JSON payload].
 * These tests cover correctness of framing, byte order, encoding, and edge cases.
 */
public class NativeMessagingTest {

    public static void run() throws Exception {
        TestRunner.suite("NativeMessaging");

        // ── Round-trip correctness ────────────────────────────────────────────

        TestRunner.test("round-trip: simple JSON object", () -> {
            String msg = "{\"method\":\"GET\",\"url\":\"/api/v1/test\"}";
            Assert.equal(msg, writeAndRead(msg));
        });

        TestRunner.test("round-trip: empty JSON object", () -> {
            Assert.equal("{}", writeAndRead("{}"));
        });

        TestRunner.test("round-trip: empty JSON array", () -> {
            Assert.equal("[]", writeAndRead("[]"));
        });

        TestRunner.test("round-trip: empty string payload", () -> {
            Assert.equal("", writeAndRead(""));
        });

        TestRunner.test("round-trip: JSON with escaped quotes and backslash", () -> {
            String msg = "{\"key\":\"value with \\\"quotes\\\" and \\\\backslash\"}";
            Assert.equal(msg, writeAndRead(msg));
        });

        TestRunner.test("round-trip: JSON with non-ASCII (UTF-8) characters", () -> {
            // Czech characters — common in real-world API responses
            String msg = "{\"name\":\"Řehoř Čapek\",\"city\":\"Brno\"}";
            Assert.equal(msg, writeAndRead(msg));
        });

        TestRunner.test("round-trip: large payload (~200 KB)", () -> {
            char[] chars = new char[200_000];
            Arrays.fill(chars, 'x');
            String msg = "{\"data\":\"" + new String(chars) + "\"}";
            Assert.equal(msg, writeAndRead(msg));
        });

        TestRunner.test("round-trip: multiple messages in sequence share the same stream", () -> {
            String[] messages = {
                "{\"seq\":1,\"url\":\"/a\"}",
                "{\"seq\":2,\"url\":\"/b\"}",
                "{\"seq\":3,\"url\":\"/c\"}",
                "{\"seq\":4,\"url\":\"/d\"}",
                "{\"seq\":5,\"url\":\"/e\"}"
            };

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            for (String m : messages) NativeMessaging.write(buf, m);

            InputStream in = new ByteArrayInputStream(buf.toByteArray());
            for (String expected : messages) {
                Assert.equal(expected, NativeMessaging.read(in));
            }
        });

        // ── EOF / truncation handling ─────────────────────────────────────────

        TestRunner.test("read returns null on EOF (empty stream)", () -> {
            Assert.isNull(NativeMessaging.read(new ByteArrayInputStream(new byte[0])),
                    "expected null on empty stream");
        });

        TestRunner.test("read returns null on EOF mid-header (partial length header)", () -> {
            // Only 3 of the 4 length bytes arrive — connection dropped mid-header
            byte[] truncated = {0x0A, 0x00, 0x00};
            Assert.isNull(NativeMessaging.read(new ByteArrayInputStream(truncated)),
                    "expected null on truncated length header");
        });

        TestRunner.test("read returns null on EOF mid-payload (truncated payload)", () -> {
            // Header promises 10 bytes but stream ends after 5
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(new byte[]{10, 0, 0, 0}); // length header: 10
            buf.write(new byte[]{1, 2, 3, 4, 5}); // only 5 payload bytes
            Assert.isNull(NativeMessaging.read(new ByteArrayInputStream(buf.toByteArray())),
                    "expected null on truncated payload");
        });

        TestRunner.test("read: zero-length message (header = 0) returns empty string", () -> {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(new byte[]{0, 0, 0, 0}); // length = 0
            // no payload bytes
            Assert.equal("", NativeMessaging.read(new ByteArrayInputStream(buf.toByteArray())));
        });

        // ── Byte order and size encoding ──────────────────────────────────────

        TestRunner.test("length header byte order is little-endian (1-byte payload)", () -> {
            // Payload "X" = 1 byte → header must be [0x01, 0x00, 0x00, 0x00]
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            NativeMessaging.write(buf, "X");
            byte[] raw = buf.toByteArray();

            Assert.equal((byte) 0x01, raw[0]); // LSB first
            Assert.equal((byte) 0x00, raw[1]);
            Assert.equal((byte) 0x00, raw[2]);
            Assert.equal((byte) 0x00, raw[3]);
            Assert.equal('X', (char) raw[4]);
        });

        TestRunner.test("length header encodes 256-byte payload correctly", () -> {
            // 256 = 0x00000100 → little-endian: [0x00, 0x01, 0x00, 0x00]
            char[] chars = new char[256];
            Arrays.fill(chars, 'A');
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            NativeMessaging.write(buf, new String(chars));
            byte[] raw = buf.toByteArray();

            Assert.equal((byte) 0x00, raw[0]);
            Assert.equal((byte) 0x01, raw[1]);
            Assert.equal((byte) 0x00, raw[2]);
            Assert.equal((byte) 0x00, raw[3]);
        });

        TestRunner.test("length header uses UTF-8 byte count, not character count", () -> {
            // '€' (U+20AC) encodes to 3 UTF-8 bytes: E2 82 AC
            // 10 '€' chars = 10 characters but 30 UTF-8 bytes
            char[] chars = new char[10];
            Arrays.fill(chars, '€');
            String msg = new String(chars);

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            NativeMessaging.write(buf, msg);
            byte[] raw = buf.toByteArray();

            int encodedLen = ByteBuffer.wrap(raw, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            Assert.equal(30, encodedLen); // byte count (30), not char count (10)
            Assert.equal(msg, writeAndRead(msg)); // and round-trip is correct
        });

        // ── Size limit ────────────────────────────────────────────────────────

        TestRunner.test("write: payload exactly at 1 MB limit succeeds", () -> {
            // 1 048 576 = 0x00100000 → little-endian: [0x00, 0x00, 0x10, 0x00]
            char[] chars = new char[NativeMessaging.MAX_MESSAGE_BYTES];
            Arrays.fill(chars, 'x');
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            NativeMessaging.write(buf, new String(chars)); // must not throw

            byte[] raw = buf.toByteArray();
            Assert.equal((byte) 0x00, raw[0]);
            Assert.equal((byte) 0x00, raw[1]);
            Assert.equal((byte) 0x10, raw[2]);
            Assert.equal((byte) 0x00, raw[3]);
        });

        TestRunner.test("write: payload exceeding 1 MB throws IOException", () -> {
            char[] chars = new char[NativeMessaging.MAX_MESSAGE_BYTES + 1];
            Arrays.fill(chars, 'x');
            try {
                NativeMessaging.write(new ByteArrayOutputStream(), new String(chars));
                throw new AssertionError("expected IOException for oversized payload");
            } catch (IOException e) {
                Assert.contains(e.getMessage(), "too large");
            }
        });

        // ── Flush behaviour ───────────────────────────────────────────────────

        TestRunner.test("write flushes: reader on piped stream unblocks immediately", () -> {
            // PipedInputStream blocks until data is available. This verifies that
            // write() calls flush() so the reader does not have to.
            PipedOutputStream pout = new PipedOutputStream();
            PipedInputStream  pin  = new PipedInputStream(pout, 65536);

            String msg = "{\"flushed\":true}";
            NativeMessaging.write(pout, msg);
            Assert.equal(msg, NativeMessaging.read(pin));
        });
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static String writeAndRead(String msg) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        NativeMessaging.write(buf, msg);
        return NativeMessaging.read(new ByteArrayInputStream(buf.toByteArray()));
    }
}
