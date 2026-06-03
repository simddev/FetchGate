'use strict';

const dot          = document.getElementById('dot');
const statusLabel  = document.getElementById('status-label');
const statusDetail = document.getElementById('status-detail');
const actionBtn    = document.getElementById('action-btn');
const shortcutVal  = document.getElementById('shortcut-val');
const shortcutRow  = document.getElementById('shortcut-row');

// Module-level so all functions (including startRecording's closures) can reach it.
let bg = null;
let currentTab = null;

async function init() {
    bg = await browser.runtime.getBackgroundPage();
    currentTab = await bg.getActiveTab();

    await renderStatus();

    // Refresh status while the popup is open — catches arm/disarm via keyboard shortcut.
    setInterval(renderStatus, 750);

    // Open the host setup page when the user clicks any ? badge in the status detail.
    statusDetail.addEventListener('click', (e) => {
        if (e.target.classList.contains('host-help')) {
            browser.tabs.create({ url: browser.runtime.getURL('help.html') });
        }
    });

    await refreshShortcut();
    shortcutRow.addEventListener('click', startRecording);
}

async function renderStatus() {
    const { armedTabId, portConnected, lastDisarmReason, lastDisarmedTabId } = bg.getState();

    let armedTab = null;
    if (armedTabId !== null) {
        try { armedTab = await browser.tabs.get(armedTabId); } catch (_) {}
    }

    const onThisTab  = currentTab && armedTabId === currentTab.id;
    const onOtherTab = armedTabId !== null && !onThisTab;

    if (armedTabId === null) {
        // ── Disarmed ─────────────────────────────────────────────
        setDot('disarmed');
        statusLabel.textContent  = 'Disarmed';
        const onDisarmedTab = currentTab && currentTab.id === lastDisarmedTabId;
        statusDetail.textContent = (onDisarmedTab && lastDisarmReason) || 'No tab is currently armed.';
        setBtn('arm', 'Arm this tab', async () => {
            if (currentTab) await bg.arm(currentTab.id);
            window.close();
        });

    } else if (onThisTab && portConnected) {
        // ── Armed — this tab, host OK ─────────────────────────────
        setDot('armed');
        statusLabel.textContent = 'Armed';
        statusDetail.innerHTML  = domain(armedTab) + '<br>Host: connected <span class="host-help">?</span>';
        setBtn('disarm', 'Disarm', () => {
            bg.disarm(armedTabId, 'Tab has been disarmed.');
            window.close();
        });

    } else if (onThisTab && !portConnected) {
        // ── Armed — this tab, host disconnected ──────────────────
        setDot('error');
        statusLabel.textContent = 'Armed — host disconnected';
        statusDetail.innerHTML  = domain(armedTab) +
            '<br>Host: not running <span class="host-help">?</span>' +
            '<br><span style="color:#555;font-size:11px">Press ? for setup instructions.</span>';
        setBtn('reconnect', 'Reconnect', async () => {
            await bg.arm(armedTabId);
            window.close();
        });

    } else if (onOtherTab) {
        // ── Armed — different tab ─────────────────────────────────
        setDot(portConnected ? 'armed' : 'error');
        statusLabel.textContent = 'Armed (other tab)';
        statusDetail.innerHTML  = domain(armedTab) +
            (portConnected
                ? '<br>Host: connected <span class="host-help">?</span>'
                : '<br>Host: not running <span class="host-help">?</span>' +
                  '<br><span style="color:#555;font-size:11px">Press ? for setup instructions.</span>');
        setBtn('switch', 'Arm this tab instead', async () => {
            bg.disarm(armedTabId, 'Switched to a different tab.');
            if (currentTab) await bg.arm(currentTab.id);
            window.close();
        });
    }
}

async function refreshShortcut() {
    const cmds = await browser.commands.getAll();
    const cmd  = cmds.find(c => c.name === 'toggle-arm');
    shortcutVal.textContent = cmd?.shortcut || 'Not set';
}

let recording = false;

function startRecording() {
    if (recording) return;
    recording = true;
    shortcutVal.textContent = 'Press keys…';
    shortcutVal.className = 'recording';

    // Auto-cancel after 8 s if the user walks away without pressing anything.
    let autoCancel = setTimeout(() => cancel(), 8000);

    function recordingError(msg) {
        shortcutVal.textContent = msg;
        shortcutVal.className = 'error';
        setTimeout(() => {
            if (recording) {
                shortcutVal.textContent = 'Press keys…';
                shortcutVal.className = 'recording';
            }
        }, 1200);
    }

    function onKey(e) {
        e.preventDefault();
        e.stopPropagation();

        if (e.key === 'Escape') { cancel(); return; }
        if (['Control', 'Alt', 'Shift', 'Meta'].includes(e.key)) return;

        const shortcut = eventToShortcut(e);
        if (!shortcut)                      { recordingError('Key not supported'); return; }
        if (FIREFOX_RESERVED.has(shortcut)) { recordingError('Unavailable shortcut'); return; }

        // Pause input during async API call — keep recording=true so a concurrent
        // startRecording() call is still blocked, but remove the listener so
        // keypresses during the API round-trip don't queue up.
        document.removeEventListener('keydown', onKey, true);
        clearTimeout(autoCancel);

        shortcutVal.textContent = shortcut;

        browser.commands.update({ name: 'toggle-arm', shortcut })
            .then(async () => {
                const cmds   = await browser.commands.getAll();
                const actual = cmds.find(c => c.name === 'toggle-arm')?.shortcut;
                if (actual === shortcut) {
                    stop();
                    shortcutVal.className = 'success';
                    bg.startShortcutVerification(shortcut);
                    setTimeout(() => window.close(), 800);
                } else {
                    resume();
                    recordingError('Unavailable shortcut');
                }
            })
            .catch(() => {
                resume();
                recordingError('Unavailable shortcut');
            });
    }

    function resume() {
        autoCancel = setTimeout(() => cancel(), 8000);
        document.addEventListener('keydown', onKey, true);
    }

    function cancel() { stop(); refreshShortcut(); }

    function stop() {
        recording = false;
        clearTimeout(autoCancel);
        shortcutVal.className = '';
        document.removeEventListener('keydown', onKey, true);
    }

    // No blur listener — browser action popups close on outside click (listeners
    // are GC'd automatically). A blur listener would cancel recording on every
    // desktop notification, making the feature unusable.
    document.addEventListener('keydown', onKey, true);
}

// First-pass filter for universally certain Firefox shortcuts.
// Kept intentionally small — Ctrl+Shift combos were removed because Firefox
// version differences caused too many false positives. Runtime verification
// (startShortcutVerification in background.js) catches everything else reliably.
const FIREFOX_RESERVED = new Set([
    'Ctrl+A','Ctrl+C','Ctrl+V','Ctrl+X','Ctrl+Z','Ctrl+Y',
    'Ctrl+N','Ctrl+T','Ctrl+W','Ctrl+L','Ctrl+R','Ctrl+F',
    'Ctrl+H','Ctrl+U','Ctrl+M',
    'Ctrl+Shift+N','Ctrl+Shift+T','Ctrl+Shift+W',
    'Ctrl+1','Ctrl+2','Ctrl+3','Ctrl+4','Ctrl+5',
    'Ctrl+6','Ctrl+7','Ctrl+8','Ctrl+9',
    'F1','F5','F11',
    'Alt+F4','Alt+Left','Alt+Right',
]);

function eventToShortcut(e) {
    if (['Control', 'Alt', 'Shift', 'Meta'].includes(e.key)) return null;

    const parts = [];
    if (e.ctrlKey)  parts.push('Ctrl');
    if (e.altKey)   parts.push('Alt');
    if (e.shiftKey) parts.push('Shift');

    const special = {
        F1:'F1',F2:'F2',F3:'F3',F4:'F4',F5:'F5',F6:'F6',
        F7:'F7',F8:'F8',F9:'F9',F10:'F10',F11:'F11',F12:'F12',
        ' ':'Space',ArrowUp:'Up',ArrowDown:'Down',
        ArrowLeft:'Left',ArrowRight:'Right',
        Insert:'Insert',Delete:'Delete',
        Home:'Home',End:'End',PageUp:'PageUp',PageDown:'PageDown',
        ',':'Comma','.':'Period',
    };

    let main = special[e.key];
    if (!main) {
        if (e.key.length === 1 && /[a-zA-Z0-9]/.test(e.key)) {
            main = e.key.toUpperCase();
        } else {
            return null;
        }
    }

    // Firefox requires Ctrl or Alt for letter/number keys (Shift alone is not enough)
    if (!e.ctrlKey && !e.altKey && !/^F\d+$/.test(main)) return null;

    parts.push(main);
    return parts.join('+');
}

function domain(tab) {
    if (!tab) return '—';
    try {
        // Hostname is always HTML-safe (alphanumeric, dots, hyphens).
        // tab.title is user-controlled and must be escaped before innerHTML use.
        return new URL(tab.url).hostname || escHtml(tab.title) || '—';
    } catch (_) {
        return escHtml(tab.title) || '—';
    }
}

function escHtml(s) {
    if (!s) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function setDot(state) {
    dot.className = `dot-${state}`;
}

function setBtn(style, label, onClick) {
    actionBtn.className = `btn-${style}`;
    actionBtn.textContent = label;
    actionBtn.onclick = onClick;  // assignment replaces the previous handler on each poll
}

init();
