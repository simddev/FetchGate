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

    /**
     * Read one message from the given stream.
     * Blocks until all bytes arrive. Returns null on EOF (Firefox disconnected).
     */
    public static String read(InputStream in) throws IOException {
        byte[] lenBuf = in.readNBytes(4);
        if (lenBuf.length < 4) return null;  // EOF

        int len = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).getInt();
        byte[] payload = in.readNBytes(len);
        return new String(payload, "UTF-8");
    }

    /**
     * Write one message to the given stream.
     * The caller is responsible for external synchronisation if the stream
     * is shared across threads.
     */
    public static void write(OutputStream out, String json) throws IOException {
        byte[] payload = json.getBytes("UTF-8");
        byte[] lenBuf  = ByteBuffer.allocate(4)
                                   .order(ByteOrder.LITTLE_ENDIAN)
                                   .putInt(payload.length)
                                   .array();
        out.write(lenBuf);
        out.write(payload);
        out.flush();
    }
}
