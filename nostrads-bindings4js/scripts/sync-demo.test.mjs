import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { schnorr } from '@noble/curves/secp256k1.js';
import { sha256 } from '@noble/hashes/sha256.js';
import { bech32 } from '@scure/base';
import { connectRelay, decodeKey, ensureImage, signEvent, synchronizeDemo, verifyEvent } from './sync-demo.mjs';

// Public test vector scalar, unrelated to the live demo identity.
const keyHex = '0'.repeat(63) + '1';
const key = decodeKey(keyHex);
const advertiser = Buffer.from(schnorr.getPublicKey(key)).toString('hex');
const now = 1792756800;
const day = 86400;
const bytes = new TextEncoder().encode('isolated demo image fixture');
const digest = Buffer.from(sha256(bytes)).toString('hex');
const imageUrl = `https://images.example/${digest}.png`;

function fixture({ age = 0, remaining = 7 * day, missing = [] } = {}) {
    const catalog = {
        advertiser, delegate: 'b'.repeat(64), relays: ['wss://relay-a.example', 'wss://relay-b.example'],
        imageOrigins: ['https://images.example'], uploadEndpoints: ['https://images.example'],
        campaigns: [{ id: 'test', size: '256x512', image: 'ads/test.png', description: 'Demo', callToAction: 'Open' }]
    };
    const event = signEvent({
        created_at: now - age, kind: 30100,
        tags: [['d', 'stable-demo-campaign'], ['s', '256x512'], ['expiration', String(now + remaining)],
            ['D', catalog.delegate, 'already-encrypted-wallet-configuration']],
        content: JSON.stringify({ max_payouts: 3, call_to_action: 'Open', link: 'https://example.com',
            description: 'Demo', bid: 1000, hold_time: 900, payload: imageUrl,
            payout_reset_interval: 3600, context: 'nostrads-demo:test' })
    }, key);
    const seeds = [{ campaign: 'test', event }];
    const stores = catalog.relays.map((url, i) => new Map(missing.includes(i) ? [] : [[event.id, event]]));
    const writes = [];
    const closed = [];
    const log = [];
    const relayFactory = async url => {
        const index = catalog.relays.indexOf(url);
        return {
            query: async filter => [...stores[index].values()].filter(value =>
                (!filter.ids || filter.ids.includes(value.id)) && (!filter['#d'] || filter['#d'].includes(value.tags[0][1]))),
            publish: async value => {
                assert(verifyEvent(value), 'publisher sent an invalid NIP-01 signature');
                writes.push({ index, event: value });
                stores[index].clear();
                stores[index].set(value.id, value);
            },
            close: () => closed.push(index)
        };
    };
    const options = { catalog, seeds, key, now, relayFactory, readImage: async () => bytes,
        fetchImpl: async () => new Response(bytes), log: value => log.push(value) };
    return { options, stores, writes, closed, log, event };
}

test('public nostr4j campaign seeds verify with the independent NIP-01 signer', async () => {
    const seeds = JSON.parse(await readFile(new URL('../../nostrads-js/demo/seed-events.json', import.meta.url)));
    const catalog = JSON.parse(await readFile(new URL('../../nostrads-js/src/demo/catalog.json', import.meta.url)));
    assert.equal(seeds.length, catalog.campaigns.length);
    for (const { campaign, event } of seeds) {
        assert(verifyEvent(event), campaign);
        assert.equal(event.pubkey, catalog.advertiser);
        assert.equal(JSON.parse(event.content).context, `nostrads-demo:${campaign}`);
        const image = catalog.campaigns.find(value => value.id === campaign);
        const data = await readFile(new URL(`../../nostrads-js/src/demo/${image.image}`, import.meta.url));
        assert(new URL(JSON.parse(event.content).payload).pathname.includes(Buffer.from(sha256(data)).toString('hex')));
    }
});

test('healthy campaigns are unchanged on repeated builds, including --check', async () => {
    const f = fixture();
    for (let attempt = 0; attempt < 2; attempt++) {
        const report = await synchronizeDemo({ ...f.options, checkOnly: attempt === 1 });
        assert.deepEqual(report, { advertiser, published: 0, renewed: 0, imagesRestored: 0, unchanged: 1 });
    }
    assert.equal(f.writes.length, 0);
    assert.equal(f.closed.length, 4);
});

test('one wiped relay receives the original signed campaign, then subsequent builds do nothing', async () => {
    const f = fixture({ missing: [1] });
    const report = await synchronizeDemo(f.options);
    assert.equal(report.published, 1);
    assert.deepEqual(f.writes, [{ index: 1, event: f.event }]);
    assert.equal((await synchronizeDemo(f.options)).published, 0);
});

test('both wiped relays recover from Git seeds without creating new campaign IDs', async () => {
    const f = fixture({ missing: [0, 1] });
    assert.equal((await synchronizeDemo(f.options)).published, 2);
    assert(f.writes.every(write => write.event.id === f.event.id));
});

test('expired campaigns renew with the same d and encrypted delegate payload', async () => {
    const f = fixture({ age: 8 * day, remaining: -day, missing: [0, 1] });
    const report = await synchronizeDemo(f.options);
    assert.equal(report.renewed, 1);
    const event = f.writes[0].event;
    assert.equal(event.created_at, now);
    assert.notEqual(event.id, f.event.id);
    assert.deepEqual(event.tags.find(t => t[0] === 'd'), f.event.tags.find(t => t[0] === 'd'));
    assert.deepEqual(event.tags.find(t => t[0] === 'D'), f.event.tags.find(t => t[0] === 'D'));
    assert.equal(Number(event.tags.find(t => t[0] === 'expiration')[1]), now + 7 * day);
    assert.equal((await synchronizeDemo(f.options)).published, 0);
});

test('near-expiry campaigns renew while old seeds cannot overwrite the renewal', async () => {
    const f = fixture({ age: 6 * day, remaining: day });
    assert.equal((await synchronizeDemo(f.options)).renewed, 1);
    const revision = f.writes[0].event;
    f.stores[1].clear();
    assert.equal((await synchronizeDemo(f.options)).renewed, 0);
    assert.equal(f.writes.at(-1).event.id, revision.id);
});

test('old but unexpired seeds are refreshed when a wiped relay needs recovery', async () => {
    const f = fixture({ age: 3 * day, remaining: 4 * day, missing: [1] });
    assert.equal((await synchronizeDemo(f.options)).renewed, 1);
    assert.equal(f.writes[0].event.created_at, now);
});

test('a signed catalog edit replaces the campaign while preserving its identity', async () => {
    const f = fixture();
    f.options.catalog.campaigns[0].description = 'Updated demo';
    assert.equal((await synchronizeDemo(f.options)).renewed, 1);
    assert.equal(JSON.parse(f.writes[0].event.content).description, 'Updated demo');
});

test('wrong keys, corrupted seeds, and unsigned relay revisions cannot replace trusted campaigns', async () => {
    const f = fixture();
    await assert.rejects(synchronizeDemo({ ...f.options, key: decodeKey('0'.repeat(63) + '2') }), /does not match/);
    const corrupted = structuredClone(f.options.seeds);
    corrupted[0].event.content = '{}';
    await assert.rejects(synchronizeDemo({ ...f.options, seeds: corrupted }), /Invalid signed/);
    const bad = { ...f.event, id: 'f'.repeat(64), created_at: now + 1 };
    f.stores[0].set(bad.id, bad);
    assert.equal((await synchronizeDemo(f.options)).published, 0);
    assert.equal(f.writes.length, 0);
});

test('read-only checks detect missing campaigns without writing', async () => {
    const f = fixture({ missing: [1] });
    await assert.rejects(synchronizeDemo({ ...f.options, checkOnly: true }), /missing from/);
    assert.equal(f.writes.length, 0);
    assert.deepEqual(f.closed, [0, 1]);
});

test('ACK followed by absent read-back fails recovery and closes every connection', async () => {
    const f = fixture({ missing: [1] });
    const factory = f.options.relayFactory;
    f.options.relayFactory = async url => ({ ...await factory(url), publish: async () => {} });
    await assert.rejects(synchronizeDemo(f.options), /failed read-back/);
    assert.deepEqual(f.closed, [0, 1]);
});

test('missing Blossom images are restored with scoped authorization and verified byte-for-byte', async () => {
    const f = fixture();
    let restored = false;
    let uploads = 0;
    f.options.fetchImpl = async (url, options = {}) => {
        if (options.method === 'PUT') {
            uploads++;
            const token = JSON.parse(Buffer.from(options.headers.Authorization.slice(6), 'base64url').toString());
            assert(verifyEvent(token));
            assert.equal(token.kind, 24242);
            assert(token.tags.some(t => t[0] === 'x' && t[1] === digest));
            assert(token.tags.some(t => t[0] === 'server' && t[1] === 'images.example'));
            assert.equal(options.headers['X-SHA-256'], digest);
            assert.deepEqual(options.body, bytes);
            restored = true;
            return Response.json({ url: imageUrl, sha256: digest, size: bytes.length });
        }
        return restored ? new Response(bytes) : new Response(null, { status: 404 });
    };
    assert.equal((await synchronizeDemo(f.options)).imagesRestored, 1);
    assert.equal(uploads, 1);
    assert.equal(f.writes.length, 0, 'same image URL should not reset campaigns');
    assert.equal((await synchronizeDemo(f.options)).imagesRestored, 0);
});

test('incorrect upload descriptors and untrusted URLs are rejected before image retrieval', async () => {
    const f = fixture();
    for (const invalid of [
        { url: imageUrl, sha256: '0'.repeat(64), size: bytes.length },
        { url: imageUrl, sha256: digest, size: bytes.length + 1 },
        { url: `https://other.example/${digest}.png`, sha256: digest, size: bytes.length }
    ]) {
        const requests = [];
        const fetchImpl = async (url, options = {}) => {
            requests.push(String(url));
            return options.method === 'PUT' ? Response.json(invalid) : new Response(null, { status: 404 });
        };
        await assert.rejects(ensureImage({ ...f.options, event: f.event, bytes, fetchImpl }));
        assert.equal(requests.length, 2);
        assert(!requests.some(url => url.includes('other.example')));
    }
});

test('keys accept hex and nsec without echoing malformed input', () => {
    const nsec = bech32.encode('nsec', bech32.toWords(key), 100);
    assert.deepEqual(decodeKey(nsec), key);
    const invalid = 'private-input-must-not-be-logged';
    assert.throws(() => decodeKey(invalid), error => !error.message.includes(invalid));
    assert.throws(() => decodeKey('0'.repeat(64)), /valid/);
});

test('CLI emits only a public identity when unconfigured and redacts invalid secret failures', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'nostrads-demo-test-'));
    try {
        const output = join(dir, 'output');
        const env = { ...process.env, NOSTRADS_DEMO_PRIVATE_KEY: '', GITHUB_OUTPUT: output };
        const missing = spawnSync(process.execPath, [new URL('sync-demo.mjs', import.meta.url).pathname], { env, encoding: 'utf8' });
        assert.equal(missing.status, 0, missing.stderr);
        assert.match(await readFile(output, 'utf8'), /^advertiser=[0-9a-f]{64}\n$/);
        const secret = 'an-invalid-secret-that-must-stay-private';
        const invalid = spawnSync(process.execPath, [new URL('sync-demo.mjs', import.meta.url).pathname], {
            env: { ...env, NOSTRADS_DEMO_PRIVATE_KEY: secret }, encoding: 'utf8'
        });
        assert.equal(invalid.status, 1);
        assert(!`${invalid.stdout}${invalid.stderr}`.includes(secret));
    } finally {
        await rm(dir, { recursive: true, force: true });
    }
});

class TestSocket extends EventTarget {
    readyState = 0;
    constructor() {
        super();
        queueMicrotask(() => { this.readyState = 1; this.dispatchEvent(new Event('open')); });
    }
    send(text) {
        const [type, id] = JSON.parse(text);
        if (type === 'REQ') {
            this.reply(['EVENT', id, fixture().event]);
            this.reply(['EOSE', id]);
        }
        if (type === 'EVENT') this.reply(['OK', id.id, true, '']);
    }
    reply(message) {
        queueMicrotask(() => this.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(message) })));
    }
    close() { this.readyState = 3; this.dispatchEvent(new Event('close')); }
}

test('WebSocket transport requires matching OK and EOSE messages', async () => {
    const relay = await connectRelay('wss://fixture.example', { WebSocketImpl: TestSocket });
    assert.equal((await relay.query({ kinds: [30100] })).length, 1);
    await relay.publish(fixture().event);
    relay.close();
});

test('WebSocket transport rejects negative ACKs, closed queries and missing ACKs', async () => {
    for (const mode of ['negative', 'closed', 'timeout', 'disconnect']) {
        class RefusingSocket extends TestSocket {
            send(text) {
                const [type, id] = JSON.parse(text);
                if (type === 'CLOSE') return;
                if (mode === 'negative') this.reply(['OK', id.id, false, 'private server message']);
                if (mode === 'closed') this.reply(['CLOSED', id, 'private server message']);
                if (mode === 'disconnect') this.close();
            }
        }
        const relay = await connectRelay('wss://fixture.example', { WebSocketImpl: RefusingSocket, timeoutMs: 50 });
        await assert.rejects(mode === 'closed' || mode === 'disconnect'
            ? relay.query({}) : relay.publish(fixture().event), error => !error.message.includes('private server message'));
        relay.close();
    }
});
