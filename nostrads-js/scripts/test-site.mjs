import assert from 'node:assert/strict';
import {mkdir, readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {moduleRoot} from './browser-runtime.mjs';

export async function testSite(runtime, page) {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const config = JSON.parse(await readFile(resolve(moduleRoot, 'src/demo/network.json'), 'utf8'));
    const imageUrl = config.imageOrigins[0] + '/' + 'a'.repeat(64) + '.png';
    const image = await readFile(resolve(moduleRoot, 'browser-tests/test-image.svg'));
    await page.route(/^https?:\/\/(?!127\.0\.0\.1:)/, route => route.request().url() === imageUrl
        ? route.fulfill({contentType:'image/svg+xml',body:image}) : route.abort());
    // Keep the real bundled client, its worker bridge and renderer. Substitute only
    // the network worker's replies so CI needs neither a delegate nor a wallet.
    await page.route('**/nostrads-worker.js', route => route.fulfill({contentType:'text/javascript',body:`
        self.invocations = [];
        const pending = [];
        const reply = (request, result) => self.postMessage(JSON.stringify({type:'result',invkId:request.invkId,result}));
        self.deliverOffers = () => {
            for (const request of pending.splice(0)) reply(request, [{
                content:JSON.stringify({payload:${JSON.stringify(imageUrl)},link:'https://example.com',description:'Network fixture',call_to_action:'Open'}),
                tags:[['m','image/png'],['k','link']]
            }, 'fixture-' + request.args[0].uid]);
        };
        self.addEventListener('message', event => {
            const request = JSON.parse(event.data);
            if (request.type !== 'invoke') return;
            self.invocations.push(request);
            if (request.method === 'loadAd') pending.push(request);
            else reply(request, true);
        });
    `}));
    await page.clock.install();
    await page.goto(runtime.base + '/src/');
    await page.waitForFunction(() => document.querySelector('#demo-code').textContent.includes('advertisersWhitelist'));
    assert.equal(await page.locator('#demo-placements .nostr-ddspace').count(), 2);
    assert.equal(await page.locator('#gallery, .network-section').count(), 0);
    assert.match(await page.locator('h1').textContent(), /Peer-to-peer/);
    await page.waitForFunction(() => [...document.querySelectorAll('#demo-placements .nostr-ddspace')].every(e => e.hasAttribute('nostrads-uid')));
    const worker = page.workers()[0];
    for (let attempt = 0; attempt < 100; attempt++) {
        if (await worker.evaluate(() => self.invocations.filter(x => x.method === 'loadAd').length === 2)) break;
        await new Promise(resolve => setTimeout(resolve, 20));
    }
    const requests = await worker.evaluate(() => self.invocations);
    const options = requests.find(x => x.method === 'initDisplay').args[0];
    assert.deepEqual(options.relays, config.relays);
    assert.deepEqual(options.advertisersWhitelist, [config.advertiser]);
    assert.deepEqual(options.allowedImageOrigins, config.imageOrigins);
    assert.equal(new URL(options.worker).pathname, '/src/nostrads-worker.js');
    assert.equal(requests.filter(x => x.method === 'registerAdspace').length, 2);
    assert(await page.locator('#demo-placements .nostr-ddspace').evaluateAll(slots => slots.every(e => !e.style.backgroundImage)), 'local artwork filled an empty network slot');
    await page.clock.fastForward(31000);
    assert.match(await page.locator('#network-status').textContent(), /No demo ad has loaded yet/);
    await worker.evaluate(() => self.deliverOffers());
    await page.waitForFunction(() => document.querySelector('#network-status').textContent.startsWith('2 of 2'));
    assert(await page.locator('#demo-placements .nostr-ddspace').evaluateAll((slots, url) => slots.every(e => e.style.backgroundImage.includes(url)), imageUrl));
    assert.equal((await worker.evaluate(() => self.invocations)).filter(x => x.method === 'confirmAd').length, 0, 'link ads confirmed without a click');
    await page.locator('#demo-format').selectOption('landscape');
    await page.waitForFunction(() => document.querySelector('.demo-landscape')?.hasAttribute('nostrads-uid'));
    for (let attempt = 0; attempt < 100; attempt++) {
        if (await worker.evaluate(() => self.invocations.filter(x => x.method === 'loadAd').length === 3)) break;
        await new Promise(resolve => setTimeout(resolve, 20));
    }
    const changed = await worker.evaluate(() => self.invocations.filter(x => x.method === 'loadAd').at(-1).args[0]);
    assert.equal(changed.width, changed.height * 2);
    await worker.evaluate(() => self.deliverOffers());
    await page.waitForFunction(() => document.querySelector('.demo-landscape').style.backgroundImage);
    const lifecycle = await worker.evaluate(() => self.invocations.map(x => x.method));
    assert(lifecycle.includes('cancelAd'), 'previous offer was not cancelled when changing format');
    assert(lifecycle.includes('unregisterAdspace'), 'previous space was not unregistered');
    await page.clock.resume();
    const qaDir = resolve(moduleRoot, 'build/qa');
    await mkdir(qaDir, {recursive:true});
    for (const width of [1440, 768, 390, 320]) {
        await page.setViewportSize({width,height:1000});
        await page.evaluate(() => window.scrollTo({top:0,behavior:'instant'}));
        await new Promise(resolve => setTimeout(resolve, 150));
        assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `horizontal overflow at ${width}px`);
        await page.screenshot({path:resolve(qaDir, `landing-fixture-${width}.png`),fullPage:true});
    }
    await page.route('**/demo/network.json', route => route.fulfill({status:503,body:'Unavailable'}));
    await page.reload();
    await page.waitForFunction(() => document.querySelector('#network-status').dataset.state === 'error');
    assert(await page.locator('#demo-placements .nostr-ddspace').evaluateAll(slots => slots.every(e => !e.style.backgroundImage)));
    await page.goto(runtime.base + '/src/console/');
    assert.match(await page.locator('#console-status').textContent(), /extension/);
    assert.equal(errors.length, 0, errors.join('\n'));
    console.log(`Screenshots (offline fixtures): ${qaDir}`);
}
