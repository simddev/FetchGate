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
        if (spec.headers  != null) init.headers = spec.headers;
        if (spec.body     != null) init.body    = spec.body;

        const response = await fetch(spec.url, init);

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
