import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;

/**
 * Tests for NativeHost — the TCP↔NativeMessaging bridge.
 *
 * Each test constructs a NativeHost wired to in-process pipes that stand in for
 * Firefox's stdin/stdout, starts it on a random OS-assigned port (port 0), and
 * exercises it via a real TCP connection on localhost.
 *
 * Helper: Fixture sets up the plumbing common to most tests.
 *
 * Request ID protocol:
 *   NativeHost injects a unique "__fg_id" field into every request it forwards
 *   to Firefox. background.js echoes the field in the response. The host matches
 *   responses by ID and discards any stale replies left over from timed-out
 *   requests. Tests use Fixture helpers (readForwardedId, tagged) to participate
 *   in this protocol from the "Firefox side" of the pipe.
 */
public class NativeHostTest {

    /** Timeout used for all hosts under test — short enough to keep the suite fast. */
    private static final int TEST_TIMEOUT_MS = 600;

    public static void run() throws Exception {
        TestRunner.suite("NativeHost");

        // ── Binding and lifecycle ─────────────────────────────────────────────

        TestRunner.test("server binds to loopback address only", () -> {
            try (Fixture f = new Fixture()) {
                try (Socket s = new Socket("127.0.0.1", f.host.getBoundPort())) {
                    Assert.isTrue(s.isConnected(), "should connect on 127.0.0.1");
                }
            }
        });

        TestRunner.test("getBoundPort() returns -1 before start()", () -> {
            PipedOutputStream pout = new PipedOutputStream();
            PipedInputStream  pin  = new PipedInputStream(pout, 1024);
            NativeHost host = new NativeHost(pin, pout, 0, 100);
            Assert.equal(-1, host.getBoundPort());
            pout.close();
            pin.close();
        });

        TestRunner.test("stop() terminates the accept loop", () -> {
            try (Fixture f = new Fixture()) {
                int port = f.host.getBoundPort();
                f.host.stop();
                Thread.sleep(150);

                try {
                    new Socket("127.0.0.1", port).close();
                    throw new AssertionError("expected connection to be refused after stop()");
                } catch (ConnectException e) {
                    // expected
                }
            }
        });

        TestRunner.test("stop() before start() is a no-op (does not throw)", () -> {
            PipedOutputStream pout = new PipedOutputStream();
            PipedInputStream  pin  = new PipedInputStream(pout, 1024);
            NativeHost host = new NativeHost(pin, pout, 0, 100);
            host.stop();
            pout.close();
            pin.close();
        });

        TestRunner.test("stop() is idempotent — calling it twice does not throw", () -> {
            try (Fixture f = new Fixture()) {
                f.host.stop();
                f.host.stop();
            }
        });

        // ── Core request/response flow ────────────────────────────────────────

        TestRunner.test("simple GET request-response round-trip", () -> {
            try (Fixture f = new Fixture()) {
                String request  = "{\"method\":\"GET\",\"url\":\"/api/v1/orders\"}";
                String response = "{\"status\":200,\"body\":\"[]\"}";

                Future<String> callerResult = f.sendAsCaller(request);

                int id = f.readForwardedId(request); // verifies original fields intact
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(response, id));

                Assert.equal(response, callerResult.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("POST request with all fields (method, url, headers, body)", () -> {
            // Exercises the real-world path: caller sends a full spec with body,
            // host forwards it untouched, Firefox executes fetch() with those params.
            try (Fixture f = new Fixture()) {
                String request = "{\"method\":\"POST\",\"url\":\"/api/v3/cart\","
                        + "\"headers\":{\"Content-Type\":\"application/json\","
                        + "\"X-CSRF-Token\":\"tok123\"},"
                        + "\"body\":\"{\\\"productId\\\":42}\"}";
                String response = "{\"status\":201,\"statusText\":\"Created\","
                        + "\"headers\":{\"content-type\":\"application/json\"},"
                        + "\"body\":\"{\\\"orderId\\\":99}\"}";

                Future<String> result = f.sendAsCaller(request);
                int id = f.readForwardedId(request);
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(response, id));
                Assert.equal(response, result.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("non-2xx response (404) is returned verbatim to caller", () -> {
            // The host must not filter or alter error responses — the caller decides
            // what to do with a 404, 403, 500 etc.
            try (Fixture f = new Fixture()) {
                String response = "{\"status\":404,\"statusText\":\"Not Found\","
                        + "\"headers\":{\"content-type\":\"text/plain\"},\"body\":\"no such resource\"}";

                Future<String> result = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/missing\"}");
                int id = f.readForwardedId();
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(response, id));
                Assert.equal(response, result.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("request with non-ASCII content is forwarded and returned correctly", () -> {
            // Real API responses routinely contain UTF-8 text (names, addresses, etc.).
            try (Fixture f = new Fixture()) {
                String request  = "{\"method\":\"GET\",\"url\":\"/api/user/42\","
                        + "\"headers\":{\"Accept-Language\":\"cs-CZ\"}}";
                String response = "{\"status\":200,\"body\":"
                        + "\"{\\\"name\\\":\\\"Řehoř Čapek\\\",\\\"city\\\":\\\"Brně\\\"}\"}";

                Future<String> result = f.sendAsCaller(request);
                int id = f.readForwardedId(request);
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(response, id));
                Assert.equal(response, result.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("request fields are preserved when forwarded to Firefox", () -> {
            // The host injects __fg_id but must not alter any caller-supplied fields.
            // readForwardedId(request) strips __fg_id and asserts the remainder matches.
            try (Fixture f = new Fixture()) {
                String request = "{\"method\":\"POST\",\"url\":\"/submit\",\"body\":\"data\"}";
                f.sendAsCaller(request);
                int id = f.readForwardedId(request); // assertion happens inside
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged("{\"status\":200}", id));
            }
        });

        TestRunner.test("response forwarded to caller verbatim (no modification)", () -> {
            // The host strips __fg_id from the Firefox response before passing it to
            // the caller. All other fields must be forwarded without alteration.
            try (Fixture f = new Fixture()) {
                String response = "{\"status\":403,\"statusText\":\"Forbidden\",\"body\":\"nope\"}";
                Future<String> result = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/\"}");
                int id = f.readForwardedId();
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(response, id));
                Assert.equal(response, result.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("response with JSON-encoded special characters forwarded correctly", () -> {
            // JSON-encoded \n, \t, unicode — no literal newlines, so readLine() doesn't truncate.
            try (Fixture f = new Fixture()) {
                String response = "{\"status\":200,\"body\":\"line1\\nline2\\ttab\\u00e9\"}";
                Future<String> result = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/\"}");
                int id = f.readForwardedId();
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(response, id));
                Assert.equal(response, result.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("large request and response (~100 KB each) round-trip successfully", () -> {
            try (Fixture f = new Fixture()) {
                char[] body = new char[50_000];
                Arrays.fill(body, 'x');
                String request  = "{\"method\":\"POST\",\"url\":\"/up\",\"body\":\"" + new String(body) + "\"}";
                Arrays.fill(body, 'y');
                String response = "{\"status\":200,\"body\":\"" + new String(body) + "\"}";

                Future<String> result = f.sendAsCaller(request);
                int id = f.readForwardedId(request);
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(response, id));
                Assert.equal(response, result.get(3, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("multiple sequential requests all produce correct responses", () -> {
            try (Fixture f = new Fixture()) {
                for (int i = 0; i < 5; i++) {
                    String req  = "{\"seq\":" + i + ",\"url\":\"/item/" + i + "\"}";
                    String resp = "{\"status\":200,\"seq\":" + i + "}";
                    Future<String> result = f.sendAsCaller(req);
                    int id = f.readForwardedId(req);
                    NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(resp, id));
                    Assert.equal(resp, result.get(2, TimeUnit.SECONDS));
                }
            }
        });

        // ── Timeout and stale state ───────────────────────────────────────────

        TestRunner.test("timeout: returns error JSON when Firefox does not respond", () -> {
            try (Fixture f = new Fixture()) {
                Future<String> result = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/\"}");
                String reply = result.get(TEST_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
                Assert.contains(reply, "error");
                Assert.contains(reply, "timeout");
            }
        });

        TestRunner.test("timeout error JSON includes the configured timeout value in ms", () -> {
            try (Fixture f = new Fixture()) {
                Future<String> result = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/\"}");
                String reply = result.get(TEST_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
                Assert.contains(reply, String.valueOf(TEST_TIMEOUT_MS));
            }
        });

        TestRunner.test("stale response is discarded by ID mismatch — next request gets correct response", () -> {
            // The ID-based fix: the host discards any response whose __fg_id does not
            // match the current request, regardless of timing. The stale reply from a
            // previous timed-out request has the old ID and is rejected even if it
            // arrives before the clear() of the next request.
            try (Fixture f = new Fixture()) {
                Future<String> result1 = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/first\"}");
                int staleId = f.readForwardedId();
                Assert.contains(result1.get(TEST_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS), "timeout");

                // Firefox delivers a late reply tagged with the OLD ID.
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged("{\"status\":200,\"body\":\"late\"}", staleId));
                Thread.sleep(60); // let stdin-reader enqueue the stale response

                // The next request must receive its own response (new ID), not the stale one.
                String expected = "{\"status\":200,\"body\":\"correct\"}";
                Future<String> result2 = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/second\"}");
                int id2 = f.readForwardedId();
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(expected, id2));
                Assert.equal(expected, result2.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("unsolicited Firefox message arriving between requests is cleared", () -> {
            // Firefox could theoretically send a message when no request is in flight.
            // Such a message carries no __fg_id, so the host's ID check discards it
            // even if the responseQueue.clear() misses it in a race. Both defences work.
            try (Fixture f = new Fixture()) {
                // No request in flight — Firefox sends something spontaneously (no __fg_id).
                NativeMessaging.write(f.firefoxResponseWriter, "{\"unsolicited\":true}");
                Thread.sleep(60); // let stdin-reader enqueue it

                String expected = "{\"status\":200,\"body\":\"real\"}";
                Future<String> result = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/\"}");
                int id = f.readForwardedId();
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(expected, id));
                Assert.equal(expected, result.get(2, TimeUnit.SECONDS));
            }
        });

        // ── Failure modes ─────────────────────────────────────────────────────

        TestRunner.test("oversized request (>1 MB) returns error JSON to caller, not silent EOF", () -> {
            // If the request cannot be forwarded to Firefox (e.g. > 1MB limit),
            // the caller must receive a descriptive error, not a sudden connection close.
            try (Fixture f = new Fixture()) {
                char[] body = new char[NativeMessaging.MAX_MESSAGE_BYTES]; // 1 MB of 'x'
                Arrays.fill(body, 'x');
                // The JSON envelope pushes this just over the 1 MB limit
                String oversized = "{\"method\":\"POST\",\"url\":\"/\",\"body\":\"" + new String(body) + "\"}";

                Future<String> result = f.sendAsCaller(oversized);
                String reply = result.get(2, TimeUnit.SECONDS);

                Assert.notNull(reply, "caller must receive a response, not a silent connection close");
                Assert.contains(reply, "error");
                Assert.contains(reply, "too large");
            }
        });

        TestRunner.test("broken Firefox pipe: caller receives error JSON, not silent EOF", () -> {
            // If the connection to Firefox dies (pipe broken), writing to nativeOut
            // throws IOException. The caller must receive an error, not hang or get EOF.
            try (Fixture f = new Fixture()) {
                // Close the reading end of the NM output pipe to simulate Firefox dying
                f.hostRequestReader.close();
                Thread.sleep(50);

                Future<String> result = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/\"}");
                String reply = result.get(2, TimeUnit.SECONDS);

                Assert.notNull(reply, "caller must receive a response, not hang");
                Assert.contains(reply, "error");
            }
        });

        TestRunner.test("firefox disconnects during in-flight request — caller receives error quickly, not after full timeout", () -> {
            try (Fixture f = new Fixture()) {
                Future<String> result = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/\"}");
                f.readForwardedId(); // consume so the pipe does not stall

                f.firefoxResponseWriter.close(); // simulate Firefox dying mid-request

                long start = System.currentTimeMillis();
                String reply = result.get(TEST_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS);
                long elapsed = System.currentTimeMillis() - start;

                Assert.notNull(reply, "caller must receive a response, not hang indefinitely");
                Assert.contains(reply, "error");
                // Sentinel unblocks the poll immediately — should arrive well before the full timeout
                Assert.isTrue(elapsed < TEST_TIMEOUT_MS / 2,
                        "expected fast error via sentinel, but got delay of " + elapsed + " ms");
            }
        });

        // ── Malformed / edge-case input ───────────────────────────────────────

        TestRunner.test("blank request line is ignored — host keeps running", () -> {
            try (Fixture f = new Fixture()) {
                try (Socket s = new Socket("127.0.0.1", f.host.getBoundPort());
                     PrintWriter    out = new PrintWriter(s.getOutputStream(), true);
                     BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
                    out.println("");
                    s.setSoTimeout(300);
                    try {
                        Assert.isNull(in.readLine(), "expected no response to blank request");
                    } catch (SocketTimeoutException e) { /* also acceptable */ }
                }
                try (Socket s2 = new Socket("127.0.0.1", f.host.getBoundPort())) {
                    Assert.isTrue(s2.isConnected(), "host should still accept after blank request");
                }
            }
        });

        TestRunner.test("request with leading/trailing whitespace is trimmed and processed correctly", () -> {
            // injectFgId() requires the JSON to start with '{'. Without stripping,
            // " {\"url\":\"...\"}" would yield {"__fg_id":1,{"url":"..."}} — malformed JSON
            // that the extension would fail to parse. After stripping it must work normally.
            try (Fixture f = new Fixture()) {
                String bare     = "{\"method\":\"GET\",\"url\":\"/trimmed\"}";
                String padded   = "   " + bare + "   ";
                String response = "{\"status\":200,\"body\":\"ok\"}";

                Future<String> result = f.sendAsCaller(padded);
                int id = f.readForwardedId(bare); // stripped content must match bare
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(response, id));
                Assert.equal(response, result.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("non-object request (not starting with '{') returns error, connection stays open", () -> {
            // The protocol requires JSON objects. Arrays, bare strings, and other JSON
            // types are rejected with a descriptive error; the connection stays open
            // so the caller can retry.
            try (Fixture f = new Fixture()) {
                try (Socket s = new Socket("127.0.0.1", f.host.getBoundPort());
                     PrintWriter    out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
                     BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"))) {

                    out.println("[\"url\",\"/api\"]");
                    String reply = in.readLine();
                    Assert.notNull(reply, "caller must receive an error response");
                    Assert.contains(reply, "error");

                    // Connection must still be open — a valid follow-up request must work.
                    String req      = "{\"method\":\"GET\",\"url\":\"/retry\"}";
                    String expected = "{\"status\":200,\"body\":\"ok\"}";
                    out.println(req);
                    int id = Fixture.extractFgId(NativeMessaging.read(f.hostRequestReader));
                    NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(expected, id));
                    Assert.equal(expected, in.readLine());
                }
            }
        });

        TestRunner.test("request containing reserved __fg_id field returns error, connection stays open", () -> {
            // A caller-supplied __fg_id would produce a duplicate key in the forwarded JSON.
            // JavaScript last-key-wins would make background.js echo the caller's value,
            // the host's ID check would never match, and the request would always time out.
            // The host must reject it immediately with an error instead.
            try (Fixture f = new Fixture()) {
                try (Socket s = new Socket("127.0.0.1", f.host.getBoundPort());
                     PrintWriter    out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
                     BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"))) {

                    out.println("{\"__fg_id\":99,\"method\":\"GET\",\"url\":\"/\"}");
                    String reply = in.readLine();
                    Assert.notNull(reply, "caller must receive an error response");
                    Assert.contains(reply, "error");
                    Assert.contains(reply, "__fg_id");

                    // Connection must stay open.
                    String req      = "{\"method\":\"GET\",\"url\":\"/after\"}";
                    String expected = "{\"status\":200,\"body\":\"recovered\"}";
                    out.println(req);
                    int id = Fixture.extractFgId(NativeMessaging.read(f.hostRequestReader));
                    NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(expected, id));
                    Assert.equal(expected, in.readLine());
                }
            }
        });

        TestRunner.test("whitespace-only request is discarded — host keeps running", () -> {
            try (Fixture f = new Fixture()) {
                try (Socket s = new Socket("127.0.0.1", f.host.getBoundPort());
                     PrintWriter    out = new PrintWriter(s.getOutputStream(), true);
                     BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
                    out.println("     ");
                    s.setSoTimeout(300);
                    try {
                        Assert.isNull(in.readLine(), "expected no response to whitespace request");
                    } catch (SocketTimeoutException e) { /* also acceptable */ }
                }
                try (Socket s2 = new Socket("127.0.0.1", f.host.getBoundPort())) {
                    Assert.isTrue(s2.isConnected(), "host should still accept after whitespace request");
                }
            }
        });

        TestRunner.test("request with embedded newline — only first line is forwarded (protocol limitation)", () -> {
            // The TCP caller-side protocol is newline-delimited. Callers must send
            // compact single-line JSON. This test documents what happens if they don't.
            try (Fixture f = new Fixture()) {
                String firstLine  = "{\"method\":\"GET\",";
                String secondLine = "\"url\":\"/api\"}";

                f.sendAsCaller(firstLine + "\n" + secondLine);

                String forwarded = NativeMessaging.read(f.hostRequestReader);
                // The forwarded string has __fg_id injected; strip it and compare to original.
                Assert.equal(firstLine, Fixture.stripFgId(forwarded));

                int id = Fixture.extractFgId(forwarded);
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged("{\"status\":200}", id));
            }
        });

        TestRunner.test("persistent connection: multiple requests on the same socket", () -> {
            try (Fixture f = new Fixture()) {
                try (Socket s = new Socket("127.0.0.1", f.host.getBoundPort());
                     PrintWriter    out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
                     BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"))) {

                    for (int i = 0; i < 3; i++) {
                        String req  = "{\"seq\":" + i + ",\"url\":\"/item/" + i + "\"}";
                        String resp = "{\"status\":200,\"seq\":" + i + "}";
                        out.println(req);
                        int id = Fixture.extractFgId(NativeMessaging.read(f.hostRequestReader));
                        NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(resp, id));
                        Assert.equal(resp, in.readLine());
                    }
                }
            }
        });

        TestRunner.test("caller disconnect before sending — host keeps running", () -> {
            try (Fixture f = new Fixture()) {
                new Socket("127.0.0.1", f.host.getBoundPort()).close();
                Thread.sleep(100);
                try (Socket s = new Socket("127.0.0.1", f.host.getBoundPort())) {
                    Assert.isTrue(s.isConnected(), "host should accept after abrupt caller disconnect");
                }
            }
        });

        // ── Sentinel and error path coverage ─────────────────────────────────

        TestRunner.test("stdin-reader I/O error (not EOF) unblocks caller immediately via sentinel", () -> {
            // If nativeIn throws an IOException mid-read (rather than returning EOF),
            // the stdin-reader catch block must offer SHUTDOWN_SENTINEL so a waiting
            // caller does not have to wait out the full timeout.
            //
            // We use a CountDownLatch-gated InputStream: it blocks until signaled,
            // then throws — giving us control over when the 'crash' happens.
            CountDownLatch crashLatch = new CountDownLatch(1);
            InputStream controlled = new InputStream() {
                @Override public int read() throws IOException { throw new IOException("unreachable"); }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    try { crashLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    throw new IOException("simulated pipe crash");
                }
            };

            PipedOutputStream hostOut    = new PipedOutputStream();
            PipedInputStream  outReader  = new PipedInputStream(hostOut, 65536);
            NativeHost        host       = new NativeHost(controlled, hostOut, 0, TEST_TIMEOUT_MS);
            ExecutorService   exec       = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r); t.setDaemon(true); return t;
            });
            exec.submit(() -> { host.start(); return null; });
            for (int i = 0; i < 100 && host.getBoundPort() <= 0; i++) Thread.sleep(20);

            try {
                // Caller sends a request — forwarded to Firefox via hostOut.
                Future<String> result = exec.submit(() -> {
                    try (Socket         s   = new Socket("127.0.0.1", host.getBoundPort());
                         PrintWriter    out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
                         BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"))) {
                        out.println("{\"method\":\"GET\",\"url\":\"/\"}");
                        return in.readLine();
                    }
                });
                // Consume the forwarded request so the caller isn't blocked on the write side.
                NativeMessaging.read(outReader);

                // Trigger the simulated pipe crash — stdin-reader should offer sentinel.
                long start = System.currentTimeMillis();
                crashLatch.countDown();

                String reply   = result.get(TEST_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS);
                long   elapsed = System.currentTimeMillis() - start;

                Assert.notNull(reply, "caller must receive a response");
                Assert.contains(reply, "error");
                Assert.isTrue(elapsed < TEST_TIMEOUT_MS / 2,
                        "expected fast error via sentinel, but got delay of " + elapsed + " ms");
            } finally {
                host.stop();
                exec.shutdownNow();
                try { outReader.close(); } catch (IOException ignored) {}
            }
        });

        TestRunner.test("timeout closes TCP connection — reconnect and retry succeeds", () -> {
            // After a timeout the host breaks the connection so a stale late reply
            // cannot be consumed by the next request. The caller must reconnect;
            // the reconnected request must be served correctly.
            try (Fixture f = new Fixture()) {
                // First connection: request times out → connection is closed by host.
                try (Socket         s   = new Socket("127.0.0.1", f.host.getBoundPort());
                     PrintWriter    out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
                     BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"))) {

                    out.println("{\"method\":\"GET\",\"url\":\"/first\"}");
                    f.readForwardedId(); // consume the forwarded request
                    Assert.contains(in.readLine(), "timeout");

                    // Host closed the connection — second readLine must return EOF.
                    s.setSoTimeout(500);
                    try {
                        Assert.isNull(in.readLine(), "expected EOF after timeout closes connection");
                    } catch (SocketTimeoutException e) { /* also acceptable */ }
                }

                // Reconnect on a fresh socket: must be served correctly.
                String expected = "{\"status\":200,\"body\":\"recovered\"}";
                Future<String> result = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/second\"}");
                int id = f.readForwardedId();
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(expected, id));
                Assert.equal(expected, result.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("oversized request on persistent connection: connection closed, host keeps accepting", () -> {
            // An oversized payload (> 1 MB) must cause the host to send an error and
            // close that connection, but the server socket must remain open for new callers.
            try (Fixture f = new Fixture()) {
                char[] body = new char[NativeMessaging.MAX_MESSAGE_BYTES];
                Arrays.fill(body, 'x');
                String oversized = "{\"method\":\"POST\",\"url\":\"/\",\"body\":\"" + new String(body) + "\"}";

                try (Socket         s   = new Socket("127.0.0.1", f.host.getBoundPort());
                     PrintWriter    out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
                     BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"))) {

                    out.println(oversized);
                    String reply = in.readLine();
                    Assert.contains(reply, "error");
                    Assert.contains(reply, "too large");

                    // Host should have closed the connection after a write failure.
                    s.setSoTimeout(500);
                    try {
                        Assert.isNull(in.readLine(), "expected EOF — host must close connection after oversized request");
                    } catch (SocketTimeoutException e) { /* also acceptable */ }
                }

                // Host must still serve a subsequent caller on a new connection.
                String req      = "{\"method\":\"GET\",\"url\":\"/recovery\"}";
                String expected = "{\"status\":200,\"body\":\"ok\"}";
                Future<String> result = f.sendAsCaller(req);
                int id = f.readForwardedId(req);
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(expected, id));
                Assert.equal(expected, result.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("caller disconnects mid-wait: host recovers and serves subsequent callers", () -> {
            // If the caller closes the TCP socket while the host is blocked waiting for
            // Firefox's response, the host must not crash — it must detect the closed
            // socket and resume accepting new connections.
            try (Fixture f = new Fixture()) {
                // Caller connects and sends a request.
                Socket      s   = new Socket("127.0.0.1", f.host.getBoundPort());
                PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
                out.println("{\"method\":\"GET\",\"url\":\"/slow\"}");

                // Confirm the request was forwarded to Firefox.
                String forwarded = NativeMessaging.read(f.hostRequestReader);
                Assert.notNull(forwarded, "request must be forwarded");
                int id = Fixture.extractFgId(forwarded);

                // Caller disconnects abruptly while host is blocked in poll().
                s.close();

                // Firefox now responds — host's println will fail silently (PrintWriter
                // swallows the broken-pipe error), then in.readLine() returns null → loop exits.
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged("{\"status\":200}", id));
                Thread.sleep(150); // let handleCaller finish and accept() resume

                // Host must still accept and serve a new caller.
                String expected = "{\"status\":201,\"body\":\"next\"}";
                Future<String> result = f.sendAsCaller("{\"method\":\"POST\",\"url\":\"/next\"}");
                int id2 = f.readForwardedId();
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(expected, id2));
                Assert.equal(expected, result.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("two consecutive timeouts each close their connection; host keeps accepting", () -> {
            // Each timeout causes the host to close that TCP connection. The host must
            // resume accepting after each, and a subsequent successful request must work.
            try (Fixture f = new Fixture()) {
                for (int i = 0; i < 2; i++) {
                    Future<String> result = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/t" + i + "\"}");
                    f.readForwardedId(); // consume forwarded request; no response written → timeout
                    Assert.contains(result.get(TEST_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS), "timeout");
                }
                // After two timeouts on separate connections, a fresh connection succeeds.
                String expected = "{\"status\":200,\"body\":\"finally\"}";
                Future<String> result = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/good\"}");
                int id = f.readForwardedId();
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(expected, id));
                Assert.equal(expected, result.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("late reply after timeout cannot contaminate next request (no sleep, tight race)", () -> {
            // Definitive regression test for reply misdelivery. The stale reply carries
            // the old request's __fg_id; the host discards it by ID mismatch regardless
            // of timing. No Thread.sleep() between the stale reply and the next request
            // to maximise the chance of exposing any remaining race.
            try (Fixture f = new Fixture()) {
                Future<String> result1 = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/timeout\"}");
                int staleId = f.readForwardedId(); // capture ID of the timed-out request
                Assert.contains(result1.get(TEST_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS), "timeout");

                // Firefox delivers a late reply tagged with the STALE ID — must be discarded.
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged("{\"status\":200,\"body\":\"STALE\"}", staleId));

                // Next request must receive its own correctly-tagged response, never the stale one.
                String expected = "{\"status\":201,\"body\":\"correct\"}";
                Future<String> result2 = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/next\"}");
                int id2 = f.readForwardedId();
                NativeMessaging.write(f.firefoxResponseWriter, Fixture.tagged(expected, id2));
                Assert.equal(expected, result2.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("firefox EOF closes the TCP server — no new connections accepted", () -> {
            // When the Native Messaging pipe reaches EOF, stdin-reader calls stop().
            // The TCP server socket must close, refusing further connections.
            try (Fixture f = new Fixture()) {
                int port = f.host.getBoundPort();

                // Simulate Firefox closing the NM connection.
                f.firefoxResponseWriter.close();

                // Give stdin-reader time to detect EOF and call stop().
                Thread.sleep(200);

                try {
                    new Socket("127.0.0.1", port).close();
                    throw new AssertionError("expected connection refused after Firefox EOF");
                } catch (ConnectException e) {
                    // expected — server socket was closed by stop()
                }
            }
        });
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    /**
     * Wires up a NativeHost with two pairs of in-process pipes:
     *
     *   firefoxResponseWriter  → (nativeIn)  → NativeHost
     *   NativeHost → (nativeOut) → hostRequestReader
     *
     * Tests write Firefox responses to firefoxResponseWriter and read the
     * requests that the host forwarded via hostRequestReader.
     *
     * The host starts on a random OS-assigned port (port 0) and is stopped
     * when close() is called.
     */
    static class Fixture implements AutoCloseable {

        final PipedOutputStream firefoxResponseWriter;
        final PipedInputStream  hostRequestReader;
        final NativeHost        host;

        private final PipedInputStream  firefoxResponseReader;
        private final PipedOutputStream hostRequestWriter;
        private final ExecutorService   executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });

        Fixture() throws Exception {
            firefoxResponseWriter = new PipedOutputStream();
            firefoxResponseReader = new PipedInputStream(firefoxResponseWriter, 65536);

            hostRequestWriter = new PipedOutputStream();
            hostRequestReader = new PipedInputStream(hostRequestWriter, 65536);

            host = new NativeHost(firefoxResponseReader, hostRequestWriter, 0, TEST_TIMEOUT_MS);
            executor.submit(() -> { host.start(); return null; });
            awaitReady();
        }

        Future<String> sendAsCaller(String requestJson) {
            return executor.submit(() -> {
                try (Socket         s   = new Socket("127.0.0.1", host.getBoundPort());
                     PrintWriter    out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
                     BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"))) {
                    out.println(requestJson);
                    return in.readLine();
                }
            });
        }

        /**
         * Reads one request forwarded to Firefox via the NM pipe.
         * Returns the __fg_id that the host injected into it.
         */
        int readForwardedId() throws IOException {
            return extractFgId(NativeMessaging.read(hostRequestReader));
        }

        /**
         * Reads one request forwarded to Firefox, asserts that its content
         * (with __fg_id stripped) equals {@code expectedOriginal}, and returns
         * the __fg_id. Use this to verify that the host preserves caller-supplied
         * fields while injecting the tracking field.
         */
        int readForwardedId(String expectedOriginal) throws IOException {
            String json = NativeMessaging.read(hostRequestReader);
            Assert.equal(expectedOriginal, stripFgId(json));
            return extractFgId(json);
        }

        /**
         * Extract the numeric __fg_id value from a JSON string.
         * Throws if the field is not present.
         */
        static int extractFgId(String json) {
            int idx = json.indexOf("\"__fg_id\":");
            int start = idx + 10; // length of "\"__fg_id\":"
            int end = start;
            while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
            return Integer.parseInt(json.substring(start, end));
        }

        /**
         * Remove the __fg_id tracking field from a JSON string.
         * Mirrors the logic in NativeHost.stripFgId.
         */
        static String stripFgId(String json) {
            return json.replaceFirst("\"__fg_id\":\\d+,?", "");
        }

        /**
         * Wrap a Firefox response JSON with the __fg_id field as the first key,
         * mimicking what background.js sends back to the native host:
         *   { __fg_id: N, ...response }
         *
         * Examples:
         *   tagged("{\"status\":200}", 3)  →  {"__fg_id":3,"status":200}
         *   tagged("{}", 3)                →  {"__fg_id":3}
         */
        static String tagged(String responseJson, int fgId) {
            String field = "\"__fg_id\":" + fgId;
            if (responseJson.length() <= 2) return "{" + field + "}";
            return "{" + field + "," + responseJson.substring(1);
        }

        private void awaitReady() throws InterruptedException {
            for (int i = 0; i < 100; i++) {
                if (host.getBoundPort() > 0) return;
                Thread.sleep(20);
            }
            throw new IllegalStateException("NativeHost did not start within 2 s");
        }

        @Override
        public void close() {
            host.stop();
            executor.shutdownNow();
            try { firefoxResponseWriter.close(); } catch (IOException ignored) {}
            try { hostRequestReader.close();     } catch (IOException ignored) {}
        }
    }
}
