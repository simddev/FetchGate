'use strict';

// The tab ID that is currently armed (null = no armed tab).
// This is the single source of truth.
let armedTabId = null;

// The Native Messaging port to the Java host (null = not connected).
let port = null;

// ─── Native Messaging ────────────────────────────────────────────────────────

function connect() {
    port = browser.runtime.connectNative('fetchgate');

    // A message from the host is a fetch request from the external caller.
    port.onMessage.addListener(onRequestFromHost);

    port.onDisconnect.addListener(() => {
        const err = browser.runtime.lastError;
        console.log('[FetchGate] Native host disconnected.',
                    err ? err.message : '');
        port = null;
        // Show an error badge so the user knows the host is gone while the tab is armed.
        if (armedTabId !== null) {
            browser.browserAction.setBadgeText({ text: 'ERR', tabId: armedTabId });
            browser.browserAction.setBadgeBackgroundColor({ color: '#cc0000', tabId: armedTabId });
        }
    });

    console.log('[FetchGate] Connected to native host.');
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

// Toolbar button clicked: toggle armed state for the current tab.
// When the tab is armed but the native host has disconnected (ERR badge,
// port === null), clicking the same tab re-arms rather than disarming —
// one click reconnects instead of requiring disarm + arm.
browser.browserAction.onClicked.addListener(async (tab) => {
    if (armedTabId === tab.id && port !== null) {
        // Tab is armed and the native host is connected — toggle off.
        disarm(tab.id);
    } else {
        // Arm, or re-arm after ERR: disarm whatever was armed first (if anything).
        if (armedTabId !== null) disarm(armedTabId);
        await arm(tab.id);
    }
});

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
    browser.browserAction.setBadgeText({ text: 'ON', tabId });
    browser.browserAction.setBadgeBackgroundColor({ color: '#00aa00', tabId });

    // Connect to the native host on first arm (this launches the Java process).
    if (!port) connect();

    console.log('[FetchGate] Tab armed:', tabId);
}

function disarm(tabId) {
    armedTabId = null;
    browser.browserAction.setBadgeText({ text: '', tabId });
    console.log('[FetchGate] Tab disarmed:', tabId);
}

// ─── Tab lifecycle ───────────────────────────────────────────────────────────

// If the armed tab is closed, clear state.
browser.tabs.onRemoved.addListener((tabId) => {
    if (tabId === armedTabId) {
        armedTabId = null;
        console.log('[FetchGate] Armed tab closed — disarmed.');
    }
});

// If the armed tab navigates to a new page, re-inject the content script
// (the previous page's content script is destroyed on navigation).
// If re-injection fails (e.g. navigated to a privileged page), disarm so
// the badge does not falsely show ON for a tab that cannot serve requests.
browser.tabs.onUpdated.addListener((tabId, changeInfo) => {
    if (tabId === armedTabId && changeInfo.status === 'complete') {
        browser.tabs.executeScript(tabId, { file: 'content_script.js' })
               .catch(e => {
                   console.error('[FetchGate] Re-inject after navigation failed:', e.message);
                   disarm(tabId);
               });
    }
});
