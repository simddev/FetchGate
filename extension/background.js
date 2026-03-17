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
    });

    console.log('[FetchGate] Connected to native host.');
}

// Forward the request to the armed tab's content script, then send the
// response back to the host.
async function onRequestFromHost(request) {
    if (armedTabId === null) {
        sendToHost({ error: 'no tab is armed' });
        return;
    }

    try {
        const response = await browser.tabs.sendMessage(armedTabId, request);
        sendToHost(response);
    } catch (e) {
        sendToHost({ error: e.message });
    }
}

function sendToHost(message) {
    if (port) {
        port.postMessage(message);
    } else {
        console.error('[FetchGate] Cannot send — not connected to native host.');
    }
}

// ─── Tab arming ──────────────────────────────────────────────────────────────

// Toolbar button clicked: toggle armed state for the current tab.
browser.browserAction.onClicked.addListener(async (tab) => {
    if (armedTabId === tab.id) {
        disarm(tab.id);
    } else {
        // Disarm whatever was armed before (if anything).
        if (armedTabId !== null) disarm(armedTabId);
        await arm(tab.id);
    }
});

async function arm(tabId) {
    armedTabId = tabId;
    browser.browserAction.setBadgeText({ text: 'ON', tabId });
    browser.browserAction.setBadgeBackgroundColor({ color: '#00aa00', tabId });

    // Inject the content script.  The guard in content_script.js makes
    // re-injection after navigation harmless.
    await browser.tabs.executeScript(tabId, { file: 'content_script.js' })
                 .catch(e => console.error('[FetchGate] Script injection failed:', e.message));

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
browser.tabs.onUpdated.addListener((tabId, changeInfo) => {
    if (tabId === armedTabId && changeInfo.status === 'complete') {
        browser.tabs.executeScript(tabId, { file: 'content_script.js' })
               .catch(e => console.error('[FetchGate] Re-inject after navigation failed:', e.message));
    }
});
