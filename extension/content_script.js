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

        return {
            status:     response.status,
            statusText: response.statusText,
            headers,
            body:       await response.text(),
        };
    } catch (e) {
        return { error: e.message };
    }
}
