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

        // Firefox's Native Messaging protocol caps messages at 1 MB. A response
        // body that is too large would cause Firefox to close the port when
        // background.js tries to postMessage it to the native host, which the
        // host surfaces as "connection to Firefox was closed". Reject early with
        // a clear error instead of silently breaking the connection.
        // 900 000 chars leaves ~100 KB headroom for status, headers, and JSON
        // escaping overhead before hitting the 1 MB wire limit.
        const BODY_LIMIT = 900_000;
        if (body.length > BODY_LIMIT) {
            return { error: `response body too large (${body.length} chars; Native Messaging limit is ~900 KB)` };
        }

        return {
            status:     response.status,
            statusText: response.statusText,
            headers,
            body,
        };
    } catch (e) {
        return { error: e.message };
    }
}
