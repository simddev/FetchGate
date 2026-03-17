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

                Assert.equal(request, NativeMessaging.read(f.hostRequestReader));
                NativeMessaging.write(f.firefoxResponseWriter, response);

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
                Assert.equal(request, NativeMessaging.read(f.hostRequestReader));
                NativeMessaging.write(f.firefoxResponseWriter, response);
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
                NativeMessaging.read(f.hostRequestReader);
                NativeMessaging.write(f.firefoxResponseWriter, response);
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
                Assert.equal(request, NativeMessaging.read(f.hostRequestReader));
                NativeMessaging.write(f.firefoxResponseWriter, response);
                Assert.equal(response, result.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("request forwarded to Firefox verbatim (no modification)", () -> {
            try (Fixture f = new Fixture()) {
                String request = "{\"method\":\"POST\",\"url\":\"/submit\",\"body\":\"data\"}";
                f.sendAsCaller(request);
                Assert.equal(request, NativeMessaging.read(f.hostRequestReader));
                NativeMessaging.write(f.firefoxResponseWriter, "{\"status\":200}");
            }
        });

        TestRunner.test("response forwarded to caller verbatim (no modification)", () -> {
            try (Fixture f = new Fixture()) {
                String response = "{\"status\":403,\"statusText\":\"Forbidden\",\"body\":\"nope\"}";
                Future<String> result = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/\"}");
                NativeMessaging.read(f.hostRequestReader);
                NativeMessaging.write(f.firefoxResponseWriter, response);
                Assert.equal(response, result.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("response with JSON-encoded special characters forwarded correctly", () -> {
            // JSON-encoded \n, \t, unicode — no literal newlines, so readLine() doesn't truncate.
            try (Fixture f = new Fixture()) {
                String response = "{\"status\":200,\"body\":\"line1\\nline2\\ttab\\u00e9\"}";
                Future<String> result = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/\"}");
                NativeMessaging.read(f.hostRequestReader);
                NativeMessaging.write(f.firefoxResponseWriter, response);
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
                Assert.equal(request, NativeMessaging.read(f.hostRequestReader));
                NativeMessaging.write(f.firefoxResponseWriter, response);
                Assert.equal(response, result.get(3, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("multiple sequential requests all produce correct responses", () -> {
            try (Fixture f = new Fixture()) {
                for (int i = 0; i < 5; i++) {
                    String req  = "{\"seq\":" + i + ",\"url\":\"/item/" + i + "\"}";
                    String resp = "{\"status\":200,\"seq\":" + i + "}";
                    Future<String> result = f.sendAsCaller(req);
                    Assert.equal(req,  NativeMessaging.read(f.hostRequestReader));
                    NativeMessaging.write(f.firefoxResponseWriter, resp);
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

        TestRunner.test("stale response from timed-out request is cleared before next request", () -> {
            try (Fixture f = new Fixture()) {
                Future<String> result1 = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/first\"}");
                NativeMessaging.read(f.hostRequestReader);
                Assert.contains(result1.get(TEST_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS), "timeout");

                // Firefox delivers a late reply for the timed-out request
                NativeMessaging.write(f.firefoxResponseWriter, "{\"status\":200,\"body\":\"late\"}");
                Thread.sleep(60);

                // The next request must receive its own response, not the stale one
                String expected = "{\"status\":200,\"body\":\"correct\"}";
                Future<String> result2 = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/second\"}");
                NativeMessaging.read(f.hostRequestReader);
                NativeMessaging.write(f.firefoxResponseWriter, expected);
                Assert.equal(expected, result2.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("unsolicited Firefox message arriving between requests is cleared", () -> {
            // Firefox could theoretically send a message when no request is in flight.
            // The responseQueue.clear() before each request must discard it so it
            // does not contaminate the next response.
            try (Fixture f = new Fixture()) {
                // No request in flight — Firefox sends something spontaneously
                NativeMessaging.write(f.firefoxResponseWriter, "{\"unsolicited\":true}");
                Thread.sleep(60); // let stdin-reader enqueue it

                String expected = "{\"status\":200,\"body\":\"real\"}";
                Future<String> result = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/\"}");
                NativeMessaging.read(f.hostRequestReader);
                NativeMessaging.write(f.firefoxResponseWriter, expected);
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
                NativeMessaging.read(f.hostRequestReader);

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
                Assert.equal(firstLine, forwarded);

                NativeMessaging.write(f.firefoxResponseWriter, "{\"status\":200}");
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
                        Assert.equal(req, NativeMessaging.read(f.hostRequestReader));
                        NativeMessaging.write(f.firefoxResponseWriter, resp);
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
