import { appendFile, readFile, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { schnorr } from '@noble/curves/secp256k1.js';
import { sha256 } from '@noble/hashes/sha256.js';
import { bech32 } from '@scure/base';

const demoRoot = new URL('../../nostrads-js/src/demo/', import.meta.url);
const hex = bytes => Buffer.from(bytes).toString('hex');
const hash = bytes => hex(sha256(bytes));
const tag = (event, name) => event.tags.find(value => value[0] === name)?.[1];
const expiration = event => Number(tag(event, 'expiration'));
const lifetime = 7 * 86400;
const renewBefore = 2 * 86400;

export function decodeKey(value) {
    try {
        let key;
        const input = value.trim();
        if (/^[0-9a-f]{64}$/i.test(input)) {
            key = Uint8Array.from(Buffer.from(input, 'hex'));
        } else {
            const decoded = bech32.decode(input, 100);
            if (decoded.prefix !== 'nsec') throw new Error();
            key = Uint8Array.from(bech32.fromWords(decoded.words));
        }
        if (key.length !== 32) throw new Error();
        schnorr.getPublicKey(key);
        return key;
    } catch {
        throw new Error('NOSTRADS_DEMO_PRIVATE_KEY must be a valid 64-character hex key or nsec.');
    }
}

function eventId(event) {
    return hash(new TextEncoder().encode(JSON.stringify([
        0, event.pubkey, event.created_at, event.kind, event.tags, event.content
    ])));
}

export function signEvent(event, key) {
    const signed = { ...event, pubkey: hex(schnorr.getPublicKey(key)) };
    signed.id = eventId(signed);
    signed.sig = hex(schnorr.sign(signed.id, key));
    return signed;
}

export function verifyEvent(event) {
    try {
        return /^[0-9a-f]{64}$/.test(event.id) && /^[0-9a-f]{64}$/.test(event.pubkey)
            && Number.isSafeInteger(event.created_at) && event.created_at >= 0
            && Number.isSafeInteger(event.kind) && typeof event.content === 'string'
            && Array.isArray(event.tags) && event.tags.every(t => Array.isArray(t)
                && t.length > 0 && t.every(v => typeof v === 'string'))
            && event.id === eventId(event) && schnorr.verify(event.sig, event.id, event.pubkey);
    } catch {
        return false;
    }
}

// Use one connection per relay. ACKs and EOSE are required; socket closure is a failure.
export async function connectRelay(url, { WebSocketImpl = WebSocket, timeoutMs = 15000 } = {}) {
    const socket = new WebSocketImpl(url);
    const queries = new Map();
    const acknowledgements = new Map();
    const failure = () => new Error(`Relay connection failed: ${url}`);
    const rejectPending = () => {
        for (const entry of [...queries.values(), ...acknowledgements.values()]) entry.reject(failure());
    };
    socket.addEventListener('error', rejectPending);
    socket.addEventListener('close', rejectPending);
    try {
        await new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(failure()), timeoutMs);
            const cleanup = () => {
                clearTimeout(timer);
                socket.removeEventListener('error', failed);
                socket.removeEventListener('close', failed);
                socket.removeEventListener('open', opened);
            };
            const failed = () => { cleanup(); reject(failure()); };
            const opened = () => { cleanup(); resolve(); };
            socket.addEventListener('error', failed);
            socket.addEventListener('close', failed);
            socket.addEventListener('open', opened);
        });
    } catch (error) {
        socket.close();
        throw error;
    }
    socket.addEventListener('message', message => {
        try {
            if (typeof message.data !== 'string' || message.data.length > 65536) return;
            const [type, id, value] = JSON.parse(message.data);
            const query = queries.get(id);
            if (type === 'EVENT' && query && query.events.length < 64) query.events.push(value);
            if (type === 'EOSE' && query) query.resolve(query.events);
            if (type === 'CLOSED' && query) query.reject(new Error(`Relay refused query: ${url}`));
            const ack = acknowledgements.get(id);
            if (type === 'OK' && ack) {
                if (value === true) ack.resolve();
                else ack.reject(new Error(`Relay refused publication: ${url}`));
            }
        } catch {
            // Ignore malformed messages. Timeouts still fail the operation.
        }
    });
    function request(pending, id, message) {
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => finish(reject, new Error(`Relay timed out: ${url}`)), timeoutMs);
            const finish = (callback, value) => {
                clearTimeout(timer);
                pending.delete(id);
                if (pending === queries && socket.readyState === 1) socket.send(JSON.stringify(['CLOSE', id]));
                callback(value);
            };
            pending.set(id, {
                events: [],
                resolve: value => finish(resolve, value),
                reject: error => finish(reject, error)
            });
            try {
                if (socket.readyState !== 1) throw failure();
                socket.send(JSON.stringify(message));
            } catch {
                finish(reject, failure());
            }
        });
    }
    return {
        query: filter => {
            const id = randomUUID();
            return request(queries, id, ['REQ', id, filter]);
        },
        publish: event => request(acknowledgements, event.id, ['EVENT', event]),
        close: () => socket.close()
    };
}

function trustedImage(url, catalog, digest) {
    const resource = new URL(url);
    if (resource.protocol !== 'https:' || resource.username || resource.password
        || !catalog.imageOrigins.includes(resource.origin)
        || !new RegExp(`/${digest}(?:\\.[a-zA-Z0-9]+)?$`).test(resource.pathname)) {
        throw new Error('Demo image URL must match its hash and a configured HTTPS image origin.');
    }
    return resource.href;
}

async function imageAvailable(url, bytes, fetchImpl) {
    const response = await fetchImpl(url, { signal: AbortSignal.timeout(30000) });
    if (!response.ok) {
        await response.body?.cancel();
        return false;
    }
    const downloaded = new Uint8Array(await response.arrayBuffer());
    return downloaded.length === bytes.length && hash(downloaded) === hash(bytes);
}

export async function ensureImage({ catalog, event, bytes, key, now, fetchImpl = fetch, checkOnly = false }) {
    const digest = hash(bytes);
    const original = trustedImage(JSON.parse(event.content).payload, catalog, digest);
    if (await imageAvailable(original, bytes, fetchImpl)) return { url: original, restored: false };
    if (checkOnly) throw new Error('Demo image needs restoration.');
    for (const endpoint of catalog.uploadEndpoints) {
        const server = new URL(endpoint);
        if (server.protocol !== 'https:' || server.username || server.password
            || !catalog.imageOrigins.includes(server.origin)) throw new Error('Invalid demo upload endpoint.');
        const authorization = signEvent({
            kind: 24242, created_at: now - 1, content: 'Restore NostrAds demo image',
            tags: [['t', 'upload'], ['x', digest], ['server', server.hostname], ['expiration', String(now + 300)]]
        }, key);
        const response = await fetchImpl(new URL('/upload', server), {
            method: 'PUT', redirect: 'error', signal: AbortSignal.timeout(30000), body: bytes,
            headers: {
                Authorization: `Nostr ${Buffer.from(JSON.stringify(authorization)).toString('base64url')}`,
                'Content-Type': 'image/png', 'Content-Length': String(bytes.length), 'X-SHA-256': digest
            }
        });
        if (!response.ok) { await response.body?.cancel(); continue; }
        const descriptor = await response.json();
        if (descriptor.sha256 !== digest || descriptor.size !== bytes.length) {
            throw new Error('Blossom returned an incorrect demo image hash or size.');
        }
        const url = trustedImage(descriptor.url, catalog, digest);
        if (!await imageAvailable(url, bytes, fetchImpl)) throw new Error('Restored demo image failed read-back.');
        return { url, restored: true };
    }
    throw new Error('Could not restore demo image on the configured Blossom servers.');
}

function sameCampaign(event, seed, advertiser, now) {
    return verifyEvent(event) && event.kind === 30100 && event.pubkey === advertiser
        && event.created_at <= now && tag(event, 'd') === tag(seed, 'd')
        && Number.isSafeInteger(expiration(event)) && expiration(event) > event.created_at
        && JSON.parse(event.content).context === JSON.parse(seed.content).context;
}

function newest(events) {
    return [...events].sort((a, b) => b.created_at - a.created_at || a.id.localeCompare(b.id))[0];
}

export async function synchronizeDemo({ catalog, seeds, key, now = Math.floor(Date.now() / 1000),
    relayFactory = connectRelay, readImage = path => readFile(new URL(path, demoRoot)),
    fetchImpl = fetch, checkOnly = false, log = console.log }) {
    const advertiser = hex(schnorr.getPublicKey(key));
    if (advertiser !== catalog.advertiser) {
        throw new Error('Demo key does not match the advertiser and encrypted delegate configuration in the catalog.');
    }
    if (seeds.length !== catalog.campaigns.length || new Set(seeds.map(s => s.campaign)).size !== seeds.length) {
        throw new Error('Demo seed archive must contain one signed event per campaign.');
    }
    for (const campaign of catalog.campaigns) {
        const seed = seeds.find(s => s.campaign === campaign.id)?.event;
        if (!seed || !sameCampaign(seed, seed, advertiser, now)
            || JSON.parse(seed.content).context !== `nostrads-demo:${campaign.id}`
            || tag(seed, 'D') !== catalog.delegate || tag(seed, 's') !== campaign.size) {
            throw new Error(`Invalid signed demo seed: ${campaign.id}`);
        }
    }
    const relays = [];
    const report = { advertiser, published: 0, renewed: 0, imagesRestored: 0, unchanged: 0 };
    try {
        if (!catalog.relays.length) throw new Error('No demo relays configured.');
        for (const url of catalog.relays) {
            if (new URL(url).protocol !== 'wss:') throw new Error('Demo publishing requires WSS relays.');
            relays.push(await relayFactory(url));
        }
        const inventories = await Promise.all(relays.map(relay => relay.query({
            kinds: [30100], authors: [advertiser], '#d': seeds.map(s => tag(s.event, 'd')),
            limit: seeds.length
        })));
        for (const campaign of catalog.campaigns) {
            const seed = seeds.find(s => s.campaign === campaign.id).event;
            const current = newest([seed, ...inventories.flat()].filter(event => {
                try { return sameCampaign(event, seed, advertiser, now); } catch { return false; }
            }));
            const bytes = await readImage(campaign.image);
            const image = await ensureImage({ catalog, event: current, bytes, key, now, fetchImpl, checkOnly });
            if (image.restored) report.imagesRestored++;
            const content = JSON.parse(seed.content);
            content.payload = image.url;
            content.description = campaign.description;
            content.call_to_action = campaign.callToAction;
            const changed = JSON.stringify(content) !== current.content;
            const missing = inventories.some(events => !events.some(stored => stored.id === current.id && verifyEvent(stored)));
            let event = current;
            if (expiration(current) <= now + renewBefore || changed || (missing && current.created_at < now - 86400)) {
                if (checkOnly) throw new Error(`Demo campaign needs renewal: ${campaign.id}`);
                event = signEvent({
                    kind: 30100, created_at: Math.max(now, current.created_at + 1),
                    tags: seed.tags.map(t => t[0] === 'expiration' ? ['expiration', String(now + lifetime)] : t),
                    content: JSON.stringify(content)
                }, key);
                report.renewed++;
            }
            let writes = 0;
            for (let index = 0; index < relays.length; index++) {
                if (inventories[index].some(stored => stored.id === event.id && verifyEvent(stored))) continue;
                if (checkOnly) throw new Error(`Demo campaign missing from a relay: ${campaign.id}`);
                const relay = relays[index];
                await relay.publish(event);
                let stored = false;
                for (let attempt = 0; attempt < 3 && !stored; attempt++) {
                    if (attempt) await delay(500);
                    const found = await relay.query({ ids: [event.id], limit: 1 });
                    stored = found.some(value => value.id === event.id && verifyEvent(value));
                }
                if (!stored) throw new Error(`Demo publication failed read-back: ${campaign.id}`);
                writes++;
                report.published++;
            }
            if (!writes) report.unchanged++;
            log(`${campaign.id}: ${writes ? `confirmed on ${relays.length} relays` : 'already available'}`);
        }
        return report;
    } finally {
        for (const relay of relays) relay.close();
    }
}

export async function run({ env = process.env, log = console.log, checkOnly = false } = {}) {
    const catalog = JSON.parse(await readFile(new URL('catalog.json', demoRoot), 'utf8'));
    let advertiser = catalog.advertiser;
    if (!env.NOSTRADS_DEMO_PRIVATE_KEY?.trim()) {
        log('NOSTRADS_DEMO_PRIVATE_KEY is not configured; retaining the existing public demo identity.');
    } else {
        const key = decodeKey(env.NOSTRADS_DEMO_PRIVATE_KEY);
        // Remove the encoded secret from the environment before doing network I/O.
        delete env.NOSTRADS_DEMO_PRIVATE_KEY;
        try {
            const seeds = JSON.parse(await readFile(new URL('../../demo/seed-events.json', demoRoot), 'utf8'));
            const report = await synchronizeDemo({ catalog, seeds, key, checkOnly, log });
            advertiser = report.advertiser;
            log(`Demo sync: ${report.published} publications, ${report.renewed} renewals, ${report.imagesRestored} image restores.`);
        } finally {
            key.fill(0);
        }
    }
    const networkPath = new URL('network.json', demoRoot);
    const network = JSON.parse(await readFile(networkPath, 'utf8'));
    network.advertiser = advertiser;
    await writeFile(networkPath, `${JSON.stringify(network, null, 2)}\n`);
    if (env.GITHUB_OUTPUT) await appendFile(env.GITHUB_OUTPUT, `advertiser=${advertiser}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    run({ checkOnly: process.argv.includes('--check') }).catch(() => {
        // Never echo raw exceptions from crypto, wallet payloads, HTTP responses or environment input.
        console.error('Demo synchronization failed. Check the demo identity, signed seeds, relay and Blossom availability.');
        process.exitCode = 1;
    });
}
