# FetchGate

A Firefox/LibreWolf WebExtension that lets an external process execute
authenticated `fetch()` calls through a live browser tab.

## What it does

When you are logged into a website, the browser holds session state (cookies,
tokens, TLS client certificates) that is not directly accessible to an external
program. FetchGate bridges that gap: a Java or Python program sends a request
spec to a local TCP port, the extension executes the corresponding `fetch()`
inside the active tab, and returns the full HTTP response — status, headers,
and body — to the caller.

The fetch runs in the tab's JavaScript context, so it inherits everything the
browser already has for that site: cookies, session tokens, CORS policy. No
credentials need to be extracted or replayed.

**Target use case:** extracting your own data from websites that have accounts
but no API, or whose API access is blocked to third-party HTTP clients.

**Platform:** GNU/Linux only.

## Architecture

```
External Caller (Java / Python)
    │
    │  newline-delimited JSON over TCP (localhost:9919)
    │
    ▼
Native Host  (Java)
    │
    │  Firefox Native Messaging — 4-byte LE length-prefixed JSON over stdin/stdout
    │
    ▼
Background Script  (background.js)
    │
    │  browser.tabs.sendMessage()
    │
    ▼
Content Script  (content_script.js)
    — runs fetch() inside the tab, inherits session state
    — returns response to background → native host → caller
```

**IPC in detail:**

- *Caller ↔ Native Host* — plain TCP on `localhost:9919`. One JSON object per
  line (newline-delimited). Persistent connections are supported; the host
  closes only on timeout or error.

- *Native Host ↔ Extension* — [Firefox Native Messaging][nm]: each message is
  a 4-byte little-endian unsigned integer (payload length) followed by a UTF-8
  JSON payload. Firefox launches the native host process on demand when the
  extension calls `browser.runtime.connectNative('fetchgate')`.

- *Background ↔ Content Script* — `browser.tabs.sendMessage()` / `sendResponse`
  within the browser process.

**Design constraint:** the extension code is intentionally minimal and dumb.
Semantic validation (allowed methods, URL policy, etc.) belongs in the Java host
or the caller. Syntax validation is delegated to JavaScript's native JSON parser
via the envelope mechanism described below.

**Request envelope:** every request forwarded to Firefox is wrapped in a
controlled envelope:
```json
{"__fg_id": 1, "req": "{\"method\":\"GET\",\"url\":\"/\"}"}
```
`__fg_id` is a monotonically increasing integer used for reply tracking.
`req` is the caller's JSON encoded as a string — `background.js` calls
`JSON.parse(msg.req)` to validate and extract it. This means JavaScript's
native JSON parser validates the request structure, and `__fg_id` appears
only in the outer envelope, eliminating false-positive response matching.
`background.js` echoes `__fg_id` in the response. The host matches responses
by ID and discards any stale replies left over from a previous timed-out
request, eliminating the reply-misdelivery race.

## Message format

**Request** (caller → host → extension):

```json
{ "method": "GET", "url": "/api/v2/user/profile", "headers": {"Accept": "application/json"} }
```

| Field         | Required | Description                                                        |
|---------------|----------|--------------------------------------------------------------------|
| `url`         | yes      | Absolute URL or path relative to the current tab origin            |
| `method`      | no       | HTTP method; defaults to `GET`                                     |
| `headers`     | no       | Object of additional request headers                               |
| `body`        | no       | Request body (for POST/PUT)                                        |
| `credentials` | no       | Fetch credentials mode: `"same-origin"` (default), `"include"`, or `"omit"` |

`__fg_id` is used internally for reply tracking and must not be sent by callers; it is stripped from the response before it reaches the caller.

**Response** (extension → host → caller):

```json
{ "status": 200, "statusText": "OK", "headers": {"content-type": "application/json"}, "body": "..." }
```

The `body` field is always a string. Parse it based on `content-type`.
On error, the response is `{ "error": "..." }`.

## Requirements

- GNU/Linux
- Firefox or LibreWolf
- JDK 21+

## Installation

See **[INSTALL.md](INSTALL.md)** for the full step-by-step setup.

In short:

1. Compile the Java source: `javac -d out src/*.java`
2. Create a `fetchgate.sh` launcher script pointing at the compiled classes
3. Copy `fetchgate.json` to `~/.mozilla/native-messaging-hosts/` and set the
   `path` field to your launcher script
4. Load the extension via `about:debugging → Load Temporary Add-on → extension/manifest.json`

## Usage

1. Navigate to a site you are logged into
2. Click the **FetchGate** toolbar button — the badge turns green **ON**
3. Connect a caller to `localhost:9919` and send a JSON request line

Quick test with netcat:

```bash
echo '{"method":"GET","url":"/"}' | nc localhost 9919
```

You should receive a single JSON line with `status`, `headers`, and `body`.

## Building and testing

```bash
# Compile
javac -d out src/*.java

# Run the test suite (63 tests, no external dependencies)
javac -d out src/*.java tests/*.java
java  -cp out TestRunner
```

## Known limitations

- **Single-line JSON only.** The caller-side TCP protocol is newline-delimited.
  Each line is validated independently; a line that does not form a complete
  JSON object (starts with `{`, ends with `}`) is rejected with an error.
  Always send compact, single-line JSON.

- **One armed tab at a time.** The background script tracks a single armed tab.
  Arming a second tab automatically disarms the first. If the native host
  disconnects (ERR badge), clicking the toolbar button once re-arms and
  reconnects; a second click is not required.

- **Extension reloads on browser restart.** Temporary add-ons (loaded via
  `about:debugging`) are not persisted across Firefox restarts. See
  `INSTALL.md` for the persistent `.xpi` install option.

- **30-second request timeout.** If the extension does not respond within 30 s
  the host returns `{"error":"timeout: ..."}` to the caller. The in-tab
  `fetch()` is not cancelled on timeout; it continues running in the browser
  until the network completes or fails. The stale reply is discarded by ID
  matching but the request still consumes a browser connection slot.

- **Cross-origin requests do not send credentials by default.** The default
  `fetch()` credentials mode is `same-origin`, so cookies and auth headers are
  only forwarded automatically for URLs on the same origin as the armed tab.
  For cross-origin absolute URLs, add `"credentials":"include"` to the request
  spec — but this only works if the target server responds with
  `Access-Control-Allow-Credentials: true` and a non-wildcard origin; otherwise
  the browser blocks the response.

- **Response body is always UTF-8 text.** `content_script.js` reads the
  response body with `response.text()`, which the Fetch specification always
  decodes as UTF-8. Binary payloads (images, PDFs, ZIPs, protobuf) and
  responses in non-UTF-8 encodings (e.g. legacy Windows-1252 or Shift-JIS
  pages) will be corrupted in transit. HTML and JSON responses in UTF-8 are
  unaffected.

- **Logs may contain sensitive data.** Requests and responses are logged to
  stderr (truncated at 120 characters). On a multi-user system these may
  appear in terminal history or journal logs. Run the host in a dedicated
  terminal and avoid piping stderr to persistent storage.

## Project structure

```
src/                    Java native host
  Main.java             Entry point; stdout redirect to protect the NM channel
  NativeMessaging.java  Firefox Native Messaging framing (length-prefixed JSON)
  NativeHost.java       TCP server + stdin-reader thread + request lifecycle

extension/              WebExtension
  manifest.json         MV2 manifest; extension ID: fetchgate@localhost
  background.js         Armed-tab state, connectNative(), message routing
  content_script.js     Executes fetch() in tab context, returns response

tests/                  Test suite (plain Java, no framework)
  TestRunner.java       Test runner and harness
  Assert.java           Assertion helpers
  NativeMessagingTest.java
  NativeHostTest.java

fetchgate.json          Native messaging manifest template
INSTALL.md              Step-by-step installation instructions
```

## License

GNU General Public License v3 — see [LICENSE](LICENSE).

[nm]: https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Native_messaging
