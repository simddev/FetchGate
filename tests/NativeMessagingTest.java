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

        TestRunner.test("round-trip: simple JSON object", () -> {
            String msg = "{\"method\":\"GET\",\"url\":\"/api/v1/test\"}";
            Assert.equal(msg, writeAndRead(msg));
        });

        TestRunner.test("round-trip: empty JSON object", () -> {
            String msg = "{}";
            Assert.equal(msg, writeAndRead(msg));
        });

        TestRunner.test("round-trip: empty JSON array", () -> {
            String msg = "[]";
            Assert.equal(msg, writeAndRead(msg));
        });

        TestRunner.test("round-trip: JSON with escaped quotes and backslash", () -> {
            String msg = "{\"key\":\"value with \\\"quotes\\\" and \\\\backslash\"}";
            Assert.equal(msg, writeAndRead(msg));
        });

        TestRunner.test("round-trip: JSON with non-ASCII (UTF-8) characters", () -> {
            // Czech characters and emoji — common in real-world API responses
            String msg = "{\"name\":\"Řehoř Čapek\",\"icon\":\"\\uD83D\\uDE00\"}";
            Assert.equal(msg, writeAndRead(msg));
        });

        TestRunner.test("round-trip: large payload (~200 KB)", () -> {
            // Build a large JSON string to verify no size-related truncation
            StringBuilder sb = new StringBuilder("{\"data\":\"");
            for (int i = 0; i < 200_000; i++) sb.append('x');
            sb.append("\"}");
            String msg = sb.toString();
            Assert.equal(msg, writeAndRead(msg));
        });

        TestRunner.test("round-trip: multiple messages in sequence", () -> {
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

        TestRunner.test("read returns null on EOF (no bytes)", () -> {
            InputStream empty = new ByteArrayInputStream(new byte[0]);
            Assert.isNull(NativeMessaging.read(empty), "expected null on empty stream");
        });

        TestRunner.test("read returns null on EOF (partial length header)", () -> {
            // Only 3 of the 4 length bytes — truncated mid-header
            InputStream truncated = new ByteArrayInputStream(new byte[]{0x03, 0x00, 0x00});
            Assert.isNull(NativeMessaging.read(truncated), "expected null on truncated header");
        });

        TestRunner.test("length header byte order is little-endian", () -> {
            // Write a message and inspect the raw header bytes manually.
            // A payload of length 1 should produce header bytes [0x01, 0x00, 0x00, 0x00].
            String msg = "X"; // 1 byte UTF-8
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            NativeMessaging.write(buf, msg);
            byte[] raw = buf.toByteArray();

            // First 4 bytes = length header
            Assert.equal((byte) 0x01, raw[0]); // least-significant byte first
            Assert.equal((byte) 0x00, raw[1]);
            Assert.equal((byte) 0x00, raw[2]);
            Assert.equal((byte) 0x00, raw[3]);
            Assert.equal('X', (char) raw[4]);  // payload byte
        });

        TestRunner.test("length header encodes multi-byte length correctly", () -> {
            // A payload of 256 bytes: length = 0x00000100
            // Little-endian: [0x00, 0x01, 0x00, 0x00]
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 256; i++) sb.append('A');
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            NativeMessaging.write(buf, sb.toString());
            byte[] raw = buf.toByteArray();

            Assert.equal((byte) 0x00, raw[0]);
            Assert.equal((byte) 0x01, raw[1]);
            Assert.equal((byte) 0x00, raw[2]);
            Assert.equal((byte) 0x00, raw[3]);
        });

        TestRunner.test("write flushes: reader unblocks immediately after write", () -> {
            // Use piped streams to verify the flush call in write() causes the reader
            // to see the data without an explicit flush from the test.
            PipedOutputStream pout = new PipedOutputStream();
            PipedInputStream  pin  = new PipedInputStream(pout, 65536);

            String msg = "{\"flushed\":true}";
            NativeMessaging.write(pout, msg);           // must flush internally
            String received = NativeMessaging.read(pin); // must not block
            Assert.equal(msg, received);
        });
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static String writeAndRead(String msg) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        NativeMessaging.write(buf, msg);
        return NativeMessaging.read(new ByteArrayInputStream(buf.toByteArray()));
    }
}
