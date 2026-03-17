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

Two native host implementations are provided. Both speak the same protocol to
the extension; the extension code is identical in both cases.

### Java host (TCP bridge)

```
External Caller (any language)
    │
    │  newline-delimited JSON over TCP (localhost:9919)
    │
    ▼
Native Host  (Java — src/)
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

The Java host runs as a persistent TCP server. Any language can reach it via
`localhost:9919` without knowing anything about the Native Messaging protocol.

### Python host (direct)

```
Your Python script  (host_py/)
    │
    │  Firefox Native Messaging — stdin/stdout  (your script IS the native host)
    │
    ▼
Background Script  (background.js)
    │  browser.tabs.sendMessage()
    ▼
Content Script  (content_script.js)
```

Firefox launches your Python script directly when you arm a tab. The script
imports `fetchgate.py`, calls `fg.fetch()`, and exits when done. No TCP server,
no Java. Arming the tab IS the trigger that starts the script.

**IPC in detail (Java host):**

- *Caller ↔ Native Host* — plain TCP on `localhost:9919`. One JSON object per
  line (newline-delimited). Persistent connections are supported (multiple
  sequential requests on one socket); the host closes only on timeout or error.
  The host is **single-caller**: it handles one TCP connection at a time and
  does not accept the next connection until the current one closes. See
  Known Limitations.

- *Native Host ↔ Extension* — [Firefox Native Messaging][nm]: each message is
  a 4-byte little-endian unsigned integer (payload length) followed by a UTF-8
  JSON payload. Firefox launches the native host process on demand when the
  extension calls `browser.runtime.connectNative('fetchgate')`.

- *Background ↔ Content Script* — `browser.tabs.sendMessage()` / `sendResponse`
  within the browser process.

**Extension permissions:**
- `nativeMessaging` — required to call `browser.runtime.connectNative()`.
- `activeTab` — grants script-injection access to the tab the user just clicked.
  This is sufficient for the initial arm, but it expires after the click event.
- `<all_urls>` — required for re-injection after the armed tab navigates to a
  new page. The `tabs.onUpdated` listener calls `browser.tabs.executeScript`
  outside of a user-gesture context, so `activeTab` does not apply there; a
  host permission is needed instead. Without it, navigation would silently
  leave the content script uninstalled while the badge still shows ON (or
  disarm the tab, depending on the error path).
- `tabs` — grants access to tab metadata needed by `browser.tabs.sendMessage`
  and `browser.tabs.executeScript`.

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
- **Java host:** JDK 21+
- **Python host:** Python 3.6+

## Installation

See **[INSTALL.md](INSTALL.md)** for the full step-by-step setup.

See **[INSTALL.md](INSTALL.md)** for full setup instructions for both hosts.

**Java host (short version):**

1. Compile: `javac -d out src/*.java`
2. Create `fetchgate.sh` launcher; copy `fetchgate.json` to `~/.mozilla/native-messaging-hosts/` with the correct path
3. Load the extension via `about:debugging`

**Python host (short version):**

1. Write your script importing `host_py/fetchgate.py`; make it executable
2. Copy `fetchgate_py.json` to `~/.mozilla/native-messaging-hosts/fetchgate.json` with the correct path
3. Load the extension via `about:debugging`

## Usage

### Java host

1. Start the Java host (it will be launched by Firefox automatically on arm)
2. Navigate to a site you are logged into
3. Click the **FetchGate** toolbar button — badge turns green **ON**
4. Connect a caller to `localhost:9919` and send a JSON request line

Quick test with netcat:

```bash
echo '{"method":"GET","url":"/"}' | timeout 3 nc localhost 9919
```

`timeout 3` is needed because the host keeps the connection open for persistent
callers — without it `nc` hangs waiting for more data after the response.

### Python host

1. Write your Python script (see `host_py/example.py`)
2. Navigate to the target site
3. Click the **FetchGate** toolbar button — Firefox launches your script immediately
4. The script runs to completion; the badge shows ERR when it exits (normal)

```python
from fetchgate import FetchGate

fg = FetchGate()
resp = fg.fetch({"method": "GET", "url": "/api/data"})
print(resp["body"])
```

## Building and testing

```bash
# Compile
javac -d out src/*.java

# Run the test suite (64 tests, no external dependencies)
javac -d out src/*.java tests/*.java
java  -cp out TestRunner
```

## Security model

The host binds to `localhost` only, so it is not reachable from other machines.
However, **any process on the local machine that can reach `localhost:9919`**
can send requests and have them executed in the currently armed browser tab —
including other applications, scripts, and (on a multi-user system) other
users. There is no authentication.

This is an accepted trade-off for a personal single-user tool on a
single-user machine. Do not run the native host on shared or multi-user
infrastructure.

## Known limitations

- **Single caller at a time.** The host handles one TCP connection at a time.
  While waiting for Firefox to respond to a request — or simply waiting for the
  current caller to send its next line — no other caller can connect or be
  served. An idle open socket monopolises the service. For single-user
  personal-tool use this is fine; use short-lived connections (connect, send
  one request, read the response, disconnect) if multiple callers need to
  interleave.

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

- **Multiple `Set-Cookie` response headers are deduplicated.** `content_script.js`
  stores response headers in a plain JavaScript object. The Fetch API combines
  most duplicate header values with `, ` before they reach `forEach`, but
  `Set-Cookie` is an exception: each cookie arrives as a separate call. Because
  the object assignment `headers[name] = value` overwrites on each call, only
  the last cookie value is kept. For typical data-extraction requests this is
  irrelevant; for responses that set multiple cookies in one reply, all but the
  last are silently dropped.

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
src/                    Java native host (TCP bridge)
  Main.java             Entry point; stdout redirect to protect the NM channel
  NativeMessaging.java  Firefox Native Messaging framing (length-prefixed JSON)
  NativeHost.java       TCP server + stdin-reader thread + request lifecycle

host_py/                Python native host (direct, no TCP)
  fetchgate.py          NM client library — import this in your script
  example.py            Ready-to-run example script

extension/              WebExtension (shared by both hosts)
  manifest.json         MV2 manifest; extension ID: fetchgate@localhost
  background.js         Armed-tab state, connectNative(), message routing
  content_script.js     Executes fetch() in tab context, returns response

tests/                  Test suite (plain Java, no framework)
  TestRunner.java       Test runner and harness
  Assert.java           Assertion helpers
  NativeMessagingTest.java
  NativeHostTest.java

fetchgate.json          NM manifest template — Java host
fetchgate_py.json       NM manifest template — Python host
INSTALL.md              Step-by-step installation instructions
```

## License

GNU General Public License v3 — see [LICENSE](LICENSE).

[nm]: https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Native_messaging
