# FetchGate — Installation

## Choose your host

FetchGate has two native host implementations. Both work with the same
Firefox extension; you pick one based on how you want to call it.

| | Java host | Python host |
|---|---|---|
| Requires | JDK 21+, **or Docker** | Python 3.6+ |
| How your code calls it | Connect to `localhost:9919` from any language | Your Python script IS the host; Firefox launches it |
| Persistent server | Yes — stays running until Firefox exits | No — script runs once and exits |
| Good for | Multi-script workflows, any language, interactive use | A single Python script with a specific job |

**Only one host can be active at a time.** Both install their configuration
to the same file: `~/.mozilla/native-messaging-hosts/fetchgate.json`. To
switch hosts, overwrite that file with the other template and reload the
extension.

---

## Java host

You can run the Java host either directly (requires JDK 21+) or via Docker
(no JDK needed).

---

### Option A — Run directly (JDK required)

#### 1. Compile

From the project root:

```bash
javac -d out src/*.java
```

Class files land in `out/`. Requires JDK 21+.

#### 2. Create the launcher script

Firefox cannot invoke `.class` files directly — the native messaging manifest
must point to an executable. Create a small shell wrapper (anywhere permanent,
e.g. `~/bin/fetchgate.sh`):

```bash
#!/bin/bash
exec java -cp "/absolute/path/to/FetchGate/out" Main
```

Replace the path with the real absolute path to your project directory. Then
make it executable:

```bash
chmod +x ~/bin/fetchgate.sh
```

The `exec` replaces the shell with the Java process so no lingering wrapper
process is left behind.

---

### Option B — Run via Docker (no JDK required)

Docker handles compilation and the Java runtime. The container shares the
host's network stack (`--network=host`) so the TCP server on `localhost:9919`
is reachable exactly like the direct installation. Linux only (matches this
project's target platform).

#### 1. Build the image

From the project root:

```bash
docker build -t fetchgate .
```

This compiles the Java source inside the build stage and produces a lean JRE
image. You only need to re-run this if the source files change.

#### 2. Create the launcher script

Create `~/bin/fetchgate.sh`:

```bash
#!/bin/bash
exec docker run --rm -i --network=host fetchgate
```

Then make it executable:

```bash
chmod +x ~/bin/fetchgate.sh
```

`--rm` removes the container automatically when Firefox closes the Native
Messaging connection. `-i` keeps stdin open for the NM framing. `--network=host`
makes `localhost:9919` inside the container identical to the host's loopback.

---

### 3. Register the native host (same for both options)

Create the directory if it does not exist:

```bash
mkdir -p ~/.mozilla/native-messaging-hosts
```

Copy the template manifest:

```bash
cp fetchgate.json ~/.mozilla/native-messaging-hosts/fetchgate.json
```

Open `~/.mozilla/native-messaging-hosts/fetchgate.json` and replace the
placeholder `path` value with the absolute path to your launcher script:

```json
"path": "/home/you/bin/fetchgate.sh"
```

Do not change the `"name"` field — it must stay `"fetchgate"` to match what
the extension asks for.

### 4. Smoke test

Load the extension first (see [Load the extension](#load-the-extension) below),
then:

1. Open any website you are logged into
2. Click the **FetchGate** toolbar button — the badge turns green **ON**
3. In a terminal:

```bash
echo '{"method":"GET","url":"/robots.txt"}' | timeout 3 nc localhost 9919
```

You should get back a single JSON line with `status`, `headers`, and `body`.
`timeout 3` is needed because the host keeps connections open for persistent
callers — without it, `nc` hangs waiting for more data after the response.
`/robots.txt` is a safe first test — it is always small. Avoid using `"url":"/"`
as the initial test: many homepages exceed the 1 MB Native Messaging size limit
and will return an error even on a perfectly healthy setup.

---

## Python host

### 1. Write your script

Copy the example script to a permanent location and edit it:

```bash
cp host_py/example.py ~/bin/my_scraper.py
chmod +x ~/bin/my_scraper.py
```

Open `~/bin/my_scraper.py` and replace the `fg.fetch(...)` call with
your own requests. The important lines to keep are:

```python
#!/usr/bin/env python3
import sys, os
sys.path.insert(0, "/absolute/path/to/FetchGate/host_py")
from fetchgate import FetchGate, FetchGateError

fg = FetchGate()
```

Everything after `fg = FetchGate()` is your code. Use `fg.fetch(spec)` to
send requests and get responses.

> **stdout is redirected.** After `FetchGate()` is constructed, `sys.stdout`
> is redirected to `sys.stderr` to prevent accidental `print()` calls from
> corrupting the Native Messaging stream. Use `sys.__stdout__` to write
> results to real stdout (e.g. for piping to a file):
>
> ```python
> sys.__stdout__.write(resp["body"])
> sys.__stdout__.flush()
> ```

### 2. Register the native host

```bash
mkdir -p ~/.mozilla/native-messaging-hosts
cp fetchgate_py.json ~/.mozilla/native-messaging-hosts/fetchgate.json
```

Open `~/.mozilla/native-messaging-hosts/fetchgate.json` and replace the
placeholder `path` with the absolute path to your script:

```json
"path": "/home/you/bin/my_scraper.py"
```

The script must be executable (`chmod +x`) and must have a shebang line
(`#!/usr/bin/env python3`) — Firefox uses the shebang to launch it.

### 3. Run it

1. Navigate to the target website in Firefox/LibreWolf (log in if needed)
2. Click the **FetchGate** toolbar button

Firefox launches your script immediately when the tab is armed. The badge turns
green **ON** while the script runs, then **ERR** when it exits. The ERR badge
after the script exits is expected — it means the Native Messaging connection
closed because your script finished.

Click the toolbar button again to re-arm and re-run the script.

> **Firefox starts the script, not you.** There is no process to launch
> manually. Just arm the tab.

---

## Load the extension

The extension setup is the same for both hosts.

### Option A — Temporary (easiest, requires re-loading after each browser restart)

1. Open Firefox or LibreWolf and navigate to `about:debugging`
2. Click **This Firefox**
3. Click **Load Temporary Add-on...**
4. Select the `extension/manifest.json` file from this project

The FetchGate icon appears in the toolbar. It stays loaded until you restart
the browser. Repeat this step after each restart.

### Option B — Persistent (survives restarts)

Standard Firefox enforces extension signing, but you can bypass this for
personal use with a developer-friendly Firefox variant:

1. Use **Firefox Developer Edition**, **Firefox Nightly**, or **LibreWolf**
   (these allow disabling the signature requirement)
2. Navigate to `about:config` and set:
   ```
   xpinstall.signatures.required = false
   ```
3. Package the extension as a `.xpi` file:
   ```bash
   cd extension && zip -r ../fetchgate.xpi . && cd ..
   ```
4. Navigate to `about:addons`, click the gear icon ⚙, and choose
   **Install Add-on From File...**
5. Select `fetchgate.xpi`

The extension now persists across browser restarts without needing
`about:debugging`.

---

## Troubleshooting

**Nothing comes back from `nc` / the script doesn't seem to run:**

Open the extension's background console:
- Go to `about:debugging` → **This Firefox** → find FetchGate → click **Inspect**
- Switch to the **Console** tab

You should see messages like:
```
[FetchGate] Connecting to native host...
[FetchGate] Tab armed: 42
```

If you see an error like `"Could not establish connection to native host"`,
the native messaging manifest is probably wrong — check that:
- `~/.mozilla/native-messaging-hosts/fetchgate.json` exists
- The `"path"` in that file is an absolute path to an executable file
- The executable has `chmod +x` and (for Python scripts) a `#!/usr/bin/env python3` shebang
- You reloaded the extension after editing the manifest

**The badge shows ERR immediately after arming (Java host):**

The Java host failed to start. Check:
- `out/` directory exists and contains `.class` files (run `javac -d out src/*.java`)
- The path in `fetchgate.sh` is correct
- `fetchgate.sh` is executable

**The badge shows ERR immediately after arming (Python host):**

Your script exited with an error. Run it directly in a terminal to see the
output:

```bash
python3 ~/bin/my_scraper.py
```

It will fail (no NM stream available), but import errors and syntax errors will
show up here.

**The badge shows ON but requests return `{"error":"no tab is armed"}`:**

The extension thinks no tab is armed (background script state was lost, e.g.
after an extension reload). Disarm and re-arm the tab by clicking the toolbar
button twice.

**Switching between Java and Python host:**

Only one host can be active at a time — both use the filename
`~/.mozilla/native-messaging-hosts/fetchgate.json`. To switch:

```bash
# Switch to Java host
cp fetchgate.json ~/.mozilla/native-messaging-hosts/fetchgate.json
# (edit "path" to point to fetchgate.sh)

# Switch to Python host
cp fetchgate_py.json ~/.mozilla/native-messaging-hosts/fetchgate.json
# (edit "path" to point to your script)
```

After switching, reload the extension in `about:debugging` for the change
to take effect.
