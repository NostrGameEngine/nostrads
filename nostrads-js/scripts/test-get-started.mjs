import assert from 'node:assert/strict';
import {signEvent} from '../../nostrads-bindings4js/scripts/sync-demo.mjs';
import {bech32} from '../../nostrads-bindings4js/node_modules/@scure/base/lib/esm/index.js';
import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';

export async function testGetStarted(runtime, page) {
    const demo = JSON.parse(await readFile(new URL('../src/demo/network.json', import.meta.url), 'utf8'));
    for (const [tab, panel] of [['games', 'games'], ['advertiser', 'advertiser'], ['delegate', 'delegate'], ['website', 'website']]) {
        await page.locator(`#${tab}-tab`).click();
        assert(await page.locator(`#${panel}-panel`).isVisible());
        assert.equal(await page.locator('[role="tabpanel"]:visible').count(), 1);
    }
    await page.locator('#website-tab').press('ArrowRight');
    assert(await page.locator('#games-panel').isVisible());
    await page.locator('#games-tab').press('Home');
    assert(await page.locator('#website-panel').isVisible());
    const key = Buffer.from('0'.repeat(63) + '1', 'hex');
    const profile = signEvent({kind: 0, created_at: Math.floor(Date.now() / 1000), tags: [], content: '{}'}, key);
    const npub = bech32.encode('npub', bech32.toWords(Buffer.from(profile.pubkey, 'hex')));
    let metadata = {};
    let hold = false;
    let profileQueries = 0;
    const published = [];
    const heldReplies = [];
    await page.routeWebSocket(/.*/, socket => {
        let closed = false;
        socket.onClose(() => { closed = true; });
        socket.onMessage(message => {
            const [type, id, filter] = JSON.parse(String(message));
            if (type === 'EVENT') published.push(id);
            if (type !== 'REQ') return;
            if (filter.kinds?.includes(0) && filter.authors?.includes(profile.pubkey)) {
                profileQueries++;
                const event = signEvent({...profile, content: JSON.stringify(metadata)}, key);
                const reply = () => {
                    if (closed) return;
                    socket.send(JSON.stringify(['EVENT', id, event]));
                    socket.send(JSON.stringify(['EOSE', id]));
                };
                if (hold) heldReplies.push(reply);
                else reply();
            } else socket.send(JSON.stringify(['EOSE', id]));
        });
    });
    const input = page.locator('#website-appkey');
    const generate = page.locator('#generate-code');
    const result = page.locator('#website-code-result');
    const status = page.locator('#setup-status');
    const waitForResult = () => page.waitForFunction(() => document.querySelector('#setup-status').dataset.state);
    await input.fill('nsec1privatekey');
    await generate.click();
    assert.match(await status.textContent(), /public npub/);
    assert.equal(profileQueries, 0, 'a private key triggered a profile lookup');
    assert.equal(await result.isVisible(), false);

    await input.fill(npub);
    await generate.click();
    await waitForResult();
    assert.match(await status.textContent(), /Add one to its Nostr profile/);
    assert.equal(await result.isVisible(), false, 'code was generated without a payment address');

    metadata = {name: 'Website fixture', lud16: 'payouts@example.com'};
    await generate.click();
    await waitForResult();
    assert.match(await status.textContent(), /payouts@example.com/);
    assert(await result.isVisible());
    assert.match(await page.locator('#website-code').textContent(), new RegExp(npub));
    assert(!((await page.locator('#website-code').textContent()).includes('advertisersWhitelist')));
    assert(!((await page.locator('#website-code').textContent()).includes('data-network="demo"')));
    const manifest = JSON.parse(await readFile(new URL('../src/embed-integrity.json', import.meta.url), 'utf8'));
    const loader = await readFile(new URL('../src/' + manifest.file, import.meta.url));
    assert.equal(manifest.integrity, 'sha384-' + createHash('sha384').update(loader).digest('base64'));
    const previewPage = page.frameLocator('#website-preview > iframe');
    const previewAd = previewPage.frameLocator('iframe').locator('#website-ad[nostrads-uid]');
    await previewAd.waitFor();
    assert.equal(new URL(await page.locator('#website-preview > iframe').getAttribute('src')).pathname, '/src/embed-preview.html');
    assert.equal(await page.locator('#website-preview > iframe').getAttribute('srcdoc'), null,
        'Firefox blocks nested same-origin frames in srcdoc under CSP self');
    assert.equal(await previewPage.locator('script').getAttribute('integrity'), manifest.integrity);
    assert.equal(await previewPage.locator('script').getAttribute('crossorigin'), 'anonymous');
    assert.equal(await previewPage.locator('script').getAttribute('data-network'), 'demo');
    const target = await previewPage.locator('script').getAttribute('data-target');
    assert.equal(await previewPage.locator(`#${target} > iframe`).count(), 1, 'the explicit banner div was not used');
    const blockedFrame = await previewPage.locator('body').evaluate(body => new Promise((resolve, reject) => {
        const doc = body.ownerDocument;
        const foreign = doc.createElement('iframe');
        const cleanup = () => {
            clearTimeout(timeout);
            doc.removeEventListener('securitypolicyviolation', onViolation);
            foreign.remove();
        };
        const timeout = setTimeout(() => { cleanup(); reject(new Error('Preview allowed a foreign frame')); }, 5000);
        function onViolation(event) {
            if (event.effectiveDirective !== 'frame-src') return;
            cleanup();
            resolve(event.blockedURI);
        }
        doc.addEventListener('securitypolicyviolation', onViolation);
        foreign.src = 'https://foreign-preview.invalid/frame.html';
        body.append(foreign);
    }));
    assert.match(blockedFrame, /^https:\/\/foreign-preview\.invalid/);
    await page.waitForFunction(() => document.querySelector('#website-preview iframe')?.contentDocument?.body.innerHTML.includes('data-target'));
    const previewUid = await previewAd.getAttribute('nostrads-uid');
    let previewWorker;
    for (let attempt = 0; attempt < 100; attempt++) {
        for (const worker of page.workers()) {
            if (await worker.evaluate(uid => self.invocations.some(request => request.method === 'loadAd' && request.args[0].uid === uid), previewUid)) {
                previewWorker = worker;
                break;
            }
        }
        if (previewWorker) break;
        await page.waitForTimeout(20);
    }
    assert(previewWorker, 'the preview did not request an ad');
    const previewRequests = await previewWorker.evaluate(() => self.invocations);
    const previewOptions = previewRequests.find(request => request.method === 'initDisplay').args[0];
    assert.equal(previewOptions.appKey, demo.appKey);
    assert.deepEqual(previewOptions.relays, demo.relays);
    assert.deepEqual(previewOptions.advertisersWhitelist, [demo.advertiser]);
    const previewSpace = previewRequests.find(request => request.method === 'loadAd').args[0];
    assert.deepEqual(previewSpace.advertisersWhitelist, [demo.advertiser], 'the preview could request real campaigns');
    assert.equal(previewSpace.width, 256);
    assert.equal(previewSpace.height, 256);
    await previewWorker.evaluate(() => self.deliverOffers());
    await previewAd.evaluate(slot => new Promise((resolve, reject) => {
        if (slot.style.backgroundImage) return resolve();
        const timer = setTimeout(() => { observer.disconnect(); reject(new Error('Preview image did not load')); }, 5000);
        const observer = new MutationObserver(() => { if (slot.style.backgroundImage) { clearTimeout(timer); observer.disconnect(); resolve(); } });
        observer.observe(slot, {attributes: true, attributeFilter: ['style']});
    })).catch(async error => {
        console.log('Preview fixture diagnostics:', await previewWorker.evaluate(() => self.invocations), await previewAd.evaluate(slot => slot.outerHTML));
        throw error;
    });
    await previewAd.evaluate(() => { window.open = () => null; });
    await previewAd.click();
    assert.equal(await previewWorker.evaluate(() => self.invocations.filter(request => request.method === 'confirmAd').length), 1);
    await page.waitForFunction(() => document.querySelector('#website-preview-status').hidden);

    await page.locator('#website-size').selectOption('720x90');
    assert.equal(await result.isVisible(), false, 'old code remained visible after changing the format');
    assert.equal(await page.locator('#website-preview iframe').count(), 0, 'old preview was not removed');
    await generate.click();
    await waitForResult();
    const snippet = await page.locator('#website-code').textContent();
    assert.match(snippet, /data-size="720x90"/);
    assert.match(snippet, /data-content="images"/);
    await page.locator('#copy-website-code').click();
    await page.waitForFunction(() => document.querySelector('#copy-website-code').textContent !== 'Copy code');
    assert.match(await page.locator('#copy-website-code').textContent(), /Copied|Code selected/);

    metadata = {lud06: bech32.encode('lnurl', bech32.toWords(Buffer.from('https://example.com/lnurl')), 2048)};
    await input.fill(profile.pubkey);
    await page.locator('#website-content').selectOption('text');
    await generate.click();
    await waitForResult();
    assert.match(await status.textContent(), /Lightning payment link found/);
    assert.match(await page.locator('#website-code').textContent(), /data-content="text"/);

    hold = true;
    await generate.click();
    await page.waitForFunction(() => document.querySelector('#generate-code').disabled);
    while (!heldReplies.length) await page.waitForTimeout(20);
    await input.fill(npub);
    assert.equal(await result.isVisible(), false);
    hold = false;
    metadata = {lud16: 'updated@example.com'};
    await generate.click();
    await waitForResult();
    for (const reply of heldReplies) reply();
    assert.match(await status.textContent(), /updated@example.com/);
    assert.equal(published.length, 0, 'checking a public profile published an event');

    // Paste the exact generated HTML into another origin. The real client and
    // worker bridge must register the recipient, content types and dimensions.
    const host = await runtime.browser.newPage();
    // Chromium asks for LAN access when a different origin embeds our loopback fixture.
    await host.context().grantPermissions(['local-network-access']);
    const errors = [];
    host.on('pageerror', error => errors.push(error.message));
    await host.route('**/nostrads-worker.js', route => route.fulfill({
        contentType: 'text/javascript',
        body: `self.invocations=[]; self.addEventListener('message', event => {
            const request=JSON.parse(event.data); if(request.type!=='invoke') return;
            self.invocations.push(request);
            const result=request.method==='loadAd' ? [{content:JSON.stringify({payload:'Live text ad',link:'https://example.com'}),tags:[['m','text/plain'],['k','link']]},'embedded-fixture'] : true;
            self.postMessage(JSON.stringify({type:'result',invkId:request.invkId,result}));
        });`
    }));
    let textSnippet = await page.locator('#website-code').textContent();
    const websiteUrl = runtime.base.replace('127.0.0.1', 'localhost') + '/website/';
    await host.route(websiteUrl, route => route.fulfill({
        contentType: 'text/html', body: `<!doctype html><html><body>${textSnippet}</body></html>`
    }));
    await host.goto(websiteUrl);
    const iframe = host.locator('iframe');
    await iframe.waitFor();
    await iframe.scrollIntoViewIfNeeded();
    const embedded = host.frameLocator('iframe');
    await embedded.locator('#website-ad').filter({hasText: 'Live text ad'}).waitFor();
    const worker = host.workers()[0];
    const requests = await worker.evaluate(() => self.invocations);
    const options = requests.find(request => request.method === 'initDisplay').args[0];
    assert.equal(options.appKey, npub);
    assert.deepEqual(options.mimeTypes, ['text/plain']);
    assert.equal(options.auth, undefined);
    assert.equal(options.advertisersWhitelist, undefined);
    const registered = requests.find(request => request.method === 'registerAdspace').args[0];
    assert.equal(registered.appKey, npub);
    assert.equal(registered.width, 720);
    assert.equal(registered.height, 90);
    assert.equal(requests.filter(request => request.method === 'confirmAd').length, 0);
    await host.setViewportSize({width: 320, height: 640});
    assert(await host.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    const box = await iframe.boundingBox();
    assert.equal(box.width, box.height * 8, 'responsive embed distorted the selected ad format');
    assert.deepEqual(errors, []);
    await host.setViewportSize({width:1440,height:1000});
    textSnippet = textSnippet.replace('data-target=', 'data-network="demo" data-target=');
    await host.reload();
    await host.frameLocator('iframe').locator('#website-ad').filter({hasText:'Live text ad'}).waitFor();
    const demoWorker = host.workers()[0];
    const demoRequests = await demoWorker.evaluate(() => self.invocations);
    const demoOptions = demoRequests.find(request => request.method === 'initDisplay').args[0];
    assert.equal(demoOptions.appKey, demo.appKey);
    assert.deepEqual(demoOptions.advertisersWhitelist, [demo.advertiser]);
    assert.deepEqual(demoOptions.relays, demo.relays);
    assert.equal(demoRequests.find(request => request.method === 'registerAdspace').args[0].width, 720);
    for (const invalid of [null, {...demo, advertiser: ''}, {...demo, relays: []}]) {
        await host.route('**/demo/network.json', route => route.fulfill({
            status: invalid ? 200 : 404, contentType: 'application/json', body: JSON.stringify(invalid)
        }));
        await host.reload();
        await host.frameLocator('iframe').locator('#website-ad').filter({hasText: 'Ad unavailable'}).waitFor();
        assert.equal(host.workers().length, 0, 'missing demo settings fell back to live campaigns');
        await host.unroute('**/demo/network.json');
    }
    assert.deepEqual(errors, []);
    await host.route('**/' + manifest.file, route => route.fulfill({
        contentType: 'text/javascript', headers: {'Access-Control-Allow-Origin': '*'},
        body: 'window.modifiedLoaderExecuted = true;\n' + loader.toString('utf8')
    }));
    await host.reload();
    assert.equal(await host.locator('iframe').count(), 0, 'the browser executed a script with the wrong SRI hash');
    assert.equal(await host.evaluate(() => window.modifiedLoaderExecuted), undefined);
    await host.close();
    await page.route('**/' + manifest.file, route => route.fulfill({
        contentType: 'text/javascript', headers: {'Access-Control-Allow-Origin': '*'},
        body: 'window.modifiedLoaderExecuted = true;\n' + loader.toString('utf8')
    }));
    const rejectedLoader = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/' + manifest.file));
    await generate.click();
    await waitForResult();
    await rejectedLoader;
    await page.clock.fastForward(16000);
    await page.waitForFunction(() => document.querySelector('#website-preview-status').dataset.state === 'error');
    assert(await page.locator('#website-preview-status').isVisible(), 'a blocked loader left only a blank preview');
    assert.equal(await previewPage.locator('iframe').count(), 0);
    assert.equal(await page.evaluate(() => window.modifiedLoaderExecuted), undefined);
}
