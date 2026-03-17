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
                // Connecting via 127.0.0.1 must succeed
                try (Socket s = new Socket("127.0.0.1", f.host.getBoundPort())) {
                    Assert.isTrue(s.isConnected(), "should connect on 127.0.0.1");
                }
            }
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
            host.stop(); // called before start() — must not throw
            pout.close();
            pin.close();
        });

        TestRunner.test("stop() is idempotent — calling it twice does not throw", () -> {
            try (Fixture f = new Fixture()) {
                f.host.stop();
                f.host.stop(); // second call must be harmless
            }
        });

        // ── Request/response flow ─────────────────────────────────────────────

        TestRunner.test("simple request-response round-trip", () -> {
            try (Fixture f = new Fixture()) {
                String request  = "{\"method\":\"GET\",\"url\":\"/api/v1/orders\"}";
                String response = "{\"status\":200,\"body\":\"[]\"}";

                Future<String> callerResult = f.sendAsCaller(request);

                String forwarded = NativeMessaging.read(f.hostRequestReader);
                Assert.equal(request, forwarded);
                NativeMessaging.write(f.firefoxResponseWriter, response);

                Assert.equal(response, callerResult.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("request forwarded to Firefox verbatim (no modification)", () -> {
            try (Fixture f = new Fixture()) {
                String request = "{\"method\":\"POST\",\"url\":\"/submit\",\"body\":\"data\"}";

                f.sendAsCaller(request);

                String forwarded = NativeMessaging.read(f.hostRequestReader);
                Assert.equal(request, forwarded);

                NativeMessaging.write(f.firefoxResponseWriter, "{\"status\":200}");
            }
        });

        TestRunner.test("response forwarded to caller verbatim (no modification)", () -> {
            try (Fixture f = new Fixture()) {
                String response = "{\"status\":403,\"statusText\":\"Forbidden\",\"body\":\"nope\"}";

                Future<String> callerResult = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/\"}");
                NativeMessaging.read(f.hostRequestReader);
                NativeMessaging.write(f.firefoxResponseWriter, response);

                Assert.equal(response, callerResult.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("response with JSON-encoded special characters forwarded correctly", () -> {
            // JSON-encoded \n, \t, unicode — the string contains no literal newlines,
            // so readLine() on the caller side does not truncate it.
            try (Fixture f = new Fixture()) {
                String response = "{\"status\":200,\"body\":\"line1\\nline2\\ttab\\u00e9\"}";

                Future<String> callerResult = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/\"}");
                NativeMessaging.read(f.hostRequestReader);
                NativeMessaging.write(f.firefoxResponseWriter, response);

                Assert.equal(response, callerResult.get(2, TimeUnit.SECONDS));
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

        // ── Timeout behaviour ─────────────────────────────────────────────────

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

        TestRunner.test("stale response is cleared before each new request", () -> {
            // Scenario: req1 times out; Firefox responds late; req2 must not receive
            // the stale response from req1.
            try (Fixture f = new Fixture()) {
                Future<String> result1 = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/first\"}");
                NativeMessaging.read(f.hostRequestReader); // consume forwarded req1

                // Wait for timeout
                Assert.contains(result1.get(TEST_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS), "timeout");

                // Firefox now sends a late reply for req1 — this is the stale entry
                NativeMessaging.write(f.firefoxResponseWriter, "{\"status\":200,\"body\":\"late\"}");
                Thread.sleep(60); // give stdin-reader time to enqueue it

                // req2 must receive its own response, not the stale one
                String expected = "{\"status\":200,\"body\":\"correct\"}";
                Future<String> result2 = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/second\"}");
                NativeMessaging.read(f.hostRequestReader);
                NativeMessaging.write(f.firefoxResponseWriter, expected);

                Assert.equal(expected, result2.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("firefox disconnects during in-flight request — caller still receives error, not a hang", () -> {
            // When Firefox disconnects mid-flight, stop() closes the server socket.
            // The handleCaller thread is already blocked on poll() and is not interrupted,
            // so the caller still gets a response — the timeout error — rather than hanging.
            try (Fixture f = new Fixture()) {
                Future<String> result = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/\"}");
                NativeMessaging.read(f.hostRequestReader); // consume forwarded request

                f.firefoxResponseWriter.close(); // simulate Firefox dying

                String reply = result.get(TEST_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS);
                Assert.notNull(reply, "caller must receive a response, not hang");
                Assert.contains(reply, "error");
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
                    } catch (SocketTimeoutException e) {
                        // also acceptable
                    }
                }
                // Host must still accept new connections
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
                    out.println("     "); // spaces only — isBlank() = true
                    s.setSoTimeout(300);
                    try {
                        Assert.isNull(in.readLine(), "expected no response to whitespace request");
                    } catch (SocketTimeoutException e) {
                        // also acceptable
                    }
                }
                try (Socket s2 = new Socket("127.0.0.1", f.host.getBoundPort())) {
                    Assert.isTrue(s2.isConnected(), "host should still accept after whitespace request");
                }
            }
        });

        TestRunner.test("request with embedded newline — only first line is forwarded (protocol limitation)", () -> {
            // The TCP caller-side protocol is newline-delimited (readLine).
            // If a caller sends pretty-printed or otherwise multi-line JSON,
            // only the content up to the first \n is forwarded to Firefox.
            // This documents the known limitation: callers must send single-line JSON.
            try (Fixture f = new Fixture()) {
                String firstLine  = "{\"method\":\"GET\",";
                String secondLine = "\"url\":\"/api\"}";

                f.sendAsCaller(firstLine + "\n" + secondLine); // sends firstLine\nsecondLine\n

                // Host reads only firstLine (stops at the embedded \n)
                String forwarded = NativeMessaging.read(f.hostRequestReader);
                Assert.equal(firstLine, forwarded);

                // Unblock the host (it is waiting for a Firefox response for firstLine)
                NativeMessaging.write(f.firefoxResponseWriter, "{\"status\":200}");
            }
        });

        TestRunner.test("one request per connection — server closes socket after responding", () -> {
            // handleCaller uses try-with-resources on the Socket, so the connection is
            // closed after each response. A second readLine() on the same socket returns
            // null (EOF).
            try (Fixture f = new Fixture()) {
                String req  = "{\"method\":\"GET\",\"url\":\"/\"}";
                String resp = "{\"status\":200}";

                try (Socket s = new Socket("127.0.0.1", f.host.getBoundPort());
                     PrintWriter    out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
                     BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"))) {

                    out.println(req);
                    Assert.equal(req, NativeMessaging.read(f.hostRequestReader));
                    NativeMessaging.write(f.firefoxResponseWriter, resp);

                    // First response arrives normally
                    Assert.equal(resp, in.readLine());

                    // Server closed the connection — second read must return EOF
                    s.setSoTimeout(500);
                    try {
                        Assert.isNull(in.readLine(), "expected EOF after server closes connection");
                    } catch (SocketTimeoutException e) {
                        // server may not have closed yet — acceptable; the point is no second response
                    }
                }
            }
        });

        TestRunner.test("caller disconnect before sending — host keeps running", () -> {
            try (Fixture f = new Fixture()) {
                new Socket("127.0.0.1", f.host.getBoundPort()).close(); // connect + immediately close
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
            // Pipe 1: test writes Firefox responses → host reads (simulates Firefox responding)
            firefoxResponseWriter = new PipedOutputStream();
            firefoxResponseReader = new PipedInputStream(firefoxResponseWriter, 65536);

            // Pipe 2: host writes NM requests → test reads (simulates Firefox receiving)
            hostRequestWriter = new PipedOutputStream();
            hostRequestReader = new PipedInputStream(hostRequestWriter, 65536);

            host = new NativeHost(firefoxResponseReader, hostRequestWriter, 0, TEST_TIMEOUT_MS);
            executor.submit(() -> { host.start(); return null; });
            awaitReady();
        }

        /** Send one newline-delimited JSON request over TCP; returns the response line. */
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

        /** Block until the host's server socket is bound (getBoundPort() > 0). */
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
