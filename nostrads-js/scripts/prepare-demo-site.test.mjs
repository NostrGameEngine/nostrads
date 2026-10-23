import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { prepareDemoSite } from './prepare-demo-site.mjs';

const commit = 'a'.repeat(40);
const publicFakeWallet = 'nostr+walletconnect://fake-demo-wallet?relay=wss%3A%2F%2Ftest.example';

async function fixture(run) {
    const root = await mkdtemp(join(tmpdir(), 'nostrads-site-'));
    try {
        await mkdir(join(root, 'console/edit'), {recursive: true});
        await writeFile(join(root, 'console/config.json'), JSON.stringify({
            testmode: false, default_relays: ['wss://current.example'],
            testmode_config: {default_relays: ['wss://demo.example']}
        }));
        for (const page of ['index.html', 'console/index.html', 'console/edit/index.html', 'console/debug.html']) {
            await writeFile(join(root, page), '<link rel="stylesheet" href="./style.css">' +
                '<script src="./edit.js"></script><script src="https://example.com/external.js"></script>');
        }
        await run(root);
    } finally { await rm(root, {recursive: true, force: true}); }
}

test('demo deployment uses the tested website settings and retains only the existing public fake wallet', async () => {
    await fixture(async root => {
        const previous = {testmode: true, default_relays: ['wss://obsolete.example'],
            testmode_config: {nwc: publicFakeWallet, arbitrary: 'must not be copied'}};
        await prepareDemoSite(root, previous, commit);
        const config = JSON.parse(await readFile(join(root, 'console/config.json')));
        assert.deepEqual(config, {testmode: true, default_relays: ['wss://current.example'],
            testmode_config: {default_relays: ['wss://demo.example'], nwc: publicFakeWallet}});
        assert.deepEqual(JSON.parse(await readFile(join(root, 'deployment.json'))), {sourceCommit: commit, mode: 'demo'});
    });
});

test('HTML entrypoints and Choices assets receive a build version; external scripts stay unchanged', async () => {
    await fixture(async root => {
        await prepareDemoSite(root, {}, commit);
        const first = await readFile(join(root, 'console/edit/index.html'), 'utf8');
        assert(first.includes(`src="./edit.js?v=${commit}"`));
        assert(first.includes(`href="./style.css?v=${commit}"`));
        assert(first.includes('src="https://example.com/external.js"'));
        assert(!JSON.parse(await readFile(join(root, 'console/config.json'))).testmode_config.nwc);
        assert.match(await readFile(join(root, '_headers'), 'utf8'), /Cache-Control: no-cache/);
        await prepareDemoSite(root, {}, commit);
        assert.equal(await readFile(join(root, 'console/edit/index.html'), 'utf8'), first);
        const nextCommit = 'b'.repeat(40);
        await prepareDemoSite(root, {}, nextCommit);
        assert((await readFile(join(root, 'console/edit/index.html'), 'utf8')).includes(`src="./edit.js?v=${nextCommit}"`));
    });
});

test('production wallet fields are never carried into a demo and invalid build IDs fail before writing', async () => {
    await fixture(async root => {
        const previous = {testmode: false, testmode_config: {nwc: 'private-wallet'}};
        await assert.rejects(prepareDemoSite(root, previous, 'invalid'), /source commit/);
        assert.equal(JSON.parse(await readFile(join(root, 'console/config.json'))).testmode, false);
        await prepareDemoSite(root, previous, commit);
        assert(!JSON.parse(await readFile(join(root, 'console/config.json'))).testmode_config.nwc);
    });
});
