'use strict';

// Guard against double-injection (can happen if the background script
// re-injects after navigation while the previous instance is still alive).
if (!window.__fetchGateInstalled) {
    window.__fetchGateInstalled = true;

    browser.runtime.onMessage.addListener((request, _sender, sendResponse) => {
        executeFetch(request).then(sendResponse);
        return true; // keep the message channel open while the Promise resolves
    });
}

async function executeFetch(spec) {
    try {
        const init = { method: spec.method || 'GET' };
        if (spec.headers     != null) init.headers     = spec.headers;
        if (spec.body        != null) init.body        = spec.body;
        if (spec.credentials != null) init.credentials = spec.credentials;

        // Resolve relative URLs against the current page origin.
        // Firefox's fetch() in a content script context does not do this
        // automatically — passing "/" directly throws "/ is not a valid URL."
        const url = new URL(spec.url, location.href).href;
        const response = await fetch(url, init);

        const headers = {};
        response.headers.forEach((value, name) => { headers[name] = value; });

        const body = await response.text();

        const reply = {
            status:     response.status,
            statusText: response.statusText,
            headers,
            body,
        };

        // Firefox's Native Messaging protocol caps messages at 1 MB. A reply
        // that is too large would cause Firefox to silently close the port when
        // background.js tries to postMessage it to the native host. Reject early
        // with a clear error instead. Measure the full serialised reply — not
        // just the body — because headers, status fields, and JSON escaping of
        // quote/backslash-heavy bodies all contribute to the final wire size.
        // TextEncoder gives true UTF-8 byte count; string .length undercounts
        // multi-byte characters (e.g. one CJK char = 3 UTF-8 bytes).
        // background.js adds ~20 bytes of __fg_id envelope on top, so
        // 1 000 000 bytes is a safe ceiling with ample headroom.
        const NM_LIMIT_BYTES = 1_000_000;
        const replyBytes = new TextEncoder().encode(JSON.stringify(reply)).byteLength;
        if (replyBytes > NM_LIMIT_BYTES) {
            return { error: `response too large (${replyBytes} bytes serialized; Native Messaging limit is 1 MB)` };
        }

        return reply;
    } catch (e) {
        return { error: e.message };
    }
}
