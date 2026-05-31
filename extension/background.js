'use strict';

// Open the host setup page the first time the extension is installed.
browser.runtime.onInstalled.addListener(({ reason }) => {
    if (reason === 'install') {
        browser.tabs.create({ url: browser.runtime.getURL('help.html') });
    }
});

// The tab ID that is currently armed (null = no armed tab).
// This is the single source of truth.
let armedTabId = null;

// The Native Messaging port to the native host (null = not connected).
let port = null;

// The reason the tab was last disarmed, and which tab it was.
// Only shown in the popup when the popup is open on that same tab,
// so unrelated tabs see a neutral "No tab is currently armed." instead.
let lastDisarmReason  = null;
let lastDisarmedTabId = null;

// The last tab the user was on — updated by onActivated so the popup can
// find it even when opening a popup shifts the "current window" context.
let lastActiveTabId = null;
browser.tabs.query({ active: true, currentWindow: true }).then(([t]) => { if (t) lastActiveTabId = t.id; });
browser.tabs.onActivated.addListener(({ tabId }) => { lastActiveTabId = tabId; });

// Restore armed state after an extension reload or browser restart.
// Without this, any restart silently clears the armed tab with no notification.
browser.storage.local.get('armedTabId').then(({ armedTabId: savedId }) => {
    if (savedId == null) return;
    browser.tabs.get(savedId)
        .then(() => arm(savedId))
        .catch(() => browser.storage.local.remove('armedTabId'));
});

// ─── Notifications ───────────────────────────────────────────────────────────

function notify(title, message) {
    browser.notifications.create({
        type: 'basic',
        iconUrl: browser.runtime.getURL('icon.svg'),
        title,
        message,
    });
}

// ─── Native Messaging ────────────────────────────────────────────────────────

function connect() {
    port = browser.runtime.connectNative('fetchgate');
    port.onMessage.addListener(onRequestFromHost);

    // Give the host 400 ms to stay alive before declaring the connection good.
    // If the host binary is missing, Firefox disconnects the port within milliseconds —
    // the timer lets us tell "host not found" apart from "host ran fine but later stopped".
    // Capture armedTabId now: if the user arms a different tab before the timer fires,
    // armedTabId will have changed and we must not fire a stale "Armed" notification.
    let hostConfirmed = false;
    const armedTabAtConnect = armedTabId;
    const confirmTimer = setTimeout(() => {
        hostConfirmed = true;
        if (armedTabId !== null && armedTabId === armedTabAtConnect) {
            notify('FetchGate Armed', 'Tab is ready — requests will be routed through this tab.');
        }
    }, 400);

    port.onDisconnect.addListener((p) => {
        clearTimeout(confirmTimer);
        const err = p.error;
        console.log('[FetchGate] Native host disconnected.', err ? err.message : '');
        port = null;
        if (armedTabId !== null) {
            browser.browserAction.setBadgeText({ text: 'ERR', tabId: armedTabId });
            browser.browserAction.setBadgeBackgroundColor({ color: '#cc0000', tabId: armedTabId });
            if (!hostConfirmed) {
                notify('FetchGate — Host Not Found',
                       'The native host could not be started. Open the popup and press ? for setup instructions.');
            } else {
                notify('FetchGate — Native Host Disconnected',
                       'The host process has stopped. Click the toolbar button to reconnect.');
            }
        }
    });

    console.log('[FetchGate] Connecting to native host...');
}

// Forward the request to the armed tab's content script, then send the
// response back to the host.
// The host sends an envelope: { __fg_id: N, req: "ESCAPED_JSON" }.
// __fg_id is echoed in every response so the host can match replies to
// requests and discard stale ones. req is parsed with JSON.parse() here,
// which validates JSON structure before anything reaches the content script.
async function onRequestFromHost(msg) {
    const id        = msg.__fg_id;
    // Capture the port that delivered this request. The fetch() is async and may
    // outlive the current port (e.g. if the host disconnects and reconnects before
    // the fetch resolves). Replying to the originating port rather than the current
    // global port prevents late replies from leaking into a newer host session.
    const replyPort = port;

    function reply(r) {
        if (replyPort) replyPort.postMessage(r);
        else console.error('[FetchGate] Cannot reply — originating port disconnected.');
    }

    let request;
    try {
        request = JSON.parse(msg.req);
    } catch (e) {
        reply({ __fg_id: id, error: 'invalid request JSON: ' + e.message });
        return;
    }

    if (armedTabId === null) {
        reply({ __fg_id: id, error: 'no tab is armed' });
        return;
    }

    try {
        const response = await browser.tabs.sendMessage(armedTabId, request);
        reply({ __fg_id: id, ...response });
    } catch (e) {
        reply({ __fg_id: id, error: e.message });
    }
}

// ─── Tab arming ──────────────────────────────────────────────────────────────

async function arm(tabId) {
    // Inject first: only mark the tab armed if injection actually succeeds.
    // On privileged pages (about:*, browser settings) executeScript throws —
    // proceeding would leave a green badge on a tab that cannot serve requests.
    try {
        await browser.tabs.executeScript(tabId, { file: 'content_script.js' });
    } catch (e) {
        console.error('[FetchGate] Script injection failed:', e.message);
        return; // leave badge unchanged; tab is not armed
    }

    armedTabId = tabId;
    lastDisarmReason = null;
    browser.storage.local.set({ armedTabId: tabId });
    browser.browserAction.setBadgeText({ text: 'ON', tabId });
    browser.browserAction.setBadgeBackgroundColor({ color: '#00aa00', tabId });

    if (!port) {
        // connect() defers the "Armed" notification by 400 ms so that if the host
        // immediately fails, only one "host not found" notification fires instead of
        // "Armed" followed immediately by "disconnected".
        connect();
    } else {
        // Port already alive — host is confirmed running, notify immediately.
        notify('FetchGate Armed', 'Tab is ready — requests will be routed through this tab.');
    }

    console.log('[FetchGate] Tab armed:', tabId);
}

function disarm(tabId, reason) {
    armedTabId        = null;
    lastDisarmReason  = reason || null;
    lastDisarmedTabId = tabId;
    browser.storage.local.remove('armedTabId');
    browser.browserAction.setBadgeText({ text: '', tabId });
    if (reason) notify('FetchGate Disarmed', reason);
    console.log('[FetchGate] Tab disarmed:', tabId);
}

// Called by popup.js — let variables are not properties of window,
// so bg.armedTabId / bg.port would return undefined.
function getState() {
    return { armedTabId, portConnected: !!port, lastDisarmReason, lastDisarmedTabId };
}

// Returns the tab the user was on when they opened the popup.
// popup.js cannot query this itself — opening the popup shifts the
// "current window" context so any query there returns the popup window (no tabs).
async function getActiveTab() {
    if (lastActiveTabId !== null) {
        try { return await browser.tabs.get(lastActiveTabId); } catch (_) {}
    }
    // Fallback: find the active tab in a focused normal browser window.
    const windows = await browser.windows.getAll({ populate: true, windowTypes: ['normal'] });
    const win = windows.find(w => w.focused) || windows[0];
    return win?.tabs?.find(t => t.active) || null;
}

// ─── Keyboard shortcut ───────────────────────────────────────────────────────

// After popup.js sets a new shortcut it calls startShortcutVerification().
// The next onCommand fire within 10 s is treated as the confirmation press rather
// than an arm/disarm, so we know Firefox and the OS are actually passing the key
// through to the extension — something the commands API can't tell us directly.
let shortcutVerifyState = null;

function startShortcutVerification(shortcut) {
    if (shortcutVerifyState) clearTimeout(shortcutVerifyState.timeoutId);
    const timeoutId = setTimeout(() => {
        shortcutVerifyState = null;
        notify('FetchGate — Shortcut Warning',
               `"${shortcut}" didn't respond. It likely conflicts with Firefox or your OS. Open FetchGate to try another.`);
    }, 10000);
    shortcutVerifyState = { shortcut, timeoutId };
    notify('FetchGate — Shortcut Set', `Press ${shortcut} now to verify it works.`);
}

browser.commands.onCommand.addListener((command) => {
    if (command !== 'toggle-arm') return;

    if (shortcutVerifyState) {
        const { shortcut, timeoutId } = shortcutVerifyState;
        clearTimeout(timeoutId);
        shortcutVerifyState = null;
        notify('FetchGate — Shortcut Confirmed', `${shortcut} is working correctly.`);
        return;
    }

    browser.tabs.query({ active: true, currentWindow: true }).then(([tab]) => {
        if (!tab) return;
        if (armedTabId === tab.id && port !== null) {
            disarm(tab.id, 'Tab has been disarmed.');
        } else {
            if (armedTabId !== null) {
                // Don't fire "switched" when reconnecting the same tab from ERR state.
                disarm(armedTabId, armedTabId === tab.id ? null : 'Switched to a different tab.');
            }
            arm(tab.id);
        }
    });
});

// ─── Tab lifecycle ───────────────────────────────────────────────────────────

// If the armed tab is closed, clear state.
browser.tabs.onRemoved.addListener((tabId) => {
    if (tabId === armedTabId) {
        disarm(tabId, 'The armed tab was closed.');
    }
});

// If the armed tab navigates to a new page, re-inject the content script
// (the previous page's content script is destroyed on navigation).
// If re-injection fails (e.g. navigated to a privileged page), disarm so
// the badge does not falsely show ON for a tab that cannot serve requests.
browser.tabs.onUpdated.addListener((tabId, changeInfo) => {
    if (tabId === armedTabId && changeInfo.status === 'complete') {
        browser.tabs.executeScript(tabId, { file: 'content_script.js' })
               .then(() => {
                   // Guard: the user may have disarmed (or armed a different tab) while
                   // executeScript was awaiting. Only act if this tab is still armed.
                   if (tabId !== armedTabId) return;
                   // Re-apply the badge — Firefox resets per-tab badge text on navigation.
                   browser.browserAction.setBadgeText({ text: 'ON', tabId });
                   browser.browserAction.setBadgeBackgroundColor({ color: '#00aa00', tabId });
               })
               .catch(e => {
                   console.error('[FetchGate] Re-inject after navigation failed:', e.message);
                   // Same guard: disarm only if this tab is still the armed one.
                   // Without this check, a stale catch() could call disarm() and clear
                   // armedTabId even though a different tab was already armed successfully.
                   if (tabId === armedTabId) {
                       disarm(tabId, 'The tab navigated to a restricted page — re-arm to continue.');
                   }
               });
    }
});
