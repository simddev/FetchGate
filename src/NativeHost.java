import java.io.*;
import java.net.*;
import java.time.Instant;
import java.util.concurrent.*;

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
 *                      Sequential handling is intentional for the PoC: it keeps
 *                      the response-routing logic trivial (no per-request IDs needed).
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

    // Responses from Firefox land here; polled by the thread handling the current caller.
    private final BlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();

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
            log("Error stopping server: " + e.getMessage());
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
                        stop(); // closes the server socket, breaking the accept() loop
                        return;
                    }
                    log("← Firefox: " + msg);
                    responseQueue.put(msg);
                }
            } catch (Exception e) {
                log("stdin-reader fatal error: " + e.getMessage());
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

            String request = in.readLine();
            if (request == null || request.isBlank()) return;
            log("← Caller: " + request);

            // Discard any stale response left in the queue from a previous
            // timed-out request that arrived late.
            responseQueue.clear();

            // Forward the request to the extension via Native Messaging.
            // Catch write failures (oversized request, broken pipe) and return a
            // proper error JSON to the caller rather than silently closing the socket.
            try {
                synchronized (nativeOut) {
                    NativeMessaging.write(nativeOut, request);
                }
            } catch (IOException e) {
                log("Failed to forward request: " + e.getMessage());
                out.println("{\"error\":\"failed to forward to extension: " + e.getMessage() + "\"}");
                return;
            }
            log("→ Firefox: " + request);

            // Block until the extension responds or the timeout expires.
            String response = responseQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (response == null) {
                response = "{\"error\":\"timeout: no response from extension after " + timeoutMs + " ms\"}";
                log("Timed out waiting for Firefox response");
            }

            log("→ Caller: " + response);
            out.println(response);

        } catch (Exception e) {
            log("Error handling caller: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------

    private static void log(String msg) {
        // stderr is intentional: stdout is the Native Messaging channel and must
        // not be written to directly anywhere in this program.
        System.err.println("[FetchGate " + Instant.now() + "] " + msg);
    }
}
