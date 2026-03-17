import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Implements the Firefox Native Messaging framing protocol.
 *
 * Each message on the wire looks like:
 *
 *   [ 4 bytes, little-endian uint32: payload length ] [ N bytes: UTF-8 JSON ]
 *
 * This is the complete wire format — there is no envelope, header, or
 * session layer beyond the length prefix. Firefox enforces a hard 1 MB
 * cap on individual message size.
 *
 * References:
 *   https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Native_messaging
 */
public class NativeMessaging {

    /** Firefox's hard per-message size cap. */
    static final int MAX_MESSAGE_BYTES = 1_048_576; // 1 MB

    /**
     * Read one message from the given stream.
     * Blocks until all bytes arrive. Returns null on EOF or truncated stream.
     */
    public static String read(InputStream in) throws IOException {
        byte[] lenBuf = in.readNBytes(4);
        if (lenBuf.length < 4) return null;  // EOF mid-header

        int len = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (len < 0) return null;              // malformed: uint32 > 2^31 appears negative in Java
        if (len > MAX_MESSAGE_BYTES) return null; // malformed: exceeds Firefox's 1 MB hard cap
        byte[] payload = in.readNBytes(len);
        if (payload.length < len) return null;  // EOF mid-payload (truncated stream)
        return new String(payload, "UTF-8");
    }

    /**
     * Write one message to the given stream.
     * Throws IOException if the UTF-8 encoding of json exceeds the 1 MB Firefox limit.
     * The caller is responsible for external synchronisation if the stream
     * is shared across threads.
     */
    public static void write(OutputStream out, String json) throws IOException {
        byte[] payload = json.getBytes("UTF-8");
        if (payload.length > MAX_MESSAGE_BYTES) {
            throw new IOException("message too large: " + payload.length
                    + " bytes (Firefox limit: " + MAX_MESSAGE_BYTES + ")");
        }
        byte[] lenBuf = ByteBuffer.allocate(4)
                                  .order(ByteOrder.LITTLE_ENDIAN)
                                  .putInt(payload.length)
                                  .array();
        out.write(lenBuf);
        out.write(payload);
        out.flush();
    }
}
