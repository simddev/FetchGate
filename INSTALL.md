# FetchGate — Installation

Two native host implementations are available. Pick one — the extension is
identical in both cases.

| | Java host | Python host |
|---|---|---|
| Requires | JDK 21+ | Python 3.6+ |
| Caller model | Any language connects to `localhost:9919` via TCP | Your Python script IS the host; Firefox launches it |
| Persistent server | Yes — runs until you stop it | No — script runs once and exits |
| Good for | Multiple scripts, interactive use, non-Python callers | Single Python script that does a specific job |

---

## Java host

### 1. Compile

From the project root:

```bash
javac -d out src/*.java
```

Class files land in `out/`. Requires JDK 21+.

### 2. Create the launcher script

Firefox cannot invoke a `.class` file directly — the native host `path` in the
native messaging manifest must point to an executable. Create a small shell
script (anywhere permanent, e.g. `~/bin/fetchgate.sh`):

```bash
#!/bin/bash
exec java -cp /absolute/path/to/FetchGate/out Main
```

Replace `/absolute/path/to/FetchGate` with the real path. Make it executable:

```bash
chmod +x ~/bin/fetchgate.sh
```

`exec` replaces the shell with the Java process so no wrapper process lingers.

### 3. Register the native host with Firefox/LibreWolf

```bash
mkdir -p ~/.mozilla/native-messaging-hosts
cp fetchgate.json ~/.mozilla/native-messaging-hosts/fetchgate.json
```

Edit `~/.mozilla/native-messaging-hosts/fetchgate.json` and replace the
placeholder `path` value with the absolute path to your launcher script:

```json
"path": "/home/you/bin/fetchgate.sh"
```

The `name` field (`"fetchgate"`) must match the string passed to
`connectNative()` in `background.js` exactly. Do not change it.

### 4. Smoke test

1. Load the extension (see [Load the extension](#load-the-extension) below)
2. Open any website you are logged into
3. Click the FetchGate toolbar button — the badge turns green **ON**
4. From a terminal:

```bash
echo '{"method":"GET","url":"/"}' | timeout 3 nc localhost 9919
```

You should receive a single line of JSON back containing `status`, `headers`,
and `body`. `timeout 3` terminates `nc` after the response arrives — the host
keeps the connection open for persistent callers, so plain `nc` would hang.

---

## Python host

### 1. Write your script

Copy `host_py/example.py` to a permanent location and edit it with your fetch
calls. The script must begin with a shebang and be executable:

```bash
cp host_py/example.py ~/bin/my_scraper.py
chmod +x ~/bin/my_scraper.py
```

The script imports `fetchgate.py` from the same directory. If you move it
elsewhere, either copy `fetchgate.py` alongside it or add the `host_py/`
directory to `sys.path` in your script:

```python
sys.path.insert(0, "/absolute/path/to/FetchGate/host_py")
from fetchgate import FetchGate
```

### 2. Register the native host with Firefox/LibreWolf

```bash
mkdir -p ~/.mozilla/native-messaging-hosts
cp fetchgate_py.json ~/.mozilla/native-messaging-hosts/fetchgate.json
```

Edit `~/.mozilla/native-messaging-hosts/fetchgate.json` and replace the
placeholder `path` value with the absolute path to your script:

```json
"path": "/home/you/bin/my_scraper.py"
```

### 3. Usage

1. Navigate to the target website in Firefox/LibreWolf
2. Click the FetchGate toolbar button

Firefox launches your script immediately when the tab is armed. The script
runs to completion, then exits. The badge shows ERR when it exits — this is
normal. Click the toolbar button again to re-run the script.

**Important:** Firefox launches the script, not you. Start by arming the tab;
there is no separate server to start first.

---

## Load the extension

The extension setup is the same regardless of which host you use.

### Option A — Temporary (for development)

1. Open Firefox or LibreWolf and navigate to `about:debugging`
2. Click **This Firefox**
3. Click **Load Temporary Add-on...**
4. Select `extension/manifest.json`

The FetchGate button appears in the toolbar. It stays loaded until the browser
is restarted. Repeat this step after each restart.

### Option B — Persistent (survives restarts)

Firefox normally requires extensions to be signed by Mozilla, but you can
bypass this for personal use:

1. Navigate to `about:config` and set:
   ```
   xpinstall.signatures.required = false
   ```
2. Package the extension as a `.xpi` file (a zip of the `extension/` directory):
   ```bash
   cd extension && zip -r ../fetchgate.xpi . && cd ..
   ```
3. Navigate to `about:addons`, click the gear icon, and choose
   **Install Add-on From File...**
4. Select `fetchgate.xpi`

The extension now persists across restarts without `about:debugging`.
Note: `xpinstall.signatures.required` is not available in the release builds of
Firefox — use Firefox Developer Edition, Firefox Nightly, or LibreWolf.

---

If nothing works, open the Firefox browser console
(`about:debugging` → Inspect on FetchGate) to see background script log output.
