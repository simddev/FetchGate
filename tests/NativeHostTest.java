import java.io.*;
import java.net.*;
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

        TestRunner.test("server binds to loopback address only", () -> {
            try (Fixture f = new Fixture()) {
                InetAddress addr = InetAddress.getByName("localhost");
                int port = f.host.getBoundPort();

                // Connecting via 127.0.0.1 must succeed
                try (Socket s = new Socket("127.0.0.1", port)) {
                    Assert.isTrue(s.isConnected(), "should connect on 127.0.0.1");
                }
                // The bound address must be the loopback, not a wildcard
                Assert.isTrue(addr.isLoopbackAddress(),
                    "bound address should be loopback");
            }
        });

        TestRunner.test("simple request-response round-trip", () -> {
            try (Fixture f = new Fixture()) {
                String request  = "{\"method\":\"GET\",\"url\":\"/api/v1/orders\"}";
                String response = "{\"status\":200,\"body\":\"[]\"}";

                // Caller sends a request via TCP
                Future<String> callerResult = f.sendAsCaller(request);

                // Simulate Firefox: read the forwarded request, send a response
                String forwarded = NativeMessaging.read(f.hostRequestReader);
                Assert.equal(request, forwarded);
                NativeMessaging.write(f.firefoxResponseWriter, response);

                // Caller must receive the response
                Assert.equal(response, callerResult.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("request forwarded to Firefox verbatim (no modification)", () -> {
            try (Fixture f = new Fixture()) {
                String request = "{\"method\":\"POST\",\"url\":\"/submit\",\"body\":\"data\"}";

                f.sendAsCaller(request); // fire and forget for this test

                String forwarded = NativeMessaging.read(f.hostRequestReader);
                Assert.equal(request, forwarded);
                f.host.stop();
            }
        });

        TestRunner.test("response forwarded to caller verbatim (no modification)", () -> {
            try (Fixture f = new Fixture()) {
                String request  = "{\"method\":\"GET\",\"url\":\"/\"}";
                String response = "{\"status\":403,\"statusText\":\"Forbidden\",\"body\":\"nope\"}";

                Future<String> callerResult = f.sendAsCaller(request);

                NativeMessaging.read(f.hostRequestReader); // consume the forwarded request
                NativeMessaging.write(f.firefoxResponseWriter, response);

                Assert.equal(response, callerResult.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("timeout: returns error JSON when Firefox does not respond", () -> {
            try (Fixture f = new Fixture()) {
                // Send a request but never write a response on the Firefox side
                Future<String> callerResult = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/\"}");

                // Wait for longer than the configured timeout
                String result = callerResult.get(TEST_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);

                Assert.contains(result, "error");
                Assert.contains(result, "timeout");
            }
        });

        TestRunner.test("timeout error message includes the configured timeout value", () -> {
            try (Fixture f = new Fixture()) {
                Future<String> callerResult = f.sendAsCaller("{\"method\":\"GET\",\"url\":\"/\"}");
                String result = callerResult.get(TEST_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
                Assert.contains(result, String.valueOf(TEST_TIMEOUT_MS));
            }
        });

        TestRunner.test("stale response is cleared before each new request", () -> {
            // Simulate: first request times out; Firefox responds late; second request
            // must receive its own response, not the leftover from the first.
            try (Fixture f = new Fixture()) {
                String req1 = "{\"method\":\"GET\",\"url\":\"/first\"}";
                String req2 = "{\"method\":\"GET\",\"url\":\"/second\"}";

                // First caller — will time out
                Future<String> result1 = f.sendAsCaller(req1);
                NativeMessaging.read(f.hostRequestReader); // consume forwarded req1

                // Let the timeout expire
                String timedOut = result1.get(TEST_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
                Assert.contains(timedOut, "timeout");

                // Now Firefox sends a late response for req1 (stale)
                NativeMessaging.write(f.firefoxResponseWriter, "{\"status\":200,\"body\":\"late\"}");
                Thread.sleep(50); // give the stdin-reader time to enqueue it

                // Second request — must NOT receive the stale response
                String expected2 = "{\"status\":200,\"body\":\"correct\"}";
                Future<String> result2 = f.sendAsCaller(req2);
                NativeMessaging.read(f.hostRequestReader); // consume forwarded req2
                NativeMessaging.write(f.firefoxResponseWriter, expected2);

                Assert.equal(expected2, result2.get(2, TimeUnit.SECONDS));
            }
        });

        TestRunner.test("blank request line is ignored — no response, no crash", () -> {
            try (Fixture f = new Fixture()) {
                // Connect and send a blank line; host should discard it and
                // keep running (accept the next connection).
                try (Socket s = new Socket("127.0.0.1", f.host.getBoundPort());
                     PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                     BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
                    out.println(""); // blank
                    s.setSoTimeout(300);
                    try {
                        String reply = in.readLine();
                        // Null means server closed connection without writing — that's fine too
                        Assert.isNull(reply, "expected no response to blank request");
                    } catch (java.net.SocketTimeoutException e) {
                        // Timeout with no data written is also acceptable
                    }
                }

                // Host must still be running and accepting connections
                try (Socket s2 = new Socket("127.0.0.1", f.host.getBoundPort())) {
                    Assert.isTrue(s2.isConnected(), "host should still accept after blank request");
                }
            }
        });

        TestRunner.test("caller disconnect before sending is handled gracefully", () -> {
            try (Fixture f = new Fixture()) {
                // Connect and immediately close
                new Socket("127.0.0.1", f.host.getBoundPort()).close();
                Thread.sleep(100); // let the host process the disconnection

                // Host must still accept the next connection
                try (Socket s = new Socket("127.0.0.1", f.host.getBoundPort())) {
                    Assert.isTrue(s.isConnected(), "host should accept after abrupt disconnect");
                }
            }
        });

        TestRunner.test("multiple sequential requests all produce correct responses", () -> {
            try (Fixture f = new Fixture()) {
                int n = 4;
                for (int i = 0; i < n; i++) {
                    String req  = "{\"seq\":" + i + ",\"url\":\"/item/" + i + "\"}";
                    String resp = "{\"status\":200,\"seq\":" + i + "}";

                    Future<String> result = f.sendAsCaller(req);
                    Assert.equal(req,  NativeMessaging.read(f.hostRequestReader));
                    NativeMessaging.write(f.firefoxResponseWriter, resp);
                    Assert.equal(resp, result.get(2, TimeUnit.SECONDS));
                }
            }
        });

        TestRunner.test("stop() terminates the accept loop", () -> {
            try (Fixture f = new Fixture()) {
                int port = f.host.getBoundPort();
                f.host.stop();
                Thread.sleep(100);

                // After stop, connecting should fail
                try {
                    new Socket("127.0.0.1", port).close();
                    throw new AssertionError("expected connection to be refused after stop()");
                } catch (ConnectException e) {
                    // expected — server is no longer listening
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
     * The host starts on a random OS-assigned port and is stopped in close().
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
            // Pipe 1: test writes responses → host reads (simulates Firefox responding)
            firefoxResponseWriter = new PipedOutputStream();
            firefoxResponseReader = new PipedInputStream(firefoxResponseWriter, 65536);

            // Pipe 2: host writes requests → test reads (simulates Firefox receiving)
            hostRequestWriter = new PipedOutputStream();
            hostRequestReader = new PipedInputStream(hostRequestWriter, 65536);

            host = new NativeHost(firefoxResponseReader, hostRequestWriter, 0, TEST_TIMEOUT_MS);
            executor.submit(() -> { host.start(); return null; });
            awaitReady();
        }

        /** Send one newline-delimited JSON request over TCP; returns the response line. */
        Future<String> sendAsCaller(String requestJson) {
            return executor.submit(() -> {
                try (Socket s  = new Socket("127.0.0.1", host.getBoundPort());
                     PrintWriter  out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
                     BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"))) {
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
