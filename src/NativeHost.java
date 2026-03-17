import java.io.*;
import java.net.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core of the FetchGate native host.
 *
 * Two interfaces:
 *
 *   1. Firefox  — System.in / System.out, framed with NativeMessaging protocol
 *   2. Caller   — TCP server on localhost:PORT, newline-delimited JSON
 *
 * Threading model:
 *
 *   Main thread      — accepts TCP connections; handles them one at a time.
 *                      Sequential handling is intentional: only one request is
 *                      in flight to Firefox at any given time. Per-request IDs
 *                      (envelope __fg_id) guard against stale late replies.
 *
 *   stdin-reader     — daemon thread; reads NM frames from Firefox continuously
 *                      and drops them onto responseQueue.
 *
 * Request lifecycle:
 *
 *   caller --TCP--> host --NM stdout--> Firefox/background --tabs.sendMessage-->
 *   content_script --fetch()--> response --NM stdin--> host --TCP--> caller
 */
public class NativeHost {

    static final int DEFAULT_PORT    = 9919;
    static final int DEFAULT_TIMEOUT = 30_000; // ms

    private final InputStream  nativeIn;
    private final OutputStream nativeOut;
    private final int          port;
    private final int          timeoutMs;

    // Sentinel value placed in responseQueue by the stdin-reader when Firefox disconnects.
    // Allows handleCaller to return an immediate error instead of waiting for the full timeout.
    static final String SHUTDOWN_SENTINEL = "\0SHUTDOWN\0";

    // Responses from Firefox land here; polled by the thread handling the current caller.
    private final BlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();

    // Monotonically increasing counter. Each outbound request gets a unique ID so the
    // host can detect and discard stale responses left over from timed-out requests.
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    // Cleared by stdin-reader when Firefox disconnects (EOF or read error).
    // handleCaller checks this before clearing the queue and before writing,
    // to avoid consuming the SHUTDOWN_SENTINEL prematurely on persistent connections.
    private volatile boolean firefoxAlive = true;

    // Set once the ServerSocket is bound; lets tests discover the OS-assigned port.
    private volatile int          boundPort    = -1;
    private volatile ServerSocket serverSocket;

    /** Production constructor — uses the fixed port and timeout. */
    public NativeHost(InputStream nativeIn, OutputStream nativeOut) {
        this(nativeIn, nativeOut, DEFAULT_PORT, DEFAULT_TIMEOUT);
    }

    /** Test constructor — accepts an arbitrary port (0 = OS-assigned) and timeout. */
    NativeHost(InputStream nativeIn, OutputStream nativeOut, int port, int timeoutMs) {
        this.nativeIn  = nativeIn;
        this.nativeOut = nativeOut;
        this.port      = port;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Returns the port the server is actually listening on.
     * Only valid after start() has been called from another thread.
     * Poll until > 0 to wait for readiness.
     */
    public int getBoundPort() { return boundPort; }

    /**
     * Shut down the TCP server (interrupts the accept() loop).
     * Safe to call multiple times. The stdin-reader daemon thread exits when
     * the JVM does (or when its pipe is closed in tests).
     */
    public void stop() {
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            log("Error stopping server: " + e);
        }
    }

    public void start() throws Exception {
        startStdinReader();

        serverSocket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        boundPort = serverSocket.getLocalPort();
        log("TCP server listening on localhost:" + boundPort);

        ServerSocket ss = serverSocket;
        try (ss) {
            while (true) {
                try {
                    Socket client = serverSocket.accept();
                    log("Caller connected: " + client.getRemoteSocketAddress());
                    handleCaller(client);
                } catch (SocketException e) {
                    if (serverSocket.isClosed()) break; // stop() was called
                    throw e;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Background thread: drain Firefox → responseQueue
    // -------------------------------------------------------------------------

    private void startStdinReader() {
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    String msg = NativeMessaging.read(nativeIn);
                    if (msg == null) {
                        log("Native Messaging connection closed by Firefox — shutting down.");
                        firefoxAlive = false;
                        responseQueue.offer(SHUTDOWN_SENTINEL); // unblock any waiting caller immediately
                        stop(); // closes the server socket, breaking the accept() loop
                        return;
                    }
                    log("← Firefox: " + truncate(msg));
                    responseQueue.put(msg);
                }
            } catch (Exception e) {
                log("stdin-reader fatal error: " + e);
                firefoxAlive = false;
                responseQueue.offer(SHUTDOWN_SENTINEL); // unblock any waiting caller immediately
                stop();
            }
        }, "stdin-reader");

        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // Handle one TCP caller: read request → forward to Firefox → wait → reply
    // -------------------------------------------------------------------------

    private void handleCaller(Socket client) {
        try (client;
             BufferedReader in  = new BufferedReader(
                                      new InputStreamReader(client.getInputStream(), "UTF-8"));
             PrintWriter    out = new PrintWriter(
                                      new OutputStreamWriter(client.getOutputStream(), "UTF-8"),
                                      /*autoFlush=*/ true)) {

            String request;
            while ((request = in.readLine()) != null) {
                // Normalise: strip leading/trailing whitespace before any further handling.
                // injectFgId() assumes the JSON starts with '{'; without this strip a request
                // like " {...}" would produce {"__fg_id":N,{...}} — malformed JSON.
                request = request.strip();
                if (request.isBlank()) continue;

                // The protocol requires complete JSON objects. Reject anything that doesn't
                // start with '{' (not an object) or doesn't end with '}' (incomplete/truncated).
                // injectFgId() operates on json.substring(0, length-1); requiring a closing '}'
                // ensures it always removes the final '}' rather than arbitrary content.
                if (!request.startsWith("{") || !request.endsWith("}")) {
                    log("Rejecting malformed request (not a complete JSON object): " + truncate(request));
                    out.println("{\"error\":\"invalid request: expected a complete JSON object\"}");
                    continue; // keep the connection open; the caller can retry
                }

                log("← Caller: " + truncate(request));

                // Fast-fail if Firefox has already disconnected: skip the queue
                // clear (which would consume the SHUTDOWN_SENTINEL) and return
                // an error immediately instead of waiting for the full timeout.
                if (!firefoxAlive) {
                    log("Firefox already disconnected — returning error to caller");
                    out.println("{\"error\":\"connection to Firefox was closed\"}");
                    break;
                }

                // Discard any stale response left in the queue from a previous
                // timed-out request that arrived late.
                responseQueue.clear();

                // Assign a unique ID and inject it into the JSON. background.js echoes the
                // ID back in the response, so the host can verify each reply belongs to the
                // current request and discard any stale replies from prior timed-out requests.
                // This closes the race window left by the break-after-timeout approach alone.
                int reqId  = requestCounter.incrementAndGet();
                String tagged = buildEnvelope(request, reqId);

                // Forward the tagged request to the extension via Native Messaging.
                // Catch write failures (oversized request, broken pipe) and return a
                // proper error JSON to the caller rather than silently closing the socket.
                try {
                    synchronized (nativeOut) {
                        NativeMessaging.write(nativeOut, tagged);
                    }
                } catch (IOException e) {
                    log("Failed to forward request: " + e);
                    out.println("{\"error\":\"failed to forward to extension: " + jsonEscape(e.getMessage()) + "\"}");
                    break; // can't forward any more requests; close this connection
                }
                log("→ Firefox: " + truncate(tagged));

                // Poll for the response that carries the expected __fg_id.
                // Responses with the wrong ID are stale replies from a prior timed-out
                // request; discard them and keep waiting within the same timeout budget.
                // background.js always puts __fg_id first, so startsWith is unambiguous.
                String idPrefix = "{\"__fg_id\":" + reqId;
                long   deadline = System.currentTimeMillis() + timeoutMs;
                String response = null;
                while (true) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break; // timed out
                    String candidate = responseQueue.poll(remaining, TimeUnit.MILLISECONDS);
                    if (candidate == null) break; // timed out
                    if (SHUTDOWN_SENTINEL.equals(candidate)) { response = candidate; break; }
                    if (candidate.startsWith(idPrefix + ",") || candidate.startsWith(idPrefix + "}")) {
                        response = candidate; break;
                    }
                    log("Discarding stale response (wrong __fg_id): " + truncate(candidate));
                }

                if (response == null) {
                    response = "{\"error\":\"timeout: no response from extension after " + timeoutMs + " ms\"}";
                    log("Timed out waiting for Firefox response");
                    out.println(response);
                    // Break: a timed-out request may still produce a late reply from Firefox.
                    // Closing forces the caller to reconnect; the stale reply will arrive with
                    // the old reqId and be discarded by the ID check on the next connection.
                    break;
                } else if (SHUTDOWN_SENTINEL.equals(response)) {
                    response = "{\"error\":\"connection to Firefox was closed\"}";
                    log("Firefox disconnected — returning error to caller");
                    out.println(response);
                    break;
                }

                // Strip the internal __fg_id tracking field before returning to the caller.
                response = stripFgId(response);
                log("→ Caller: " + truncate(response));
                out.println(response);
            }

        } catch (Exception e) {
            log("Error handling caller: " + e);
        }
    }

    /**
     * Wrap the caller's raw JSON in a controlled envelope:
     *   {"__fg_id":N,"req":"ESCAPED_JSON"}
     *
     * The caller's JSON is encoded as a JSON string value. This means
     * background.js receives a well-structured outer object regardless of
     * whether the caller's JSON is valid, and delegates structural validation
     * to JavaScript's native JSON.parse() on the "req" field.
     *
     * __fg_id is the FIRST field so background.js can echo it back and the
     * host can match responses with a startsWith() check rather than contains().
     * This eliminates the false-positive risk of a nested __fg_id inside the
     * response payload matching the wrong request ID.
     */
    private static String buildEnvelope(String requestJson, int id) {
        return "{\"__fg_id\":" + id + ",\"req\":" + jsonStringEncode(requestJson) + "}";
    }

    /**
     * Encode a Java string as a JSON string literal (including surrounding quotes).
     * Handles all characters that must be escaped in JSON strings.
     */
    private static String jsonStringEncode(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else          sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    /**
     * Remove the __fg_id tracking field injected by this host and echoed by
     * background.js before forwarding the response to the caller.
     * background.js places __fg_id first, so a prefix regex suffices.
     *
     * Examples:
     *   {"__fg_id":1,"status":200}  →  {"status":200}
     *   {"__fg_id":1}               →  {}
     */
    private static String stripFgId(String json) {
        return json.replaceFirst("\"__fg_id\":\\d+,?", "");
    }

    // -------------------------------------------------------------------------

    private static void log(String msg) {
        // stderr is intentional: stdout is the Native Messaging channel and must
        // not be written to directly anywhere in this program.
        System.err.println("[FetchGate " + Instant.now() + "] " + msg);
    }

    /**
     * Minimal JSON string escaper for hand-built error payloads.
     * Prevents a quote or backslash in an exception message from producing malformed JSON.
     */
    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /** Truncate a payload string for logging to avoid flooding stderr with large bodies. */
    private static String truncate(String s) {
        if (s == null) return "<null>";
        int limit = 120;
        return s.length() <= limit ? s
                : s.substring(0, limit) + "…[" + s.length() + " chars total]";
    }
}
