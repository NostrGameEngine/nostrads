import assert from 'node:assert/strict';
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { secp256k1, schnorr } from '../../nostrads-bindings4js/node_modules/@noble/curves/esm/secp256k1.js';
import { chacha20 } from '../../nostrads-bindings4js/node_modules/@noble/ciphers/esm/chacha.js';
import { signEvent, verifyEvent } from '../../nostrads-bindings4js/scripts/sync-demo.mjs';
import { browserRuntime, moduleRoot } from './browser-runtime.mjs';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import jsQR from 'jsqr';

// An independent NIP-44/NIP-46 peer verifies the compiled nostr4j implementation.
// These are public fixture identities, never funded or used outside this test.
const key = (n) => Buffer.from(n.toString(16).padStart(64, '0'), 'hex');
const userKey = key(1),
    remoteKey = key(2);
const hex = (value) => Buffer.from(value).toString('hex');
const userPubkey = hex(schnorr.getPublicKey(userKey));
const remotePubkey = hex(schnorr.getPublicKey(remoteKey));
const mac = (key, ...bytes) => createHmac('sha256', key).update(Buffer.concat(bytes)).digest();
function messageKeys(privateKey, publicKey, nonce) {
    const shared = secp256k1.getSharedSecret(privateKey, '02' + publicKey).slice(1);
    const conversation = mac(Buffer.from('nip44-v2'), Buffer.from(shared));
    let previous = Buffer.alloc(0);
    const blocks = [];
    for (let index = 1; index <= 3; index++) {
        previous = mac(conversation, previous, nonce, Buffer.from([index]));
        blocks.push(previous);
    }
    const expanded = Buffer.concat(blocks);
    return [expanded.subarray(0, 32), expanded.subarray(32, 44), expanded.subarray(44, 76)];
}
function encrypt(text, privateKey, publicKey) {
    const bytes = Buffer.from(text),
        nonce = randomBytes(32);
    const power = 2 ** Math.ceil(Math.log2(bytes.length)),
        chunk = power <= 256 ? 32 : power / 8;
    const padded = Buffer.alloc(2 + (bytes.length <= 32 ? 32 : chunk * Math.ceil(bytes.length / chunk)));
    padded.writeUInt16BE(bytes.length);
    bytes.copy(padded, 2);
    const [streamKey, streamNonce, hmacKey] = messageKeys(privateKey, publicKey, nonce);
    const ciphertext = Buffer.from(chacha20(streamKey, streamNonce, padded));
    return Buffer.concat([Buffer.from([2]), nonce, ciphertext, mac(hmacKey, nonce, ciphertext)]).toString('base64');
}
function decrypt(payload, privateKey, publicKey) {
    const bytes = Buffer.from(payload, 'base64');
    assert.equal(bytes[0], 2);
    const nonce = bytes.subarray(1, 33),
        ciphertext = bytes.subarray(33, -32);
    const [streamKey, streamNonce, hmacKey] = messageKeys(privateKey, publicKey, nonce);
    assert(timingSafeEqual(bytes.subarray(-32), mac(hmacKey, nonce, ciphertext)));
    const padded = Buffer.from(chacha20(streamKey, streamNonce, ciphertext));
    return padded.subarray(2, 2 + padded.readUInt16BE()).toString();
}
const runtime = await browserRuntime();
const subscriptions = new Map();
const methods = [];
const published = [];
const approvedTransports = new Set();
let releaseConnect;
let requireRestoreApproval = true;
function reply(transport, payload) {
    const event = signEvent(
        {
            kind: 24133,
            created_at: Math.floor(Date.now() / 1000),
            tags: [['p', transport]],
            content: encrypt(JSON.stringify(payload), remoteKey, transport),
        },
        remoteKey
    );
    for (const [socket, filters] of subscriptions)
        for (const [id, filter] of filters) {
            if (filter.kinds?.includes(24133) && filter['#p']?.includes(transport))
                socket.send(JSON.stringify(['EVENT', id, event]));
        }
}
try {
    const page = await runtime.browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.routeWebSocket(/.*/, (socket) => {
        const filters = new Map();
        subscriptions.set(socket, filters);
        socket.onClose(() => subscriptions.delete(socket));
        socket.onMessage((message) => {
            const [type, value, ...rest] = JSON.parse(String(message));
            if (type === 'REQ') {
                filters.set(value, rest[0]);
                socket.send(JSON.stringify(['EOSE', value]));
            }
            if (type === 'CLOSE') filters.delete(value);
            if (type !== 'EVENT') return;
            const event = value;
            assert(verifyEvent(event), 'client sent an invalid signature');
            socket.send(JSON.stringify(['OK', event.id, true, '']));
            if (event.kind !== 24133) {
                published.push(event);
                return;
            }
            const request = JSON.parse(decrypt(event.content, remoteKey, event.pubkey));
            methods.push(request.method);
            let result;
            if (request.method === 'connect') {
                assert.equal(request.params[0], remotePubkey);
                if (approvedTransports.has(event.pubkey)) {
                    assert(!request.params[1], 'single-use connection secret was replayed');
                    if (requireRestoreApproval) {
                        requireRestoreApproval = false;
                        reply(event.pubkey, { id: request.id, result: 'auth_url', error: 'https://signer.example/approve' });
                        releaseConnect = () => reply(event.pubkey, { id: request.id, result: 'ack' });
                        return;
                    }
                    reply(event.pubkey, { id: request.id, result: 'ack' });
                    return;
                }
                assert.equal(request.params[1], 'fixture-secret');
                reply(event.pubkey, { id: request.id, result: 'auth_url', error: 'https://signer.example/approve' });
                releaseConnect = () => {
                    approvedTransports.add(event.pubkey);
                    reply(event.pubkey, { id: request.id, result: 'ack' });
                };
                return;
            }
            if (request.method === 'get_public_key') result = userPubkey;
            else if (request.method === 'sign_event')
                result = JSON.stringify(signEvent(JSON.parse(request.params[0]), userKey));
            else if (request.method === 'nip44_encrypt')
                result = encrypt(request.params[1], userKey, request.params[0]);
            else if (request.method === 'nip44_decrypt')
                result = decrypt(request.params[1], userKey, request.params[0]);
            else throw new Error('Unexpected RPC: ' + request.method);
            reply(event.pubkey, { id: request.id, result });
        });
    });
    await page.goto(runtime.base + '/src/console/');
    await page.locator('#login').click();
    await page.locator('[data-method="bunker"]').click();
    await page
        .locator('#bunker-url')
        .fill(`bunker://${remotePubkey}?relay=wss%3A%2F%2Fsigner.example&secret=fixture-secret`);
    await page.locator('#auth-submit').click();
    await page.locator('#auth-approval').waitFor({ state: 'visible', timeout: 15000 });
    assert.equal(await page.locator('#auth-approval').getAttribute('href'), 'https://signer.example/approve');
    releaseConnect();
    await page.waitForFunction(() => !document.querySelector('#logout').hidden);
    assert.equal(
        await page.evaluate(async () => (await import('/src/console/components/session.js')).getSession().pubkey),
        userPubkey,
        'transport pubkey used as user identity'
    );
    await page.evaluate(async (delegate) => {
        const { createClient } = await import('/src/console/components/session.js');
        const client = createClient(['wss://ads.example'], []);
        try {
            await client.publish({
                description: 'Remote signer fixture',
                mimeType: 'text/plain',
                payload: 'A test creative',
                size: '480x60',
                link: 'https://example.com',
                actionType: 'link',
                context: null,
                callToAction: 'Open',
                category: [],
                languages: [],
                offerersWhitelist: [],
                appsWhitelist: [],
                delegate,
                nwc: 'test-wallet-must-remain-encrypted',
                dailyBudget: 10000,
                bid: 1000,
                holdTime: 60,
                expire_at: Date.now() + 300000,
                maxPayouts: 3,
                payoutResetInterval: 300,
            });
        } finally {
            client.close();
        }
    }, hex(schnorr.getPublicKey(key(3))));
    assert(methods.includes('nip44_encrypt'));
    assert(methods.includes('sign_event'));
    assert.equal(published.length, 1);
    assert.equal(published[0].pubkey, userPubkey);
    assert(!JSON.stringify(published).includes('test-wallet-must-remain-encrypted'));
    const rememberedBunker = await page.evaluate(() => JSON.parse(localStorage.getItem('nostrads:account:v1')));
    const bunkerState = JSON.parse(rememberedBunker.state);
    assert.equal(new URL(bunkerState.bunker).hostname, remotePubkey);
    assert(!bunkerState.bunker.includes('secret='), 'single-use bunker secret was retained');
    assert.notEqual(bunkerState.key, hex(userKey), 'remote login stored the user private key');
    await page.reload();
    await page.locator('#auth-approval').waitFor({ state: 'visible', timeout: 15000 });
    releaseConnect();
    await page.waitForFunction(() => !document.querySelector('#logout').hidden);
    await page.locator('#auth-dialog').waitFor({ state: 'hidden' });
    assert.equal(
        await page.evaluate(async () => (await import('/src/console/components/session.js')).getSession().pubkey),
        userPubkey,
        'bunker login did not survive reload'
    );
    assert.equal(
        JSON.parse(JSON.parse(await page.evaluate(() => localStorage.getItem('nostrads:account:v1'))).state).key,
        bunkerState.key,
        'reconnection changed the authorized transport key'
    );
    await page.locator('#logout').click();
    await page.locator('#login').click();
    await page.locator('[data-method="bunker"]').click();
    await page.locator('#generate-connect').click();
    await page.waitForFunction(() => document.querySelector('#connect-link').value.startsWith('nostrconnect://'));
    const uri = new URL(await page.locator('#connect-link').inputValue());
    async function decodeQr(selector) {
        const pixels = await page
            .locator(selector)
            .evaluate((canvas) => ({
                width: canvas.width,
                height: canvas.height,
                data: Array.from(canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data),
            }));
        return jsQR(new Uint8ClampedArray(pixels.data), pixels.width, pixels.height, {
            inversionAttempts: 'dontInvert',
        })?.data;
    }
    assert.equal(await decodeQr('#connect-qr'), uri.href, 'QR code does not contain the complete connection link');
    const qa = resolve(moduleRoot, 'build/qa');
    await mkdir(qa, { recursive: true });
    await page.screenshot({ path: resolve(qa, 'console-connect-qr.png') });
    await page.locator('#expand-connect-qr').click();
    assert(await page.locator('#connect-qr-dialog').isVisible());
    assert.equal(await decodeQr('#connect-qr-expanded'), uri.href);
    for (const width of [1440, 390]) {
        await page.setViewportSize({ width, height: 1000 });
        const bounds = await page.locator('#connect-qr-expanded').boundingBox();
        assert(bounds.width <= width && bounds.x >= 0, 'expanded QR overflows the viewport');
    }
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.screenshot({ path: resolve(qa, 'console-connect-qr-expanded.png') });
    await page.locator('#close-connect-qr').click();
    assert(await page.locator('#auth-dialog').isVisible(), 'closing the QR cancelled the login');

    reply(uri.hostname, { id: 'connect', result: 'wrong-secret' });
    await new Promise((resolve) => setTimeout(resolve, 100));
    assert(await page.locator('#auth-dialog').isVisible(), 'wrong nostrconnect secret was accepted');
    assert.equal(await page.evaluate(() => localStorage.getItem('nostrads:account:v1')), null);
    approvedTransports.add(uri.hostname);
    reply(uri.hostname, { id: 'connect', result: uri.searchParams.get('secret') });
    await page.waitForFunction(() => !document.querySelector('#logout').hidden);
    assert.equal(
        await page.evaluate(async () => (await import('/src/console/components/session.js')).getSession().pubkey),
        userPubkey
    );
    const rememberedConnect = JSON.parse(await page.evaluate(() => localStorage.getItem('nostrads:account:v1')));
    assert.equal(new URL(JSON.parse(rememberedConnect.state).bunker).hostname, remotePubkey);
    assert(!rememberedConnect.state.includes(uri.searchParams.get('secret')), 'nostrconnect secret was retained');
    await page.reload();
    await page.waitForFunction(() => !document.querySelector('#logout').hidden);
    assert.equal(
        await page.evaluate(async () => (await import('/src/console/components/session.js')).getSession().pubkey),
        userPubkey,
        'nostrconnect login did not survive reload'
    );
    await page.locator('#logout').click();
    await page.locator('#login').click();
    await page.locator('[data-method="bunker"]').click();
    await page.locator('#generate-connect').click();
    await page.waitForFunction(() => document.querySelector('#connect-link').value.startsWith('nostrconnect://'));
    const cancelled = new URL(await page.locator('#connect-link').inputValue());
    await page.locator('#auth-close').click();
    assert.equal(
        await page.locator('#connect-qr').evaluate((canvas) => canvas.width),
        1,
        'cancelled connection QR was retained'
    );
    reply(cancelled.hostname, { id: 'connect', result: cancelled.searchParams.get('secret') });
    await new Promise((resolve) => setTimeout(resolve, 150));
    assert.equal(
        await page.evaluate(async () => (await import('/src/console/components/session.js')).getSession()),
        null,
        'cancelled login completed later'
    );
    assert.deepEqual(errors, []);
    console.log(
        'NIP-46: persistent bunker and nostrconnect sessions, separate signer/user identities, encrypted publication, QR, secret validation and cancellation passed.'
    );
} finally {
    await runtime.close();
}
