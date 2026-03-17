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
            String msg = "{\"name\":\"Řehoř Čapek\",\"city\":\"Brno\"}";
            Assert.equal(msg, writeAndRead(msg));
        });

        TestRunner.test("round-trip: full request with all fields (method, url, headers, body)", () -> {
            String msg = "{\"method\":\"POST\",\"url\":\"/api/v3/cart\","
                    + "\"headers\":{\"Content-Type\":\"application/json\",\"X-CSRF-Token\":\"abc123\"},"
                    + "\"body\":\"{\\\"productId\\\":42,\\\"qty\\\":1}\"}";
            Assert.equal(msg, writeAndRead(msg));
        });

        TestRunner.test("round-trip: full response with status, headers, and body", () -> {
            String msg = "{\"status\":200,\"statusText\":\"OK\","
                    + "\"headers\":{\"content-type\":\"application/json\",\"x-request-id\":\"xyz\"},"
                    + "\"body\":\"{\\\"id\\\":42,\\\"name\\\":\\\"Widget\\\"}\"}";
            Assert.equal(msg, writeAndRead(msg));
        });

        TestRunner.test("round-trip: non-2xx status code (404) in response", () -> {
            String msg = "{\"status\":404,\"statusText\":\"Not Found\",\"headers\":{},\"body\":\"not found\"}";
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
            byte[] truncated = {0x0A, 0x00, 0x00};
            Assert.isNull(NativeMessaging.read(new ByteArrayInputStream(truncated)),
                    "expected null on truncated length header");
        });

        TestRunner.test("read returns null on EOF mid-payload (truncated payload)", () -> {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(new byte[]{10, 0, 0, 0}); // length header: 10
            buf.write(new byte[]{1, 2, 3, 4, 5}); // only 5 of the promised 10 bytes
            Assert.isNull(NativeMessaging.read(new ByteArrayInputStream(buf.toByteArray())),
                    "expected null on truncated payload");
        });

        TestRunner.test("read: zero-length message (header = 0) returns empty string", () -> {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(new byte[]{0, 0, 0, 0});
            Assert.equal("", NativeMessaging.read(new ByteArrayInputStream(buf.toByteArray())));
        });

        TestRunner.test("read: negative length (high bit set in header) returns null, does not throw", () -> {
            // The protocol uses uint32 but Java's int is signed.
            // A length with the high bit set (> 2^31-1) appears negative in Java.
            // readNBytes() would throw IllegalArgumentException on a negative argument,
            // so we must detect and handle this before calling it.
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(new byte[]{0x00, 0x00, 0x00, (byte) 0x80}); // 0x80000000 = -2147483648
            buf.write(new byte[]{1, 2, 3}); // some junk payload bytes
            Assert.isNull(NativeMessaging.read(new ByteArrayInputStream(buf.toByteArray())),
                    "expected null for malformed header with negative length");
        });

        TestRunner.test("read: length header exceeding 1 MB cap returns null, does not allocate", () -> {
            // A length in the range (MAX_MESSAGE_BYTES, Integer.MAX_VALUE] is positive in Java
            // but violates Firefox's 1 MB hard cap. Without this guard, readNBytes() would
            // attempt to allocate and block on hundreds of MB — potentially causing OOM.
            // 1_048_577 = MAX_MESSAGE_BYTES + 1, little-endian: 0x01 0x00 0x10 0x00
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(new byte[]{0x01, 0x00, 0x10, 0x00}); // 1_048_577 in little-endian
            buf.write(new byte[]{1, 2, 3}); // some junk payload bytes
            Assert.isNull(NativeMessaging.read(new ByteArrayInputStream(buf.toByteArray())),
                    "expected null for length header exceeding 1 MB cap");
        });

        // ── Byte order and size encoding ──────────────────────────────────────

        TestRunner.test("length header byte order is little-endian (1-byte payload)", () -> {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            NativeMessaging.write(buf, "X");
            byte[] raw = buf.toByteArray();

            Assert.equal((byte) 0x01, raw[0]);
            Assert.equal((byte) 0x00, raw[1]);
            Assert.equal((byte) 0x00, raw[2]);
            Assert.equal((byte) 0x00, raw[3]);
            Assert.equal('X', (char) raw[4]);
        });

        TestRunner.test("length header encodes 256-byte payload correctly", () -> {
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
            // '€' (U+20AC) = 3 UTF-8 bytes; 10 chars → 30 bytes
            char[] chars = new char[10];
            Arrays.fill(chars, '€');
            String msg = new String(chars);

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            NativeMessaging.write(buf, msg);
            byte[] raw = buf.toByteArray();

            int encodedLen = ByteBuffer.wrap(raw, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            Assert.equal(30, encodedLen);
            Assert.equal(msg, writeAndRead(msg));
        });

        // ── Size limit ────────────────────────────────────────────────────────

        TestRunner.test("write: payload exactly at 1 MB limit succeeds", () -> {
            // 1 048 576 = 0x00100000 → little-endian bytes: [0x00, 0x00, 0x10, 0x00]
            char[] chars = new char[NativeMessaging.MAX_MESSAGE_BYTES];
            Arrays.fill(chars, 'x');
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            NativeMessaging.write(buf, new String(chars));

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
