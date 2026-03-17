# FetchGate — Installation

## 1. Compile the Java native host

From the project root:

```bash
javac src/*.java
```

Class files land in `src/`. Requires JDK 21+.

## 2. Create the launcher script

Firefox cannot invoke a `.class` file directly — the native host `path` in the
native messaging manifest must point to an executable. Create a small shell
script (anywhere permanent, e.g. `~/bin/fetchgate.sh`):

```bash
#!/bin/bash
exec java -cp /absolute/path/to/FetchGate/src Main
```

Replace `/absolute/path/to/FetchGate` with the real path. Make it executable:

```bash
chmod +x ~/bin/fetchgate.sh
```

`exec` replaces the shell with the Java process so no wrapper process lingers.

## 3. Register the native host with Firefox/LibreWolf

Create the directory if it does not exist:

```bash
mkdir -p ~/.mozilla/native-messaging-hosts
```

Copy the template manifest from the project root:

```bash
cp fetchgate.json ~/.mozilla/native-messaging-hosts/fetchgate.json
```

Edit `~/.mozilla/native-messaging-hosts/fetchgate.json` and replace the
placeholder `path` value with the absolute path to your launcher script:

```json
"path": "/home/you/bin/fetchgate.sh"
```

The `name` field (`"fetchgate"`) must match the string passed to
`connectNative()` in `background.js` exactly. Do not change it.

## 4. Load the extension

1. Open Firefox or LibreWolf and navigate to `about:debugging`
2. Click **This Firefox**
3. Click **Load Temporary Add-on...**
4. Select `extension/manifest.json`

The FetchGate button appears in the toolbar. It stays loaded until the browser
is restarted. Repeat this step after each restart.

## 5. Smoke test

1. Open any website you are logged into
2. Click the FetchGate toolbar button — the badge turns green **ON**
3. From a terminal, send a test request with netcat:

```bash
echo '{"method":"GET","url":"/"}' | nc localhost 9919
```

You should receive a single line of JSON back containing `status`, `headers`,
and `body`.

If nothing comes back within 30 seconds, open the Firefox browser console
(`about:debugging` → Inspect on FetchGate) to see background script log output.
