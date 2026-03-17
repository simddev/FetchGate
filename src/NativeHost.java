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

    static final int PORT    = 9919;
    static final int TIMEOUT = 30_000; // ms

    private final InputStream  nativeIn;
    private final OutputStream nativeOut;

    // Responses from Firefox land here; polled by the thread handling the current caller.
    private final BlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();

    public NativeHost(InputStream nativeIn, OutputStream nativeOut) {
        this.nativeIn  = nativeIn;
        this.nativeOut = nativeOut;
    }

    public void start() throws Exception {
        startStdinReader();

        try (ServerSocket server = new ServerSocket(PORT, 50, InetAddress.getLoopbackAddress())) {
            log("TCP server listening on localhost:" + PORT);
            while (true) {
                Socket client = server.accept();
                log("Caller connected: " + client.getRemoteSocketAddress());
                handleCaller(client);
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
                        System.exit(0);
                    }
                    log("← Firefox: " + msg);
                    responseQueue.put(msg);
                }
            } catch (Exception e) {
                log("stdin-reader fatal error: " + e.getMessage());
                System.exit(1);
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
            synchronized (nativeOut) {
                NativeMessaging.write(nativeOut, request);
            }
            log("→ Firefox: " + request);

            // Block until the extension responds or the timeout expires.
            String response = responseQueue.poll(TIMEOUT, TimeUnit.MILLISECONDS);
            if (response == null) {
                response = "{\"error\":\"timeout: no response from extension after " + TIMEOUT + " ms\"}";
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
