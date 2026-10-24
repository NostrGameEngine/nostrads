import assert from 'node:assert/strict';
import { mkdir, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { bech32 } from '../../nostrads-bindings4js/node_modules/@scure/base/lib/esm/index.js';
import { schnorr } from '../../nostrads-bindings4js/node_modules/@noble/curves/esm/secp256k1.js';
import { signEvent } from '../../nostrads-bindings4js/scripts/sync-demo.mjs';
import { browserRuntime, moduleRoot } from './browser-runtime.mjs';

const runtime = await browserRuntime();
const key = Uint8Array.from({ length: 32 }, (_, index) => (index === 31 ? 1 : 0));
const pubkey = Buffer.from(schnorr.getPublicKey(key)).toString('hex');
const nsec = bech32.encode('nsec', bech32.toWords(key));
// Public NIP-49 interoperability vector, from the Nostr specification.
const encrypted =
    'ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p';
const unlockedPubkey = Buffer.from(
    schnorr.getPublicKey(Buffer.from('3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683', 'hex'))
).toString('hex');
const now = Math.floor(Date.now() / 1000);
const fixture = signEvent(
    {
        kind: 30100,
        created_at: now,
        tags: [
            ['d', 'console-test'],
            ['m', 'text/plain'],
            ['k', 'link'],
            ['s', '480x60'],
            ['expiration', String(now + 86400)],
        ],
        content: JSON.stringify({
            description: 'Console campaign fixture',
            payload: 'Independent test creative',
            link: 'https://example.com',
            bid: 1000,
            call_to_action: 'Open',
        }),
    },
    key
);
const imageUrl = 'https://blossom.band/' + 'a'.repeat(64) + '.png';
const imageFixture = signEvent(
    {
        kind: 30100,
        created_at: now,
        tags: [
            ['d', 'console-image-test'],
            ['m', 'image/png'],
            ['k', 'link'],
            ['s', '256x256'],
            ['expiration', String(now + 86400)],
        ],
        content: JSON.stringify({
            description: 'Image campaign fixture',
            payload: imageUrl,
            link: 'https://example.com',
            bid: 2000,
            call_to_action: 'Open',
        }),
    },
    key
);
const errors = [];
try {
    const context = await runtime.browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    page.on('pageerror', (error) => errors.push(error.message));
    await context.route(imageUrl, async (route) =>
        route.fulfill({ contentType: 'image/png', body: await readFile(resolve(moduleRoot, 'src/imgs/mark.png')) })
    );
    await context.routeWebSocket(/.*/, (socket) =>
        socket.onMessage((message) => {
            const [type, id, filter] = JSON.parse(String(message));
            if (type === 'REQ') {
                if (filter.kinds?.includes(30100) && filter.authors?.includes(pubkey)) {
                    socket.send(JSON.stringify(['EVENT', id, fixture]));
                    socket.send(JSON.stringify(['EVENT', id, imageFixture]));
                }
                socket.send(JSON.stringify(['EOSE', id]));
            } else if (type === 'EVENT') socket.send(JSON.stringify(['OK', id.id, true, '']));
        })
    );
    await page.goto(runtime.base + '/src/console/');
    await page.locator('#login').click();
    await page.locator('#auth-submit').click();
    assert.match(await page.locator('#auth-status').textContent(), /No NIP-07/);
    await page.locator('[data-method="nsec"]').click();
    await page.locator('#nsec-key').fill(nsec);
    await page.locator('#auth-submit').click();
    await page.waitForFunction(() => document.querySelector('#logout').hidden === false);
    assert.equal(
        await page.evaluate(async () => (await import('/src/console/components/session.js')).getSession().pubkey),
        pubkey
    );
    await page.locator('.campaign-row').first().waitFor({ timeout: 40000 });
    assert.equal(await page.locator('#published-count').textContent(), '2');
    await page.waitForFunction(() => document.querySelector('.campaign-thumb img')?.naturalWidth > 0);
    assert.equal(
        await page.locator('.campaign-thumb .nostr-ddspace-call-to-action').count(),
        0,
        'campaign thumbnails rendered an ad action overlay'
    );

    await page.getByRole('button', { name: 'Open Image campaign fixture', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('#adForm .choices').length === 2);
    assert.equal(
        await page.locator('#payloadImage').evaluate((input) => input.files.length),
        0,
        'existing image was unnecessarily downloaded into a file input'
    );
    await page.locator('#open-preview').click();
    await page.waitForFunction(() =>
        document.querySelector('#preview').style.backgroundImage.includes('https://blossom.band/')
    );
    assert((await page.locator('.creative-preview-dialog').boundingBox()).width >= 800, 'preview dialog is too small');
    await page.getByRole('button', { name: 'Close ad preview', exact: true }).click();
    await page.locator('#campaigns-nav').click();
    await page.waitForFunction(() => document.querySelectorAll('.campaign-row').length === 2);
    await page.locator('#campaign-search').fill('no-such-campaign');
    assert.equal(await page.locator('.campaign-row').count(), 0);
    await page.locator('#campaign-search').fill('');
    await page.locator('#format-filter').selectOption('image');
    assert.equal(await page.locator('.campaign-row').count(), 1);
    await page.locator('#format-filter').selectOption('all');
    const qaDir = resolve(moduleRoot, 'build/qa');
    await mkdir(qaDir, { recursive: true });
    for (const width of [1440, 768, 390, 320]) {
        await page.setViewportSize({ width, height: 1000 });
        assert(
            await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
            `dashboard overflow at ${width}`
        );
        await page.screenshot({ path: resolve(qaDir, `console-dashboard-${width}.png`), fullPage: true });
    }
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.locator('#createNew').click();
    await page.waitForFunction(() => document.querySelectorAll('#adForm .choices').length === 2);
    assert.equal(
        await page.evaluate(async () => (await import('/src/console/components/session.js')).getSession().pubkey),
        pubkey,
        'navigation lost the signer'
    );
    await page.locator('input[name="nwc"]').fill('nostr+walletconnect://must-be-cleared');
    const storage = await page.evaluate(() =>
        JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } })
    );
    assert(!storage.includes('must-be-cleared'), 'wallet connection entered browser storage');
    assert.equal(
        JSON.parse(JSON.parse(await page.evaluate(() => localStorage.getItem('nostrads:account:v1'))).state).key,
        Buffer.from(key).toString('hex'),
        'the signing key was not remembered'
    );
    await page.reload();
    await page.waitForFunction(() => !document.querySelector('#logout').hidden);
    assert.equal(
        await page.evaluate(async () => (await import('/src/console/components/session.js')).getSession().pubkey),
        pubkey,
        'reload did not restore the account'
    );
    const auth = await page.evaluate(async () => {
        const session = await import('/src/console/components/session.js');
        window.staleClient = session.createClient([], []);
        return session.getSession().auth;
    });
    await page.locator('#logout').click();
    assert.equal(await page.evaluate(() => localStorage.getItem('nostrads:account:v1')), null);
    await page.waitForFunction(() => !document.querySelector('#adForm'));
    assert.equal(
        await page.evaluate(async () => (await import('/src/console/components/session.js')).getSession()),
        null
    );
    const rejected = await page.evaluate(async (auth) => {
        const { default: ads } = await import('/src/console/nostr-ads.js');
        const client = ads.newAdvertiserClient([], auth, []);
        try {
            await client.getPublicKey();
            return false;
        } catch (_) {
            return true;
        } finally {
            client.close();
        }
    }, auth);
    assert(rejected, 'ended session still created an authenticated client');
    for (const [value, expectedPubkey] of [
        [Buffer.from(key).toString('hex'), pubkey],
        ['  3501454135014541350145413501453FEFB02227E449E57CF4D3A3CE05378683  ', unlockedPubkey],
    ]) {
        await page.locator('#login').click();
        await page.locator('[data-method="nsec"]').click();
        await page.locator('#nsec-key').fill(value);
        await page.locator('#auth-submit').click();
        await page.waitForFunction(() => !document.querySelector('#logout').hidden);
        assert.equal(
            await page.evaluate(async () => (await import('/src/console/components/session.js')).getSession().pubkey),
            expectedPubkey,
            'hex login derived a different identity'
        );
        const stored = await page.evaluate(() =>
            JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } })
        );
        assert(stored.includes('nostrads:account:v1'), 'hex login was not remembered');
        await page.locator('#logout').click();
    }
    await page.locator('#login').click();
    await page.locator('[data-method="nsec"]').click();
    for (const invalid of ['0'.repeat(64), 'f'.repeat(64), '1'.repeat(63), '1'.repeat(65), 'g'.repeat(64)]) {
        await page.locator('#nsec-key').fill(invalid);
        await page.locator('#auth-submit').click();
        await page.waitForFunction(() =>
            /Could not log in|Enter a valid/.test(document.querySelector('#auth-status').textContent)
        );
        assert.equal(
            await page.evaluate(async () => (await import('/src/console/components/session.js')).getSession()),
            null
        );
    }
    await page.locator('#auth-close').click();
    await page.locator('#login').click();
    await page.locator('[data-method="ncryptsec"]').click();
    await page.locator('#encrypted-key').fill(encrypted);
    await page.locator('#key-password').fill('wrong');
    await page.locator('#auth-submit').click();
    await page.waitForFunction(
        () => document.querySelector('#auth-status').textContent.includes('Could not unlock'),
        null,
        { timeout: 45000 }
    );
    assert.equal(await page.locator('#key-password').inputValue(), '');
    await page.locator('#encrypted-key').fill(encrypted);
    await page.locator('#key-password').fill('nostr');
    await page.locator('#auth-submit').click();
    await page.waitForFunction(() => document.querySelector('#logout').hidden === false, null, { timeout: 45000 });
    assert.equal(
        await page.evaluate(async () => (await import('/src/console/components/session.js')).getSession().pubkey),
        unlockedPubkey,
        'NIP-49 vector mismatch'
    );
    await page.reload();
    await page.waitForFunction(() => !document.querySelector('#logout').hidden);
    assert.equal(
        await page.evaluate(async () => (await import('/src/console/components/session.js')).getSession().pubkey),
        unlockedPubkey,
        'encrypted-key login was not restored'
    );
    const remembered = JSON.parse(await page.evaluate(() => localStorage.getItem('nostrads:account:v1')));
    assert(!('password' in remembered) && !('password' in JSON.parse(remembered.state)), 'password was saved');
    const secondTab = await page.context().newPage();
    await secondTab.goto(runtime.base + '/src/console/');
    await secondTab.waitForFunction(() => !document.querySelector('#logout').hidden);
    await page.locator('#logout').click();
    await secondTab.waitForFunction(() => document.querySelector('#logout').hidden);
    assert.equal(
        await secondTab.evaluate(async () => (await import('/src/console/components/session.js')).getSession()),
        null,
        'logout left another tab authenticated'
    );
    await secondTab.close();
    await page.reload();
    await page.locator('#login:not([disabled])').waitFor();
    assert(await page.locator('#logout').isHidden(), 'logged out account returned after reload');
    await page.evaluate(() => localStorage.setItem('nostrads:account:v1', '{broken'));
    await page.reload();
    await page.waitForFunction(() => localStorage.getItem('nostrads:account:v1') === null);
    await page.locator('#createNew').click();
    await page.waitForFunction(() => document.querySelectorAll('#adForm .choices').length === 2);
    await page.locator('textarea[name="description"]').fill('Prepared before login');
    await page.addInitScript((pubkey) => {
        window.nostr = { getPublicKey: async () => pubkey };
    }, pubkey);
    await page.evaluate((pubkey) => { window.nostr = { getPublicKey: async () => pubkey }; }, pubkey);
    await page.locator('#login').click();
    await page.locator('#auth-submit').click();
    await page.waitForFunction(() => !document.querySelector('#logout').hidden);
    assert.equal(
        await page.locator('textarea[name="description"]').inputValue(),
        'Prepared before login',
        'logging in discarded the prepared creative'
    );
    assert.equal(
        await page.evaluate(async () => (await import('/src/console/components/session.js')).getSession().method),
        'extension'
    );
    await page.reload();
    await page.waitForFunction(() => !document.querySelector('#logout').hidden);
    assert.equal(
        await page.evaluate(async () => (await import('/src/console/components/session.js')).getSession().pubkey),
        pubkey,
        'browser extension account was not restored'
    );
    const savedExtension = await page.evaluate(() => JSON.parse(localStorage.getItem('nostrads:account:v1')));
    assert.deepEqual(JSON.parse(savedExtension.state), { method: 'extension' }, 'extension stored signing material');
    await page.addInitScript((other) => { window.nostr = { getPublicKey: async () => other }; }, unlockedPubkey);
    await page.reload();
    await page.waitForFunction(() => localStorage.getItem('nostrads:account:v1') === null);
    assert(await page.locator('#logout').isHidden(), 'restoration silently switched extension accounts');
    await page.evaluate(async (saved) => {
        window.nostr = { getPublicKey: () => new Promise((resolve) => { window.finishRestore = () => resolve(saved.pubkey); }) };
        localStorage.setItem('nostrads:account:v1', JSON.stringify(saved));
        window.restoring = (await import('/src/console/components/session.js')).restoreSession();
    }, savedExtension);
    await page.waitForFunction(() => typeof window.finishRestore === 'function');
    await page.evaluate(async () => {
        // Another tab can remove the account before this tab receives its storage event.
        localStorage.removeItem('nostrads:account:v1');
        window.finishRestore();
        await window.restoring;
    });
    assert.equal(
        await page.evaluate(async () => (await import('/src/console/components/session.js')).getSession()),
        null,
        'an in-flight restore resurrected a removed account'
    );
    assert.equal(await page.evaluate(() => localStorage.getItem('nostrads:account:v1')), null);
    assert.deepEqual(errors, []);
    console.log(
        'Console: key login, NIP-49 interoperability, campaign filtering, persistent accounts, cross-tab logout and responsive layout passed.'
    );
} finally {
    await runtime.close();
}
