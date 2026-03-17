# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

FetchGate is a Firefox/LibreWolf WebExtension that allows an external Java process to execute `fetch()` calls through the browser's active, authenticated tab context. The extension acts as a transparent proxy: the caller sends a request spec, the extension runs it inside the tab (inheriting cookies, session state, CORS policy), and returns the result. Target platform: GNU/Linux only.

Use case: extracting your own data from websites that actively prevent it.

## Architecture

```
External Caller (Java / Python)
    ↓ stdin/stdout — length-prefixed JSON (Native Messaging protocol)
Native Host (Java)
    ↓ browser.runtime.connectNative()
Background Script (background.js)
    ↓ chrome.tabs.sendMessage()
Content Script (content_script.js)
    — executes fetch() in tab context, inherits session state
    ↑ returns response to background → native host → caller
```

**IPC**: Firefox Native Messaging — 4-byte little-endian length header + UTF-8 JSON payload over stdin/stdout. The Java native host reads/writes this format; the extension uses `browser.runtime.connectNative()` (connection-oriented, one pattern is sufficient).

**Design constraint**: Keep the extension code minimal and dumb. Do not put validation or complex logic in JavaScript. Offload all of that to the Java host process.

## Message Format

Request (caller → extension via native host):
```json
{ "method": "GET", "url": "/api/v2/user/profile", "headers": {"Accept": "application/json"} }
```

Response (extension → caller via native host):
```json
{ "status": 200, "statusText": "OK", "headers": {"content-type": "application/json"}, "body": "..." }
```

`url` may be absolute or relative to the current tab's origin. `body` supports text and JSON. Errors are returned as `{ "error": "..." }`.

## Components / Deliverables

- `extension/manifest.json` — WebExtension manifest (permissions: `nativeMessaging`, `activeTab`, `<all_urls>`)
- `extension/background.js` — manages "armed tab" state, Native Messaging connection, routes messages to content script
- `extension/content_script.js` — executes `fetch()` in tab context, returns response
- `src/` — Java native host (reads/writes Native Messaging protocol, bridges to caller)
- Native messaging manifest (JSON file placed at `~/.mozilla/native-messaging-hosts/`) — installation is manual, documented in instructions

## Key Behaviours

- User must explicitly activate the extension per tab (toolbar button click) — visual indicator shows when a tab is armed
- Request timeout: 30 seconds (hardcoded)
- A tab being "armed" is the single source of truth held by the background script

## Build & Run

No build automation configured yet. Compile Java with `javac`/`java` (JDK 21) or use IntelliJ IDEA.

```bash
javac -d out src/*.java
java  -cp out Main
```

## Tests

```bash
javac -d out src/*.java tests/*.java
java  -cp out TestRunner
```

60 tests across two suites: `NativeMessaging` (framing protocol) and `NativeHost` (TCP↔NM bridge). No external dependencies. `out/` is gitignored.
