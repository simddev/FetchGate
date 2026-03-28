# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

FetchGate is a Firefox/LibreWolf WebExtension that bridges an external process to a live, logged-in browser tab. Two modes: execute authenticated `fetch()` calls (fetch mode) or run arbitrary JavaScript (JS mode) inside the tab, inheriting its full session state. Target platform: GNU/Linux only.

Use case: extracting your own data from websites that actively prevent it.

Two native host implementations are provided — Java and Python. Both work with the same, unchanged extension.

## Architecture

### Java host

```
External Caller (any language)
    ↓ newline-delimited JSON over TCP localhost:9919
Java Native Host  (src/)
    ↓ browser.runtime.connectNative() — Firefox Native Messaging (stdin/stdout)
Background Script (background.js)
    ↓ browser.tabs.sendMessage()
Content Script (content_script.js)
    — executes fetch() or arbitrary JS in tab context, inherits session state
    ↑ returns result to background → native host → caller
```

The Java host is a persistent TCP server on `localhost:9919`. Firefox launches it on the first arm. It stays running until Firefox or the Java process exits. Callers connect over TCP; one connection can send multiple requests.

Can also be run via Docker (no JDK required) — see `Dockerfile` and INSTALL.md.

### Python host

```
Your Python Script  (host_py/)
    — the script IS the native host; Firefox launches it directly
    ↓ Firefox Native Messaging (stdin/stdout)
Background Script (background.js)
    ↓ browser.tabs.sendMessage()
Content Script (content_script.js)
    — executes fetch() or arbitrary JS in tab context
    ↑ returns result to background → script
```

Firefox launches the Python script when the tab is armed. The script calls `fg.fetch()` as many times as it needs (fetch mode or JS mode) and exits. No TCP port. The script IS the native host.

## IPC

Firefox Native Messaging — 4-byte little-endian length header + UTF-8 JSON payload over stdin/stdout. Firefox enforces a hard 1 MB per-message cap.

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

## Components / Deliverables

- `extension/manifest.json` — WebExtension manifest (permissions: `nativeMessaging`, `activeTab`, `tabs`, `<all_urls>`)
- `extension/background.js` — manages "armed tab" state, Native Messaging connection, routes messages to content script
- `extension/content_script.js` — executes `fetch()` or arbitrary JS in tab context, returns result
- `src/` — Java native host: TCP server on `localhost:9919`, NM framing, request lifecycle
- `Dockerfile` — multi-stage build for Java host; no JDK needed on the host machine
- `host_py/fetchgate.py` — Python NM client library; import and call `FetchGate().fetch()`
- `host_py/example.py` — template Python script to copy and customise
- `fetchgate.json` — NM manifest template for Java host
- `fetchgate_py.json` — NM manifest template for Python host
- Both NM manifests install to `~/.mozilla/native-messaging-hosts/fetchgate.json` (mutually exclusive)

## Key Behaviours

- User must explicitly activate the extension per tab (toolbar button click) — badge shows ON (green) or ERR (red)
- Only one tab can be armed at a time; arming a second tab disarms the first
- Request timeout: 30 seconds (Java host only; Python host has no timeout)
- A tab being "armed" is the single source of truth held by the background script
- Full serialized reply capped at 1 MB (UTF-8 bytes) — measured as JSON.stringify of the entire reply object

## Build & Run

```bash
# Java: compile directly
javac -d out src/*.java
# Java: run via Docker (no JDK required)
docker build -t fetchgate .

# Java: run tests (64 tests, no external dependencies)
javac -d out src/*.java tests/*.java
java  -cp out TestRunner

# Python: run tests (26 tests, no external dependencies)
python3 host_py/test_fetchgate.py
```

`out/` is gitignored. Python 3.6+, JDK 21+.
