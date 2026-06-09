# Architecture

## Project Overview

FetchGate is a Firefox/LibreWolf WebExtension that bridges an external process to a live, logged-in browser tab. Two modes: execute authenticated `fetch()` calls (fetch mode) or run arbitrary JavaScript (JS mode) inside the tab, inheriting its full session state. Target platform: GNU/Linux only.

Use case: extracting your own data from websites that actively prevent it.

Three native host implementations are provided  -  Java, Python TCP, and Python embedded. All work with the same, unchanged extension.

## Architecture

### Java host

```
External Caller (any language)
    ↓ newline-delimited JSON over TCP localhost:9919
Java Native Host  (src/)
    ↓ browser.runtime.connectNative()  -  Firefox Native Messaging (stdin/stdout)
Background Script (background.js)
    ↓ browser.tabs.sendMessage()
Content Script (content_script.js)
     -  executes fetch() or arbitrary JS in tab context, inherits session state
    ↑ returns result to background → native host → caller
```

The Java host is a persistent TCP server on `localhost:9919`. Firefox launches it on the first arm. It stays running until Firefox or the Java process exits. Callers connect over TCP; one connection can send multiple requests.

Can also be run via Docker (no JDK required)  -  see `Dockerfile` and INSTALL.md.

### Python host (embedded)

```
Your Python Script  (host_py/)
     -  the script IS the native host; Firefox launches it directly
    ↓ Firefox Native Messaging (stdin/stdout)
Background Script (background.js)
    ↓ browser.tabs.sendMessage()
Content Script (content_script.js)
     -  executes fetch() or arbitrary JS in tab context
    ↑ returns result to background → script
```

Firefox launches the Python script when the tab is armed. The script calls `fg.fetch()` as many times as it needs (fetch mode or JS mode) and exits. No TCP port. The script IS the native host.

### Python TCP host

```
External Caller (any language)
    ↓ newline-delimited JSON over TCP  localhost:9919
fetchgate_tcp_host.py  (host_py/)
     -  drop-in replacement for the Java host; Firefox launches it directly
    ↓ Firefox Native Messaging (stdin/stdout)
Background Script (background.js)
    ↓ browser.tabs.sendMessage()
Content Script (content_script.js)
     -  executes fetch() or arbitrary JS in tab context
    ↑ returns result to background → native host → caller
```

Same TCP interface as the Java host  -  any caller that works with the Java host works unchanged. Firefox launches `fetchgate_tcp_host.py` as the NM host; it binds `localhost:9919` and proxies between TCP clients and the browser tab. Use this when Java is not available.

## IPC

Firefox Native Messaging  -  4-byte little-endian length header + UTF-8 JSON payload over stdin/stdout. Firefox enforces a hard 1 MB per-message cap.

**Request envelope** (host → extension):
```json
{"__fg_id": 1, "req": "{\"method\":\"GET\",\"url\":\"/\"}"}
```
`__fg_id` is a per-request correlation ID. `req` is the caller's JSON serialised as a string so `background.js` can delegate structural validation to `JSON.parse()`. `background.js` echoes `__fg_id` in every reply so the host can match responses and discard stale ones. Both modes use the same envelope.

**Design constraint**: Keep the extension code minimal and dumb. Do not put validation or complex logic in JavaScript. Offload all of that to the native host.

## Message Format

### Fetch mode
Request (caller → extension via native host):
```json
{ "method": "GET", "url": "/api/v2/user/profile", "headers": {"Accept": "application/json"} }
```

Response:
```json
{ "status": 200, "statusText": "OK", "headers": {"content-type": "application/json"}, "body": "..." }
```

### JS mode
Request:
```json
{ "js": "const r = await fetch('/api/orders'); return r.json();" }
```

Response:
```json
{ "result": "..." }
```

Strings pass through as-is; all other values are JSON.stringify'd. Errors in both modes are returned as `{ "error": "..." }`.

## Extension UI

The toolbar popup and keyboard shortcut system have several non-obvious implementation details worth understanding.

### Tab detection in the popup

Opening the browser action popup shifts Firefox's "current window" context  -  any `browser.tabs.query({ active: true, currentWindow: true })` call made from popup.js returns empty because the popup itself is now the focused window and it has no tabs. The fix is a single-source tracker in background.js:

```js
let lastActiveTabId = null;
browser.tabs.onActivated.addListener(({ tabId }) => { lastActiveTabId = tabId; });
```

The popup reads this via `bg.getActiveTab()`, which looks up the tab by the stored ID. A fallback scans all normal windows for a focused one  -  covering the edge case where `lastActiveTabId` is stale.

### Accessing background state from the popup

`browser.runtime.getBackgroundPage()` returns the background page's `window` object. Variables declared with `let` or `const` in background.js are **not** properties of `window`  -  they are module-level bindings invisible to the popup. Only `function` declarations (which are hoisted onto `window`) and `var` variables are accessible via `bg.functionName`.

This is why `getState()` and `getActiveTab()` in background.js are declared as `function`, not as arrow functions assigned to `const`. The popup reads armed state as `bg.getState()`  -  one call returns a plain object snapshot rather than exposing mutable variables directly.

### Live polling

The popup renders its state immediately on open, then calls `setInterval(renderStatus, 750)`. This makes it reflect arm/disarm triggered by keyboard shortcut while the popup is open  -  without any message-passing infrastructure. Button handlers are attached via `actionBtn.onclick = handler` (assignment, not `addEventListener`), so each 750 ms poll replaces the previous handler instead of stacking duplicates.

### Shortcut verification state machine

`browser.commands.update()` silently accepts some shortcuts that Firefox or the desktop environment ultimately blocks  -  the API call resolves successfully, but the key is never delivered to the extension. After setting a shortcut, `startShortcutVerification()` enters a 10-second verification window:

- The next `commands.onCommand` fire within 10 s → shortcut confirmed.
- No fire within 10 s → warning notification telling the user to try a different key.

This catches the gap between "API accepted it" and "the key actually works."

### Navigation race condition in `onUpdated`

When the armed tab navigates, `tabs.onUpdated` fires `executeScript` to re-inject the content script. The `.catch()` handler calls `disarm(tabId)` if injection fails (e.g., navigated to a privileged page). Without a guard, a stale `.catch()` callback from a previous navigation could fire after the user had already armed a different tab  -  wiping `armedTabId` for the new tab. The fix is a guard in both `.then()` and `.catch()`:

```js
.then(() => { if (tabId !== armedTabId) return; /* re-apply badge */ })
.catch(() => { if (tabId === armedTabId) disarm(tabId, '…'); })
```

## Components / Deliverables

- `extension/manifest.json`  -  WebExtension manifest (permissions: `nativeMessaging`, `notifications`, `storage`, `activeTab`, `tabs`, `<all_urls>`)
- `extension/background.js`  -  manages "armed tab" state, Native Messaging connection, routes messages to content script
- `extension/content_script.js`  -  executes `fetch()` or arbitrary JS in tab context, returns result
- `extension/popup.html`  -  toolbar popup markup
- `extension/popup.js`  -  popup logic: state display, button actions, inline shortcut recorder
- `src/`  -  Java native host: TCP server on `localhost:9919`, NM framing, request lifecycle
- `Dockerfile`  -  multi-stage build for Java host; no JDK needed on the host machine
- `host_py/fetchgate.py`  -  Python NM client library; import and call `FetchGate().fetch()`
- `host_py/example.py`  -  template Python script to copy and customise
- `host_py/fetchgate_tcp_host.py`  -  Python TCP host; drop-in replacement for the Java host
- `fetchgate.json`  -  NM manifest template for Java host
- `fetchgate_py.json`  -  NM manifest template for embedded Python host
- `fetchgate_tcp_py.json`  -  NM manifest template for Python TCP host
- All three NM manifests install to `~/.mozilla/native-messaging-hosts/fetchgate.json` (mutually exclusive)

## Key Behaviours

- User arms a tab via the toolbar popup (**Arm this tab** button) or keyboard shortcut (default **Ctrl+Shift+F**, configurable)  -  badge shows ON (green) or ERR (red)
- Desktop notifications fire on arm, disarm, and native host disconnect
- Only one tab can be armed at a time; arming a second tab disarms the first
- Request timeout: 30 seconds (Java host only; Python host has no timeout)
- A tab being "armed" is the single source of truth held by the background script
- Full serialized reply capped at 1 MB (UTF-8 bytes)  -  measured as JSON.stringify of the entire reply object

## Build & Run

```bash
# Java: compile directly
javac -d out src/*.java
# Java: run via Docker (no JDK required)
docker build -t fetchgate .

# Java: run tests (64 tests, no external dependencies)
javac -d out src/*.java tests/*.java
java  -cp out TestRunner

# Python: run tests (no external dependencies)
python3 host_py/test_fetchgate.py          # 31 tests  -  NM library
python3 host_py/test_fetchgate_tcp_host.py # 16 tests  -  Python TCP host
```

`out/` is gitignored. Python 3.6+, JDK 21+.
