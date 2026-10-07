import NostrAds from '../src/console/nostr-ads.js';

function assert(condition, message) {
    if (!condition) throw new Error(message);
}

async function withTimeout(promise, label) {
    let timer;
    try {
        return await Promise.race([
            promise,
            new Promise((_, reject) => {
                timer = setTimeout(() => reject(new Error(`${label} timed out`)), 3000);
            })
        ]);
    } finally {
        clearTimeout(timer);
    }
}

// Exercise the actual compiled TeaVM bindings without contacting a relay or wallet.
export async function runPublicationTests() {
    const OriginalWebSocket = globalThis.WebSocket;
    const pending = [];
    let eventReceived;
    let sent = 0;
    globalThis.WebSocket = class extends EventTarget {
        constructor() {
            super();
            this.readyState = 0;
            setTimeout(() => {
                this.readyState = 1;
                this.dispatchEvent(new Event('open'));
            }, 0);
        }

        send(message) {
            const [type, event] = JSON.parse(message);
            if (type !== 'EVENT') return;
            sent++;
            pending.push(success => {
                this.dispatchEvent(new MessageEvent('message', {
                    data: JSON.stringify(['OK', event.id, success, success ? '' : 'blocked: test rejection'])
                }));
            });
            eventReceived?.();
        }

        close() { this.readyState = 3; }
    };

    const client = NostrAds.newAdvertiserClient(['wss://publication.invalid'], '0'.repeat(63) + '1', []);
    try {
        await withTimeout(client.getPublicKey(), 'client initialization');
        const bid = {
            description: 'Offline publication regression',
            mimeType: 'text/plain',
            payload: 'Test advertisement',
            size: '480x60',
            link: 'https://example.com',
            actionType: 'view',
            context: null,
            callToAction: null,
            category: [],
            languages: [],
            offerersWhitelist: [],
            appsWhitelist: [],
            delegate: NostrAds.getPublicKey('0'.repeat(63) + '2'),
            nwc: 'offline-test-value',
            dailyBudget: 10000,
            bid: 1000,
            holdTime: 60,
            expire_at: Date.now() + 300000,
            maxPayouts: 3,
            payoutResetInterval: 300
        };

        // A failed publication must reject, and a later successful call must still work.
        for (const accepted of [true, false, true]) {
            let settled = false;
            const received = new Promise(resolve => { eventReceived = resolve; });
            const publication = client.publish(bid).then(
                event => { settled = true; return {event}; },
                error => { settled = true; return {error}; }
            );
            await withTimeout(received, 'relay event');
            await new Promise(resolve => setTimeout(resolve, 25));
            assert(!settled, 'publication settled before the relay acknowledgement');
            pending.shift()(accepted);
            const result = await withTimeout(publication, accepted ? 'accepted publication' : 'rejected publication');
            if (accepted) {
                assert(result.event?.id && !result.error, 'successful publication was not returned');
            } else {
                assert(
                    String(result.error).includes('Failed to achieve required acknowledgements'),
                    `relay rejection was not reported: ${result.error}`
                );
            }
        }

        const invalid = await withTimeout(client.publish({...bid, link: 'invalid'}).then(
            () => ({resolved: true}),
            error => ({error})
        ), 'invalid bid');
        assert(String(invalid.error).includes('Invalid link URL'), 'bid validation failure was not reported');
        assert(sent === 3, 'an invalid bid was published or a valid bid was sent more than once');
    } finally {
        client.close();
        // close() dispatches through the Java thread context.
        await new Promise(resolve => setTimeout(resolve, 0));
        globalThis.WebSocket = OriginalWebSocket;
    }
}
