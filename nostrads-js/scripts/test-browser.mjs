import assert from 'node:assert/strict';
import {mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {browserRuntime, moduleRoot} from './browser-runtime.mjs';

const test = process.argv[2] || 'renderer';
if (!['renderer','publication','site','upload'].includes(test)) throw new Error('Unknown browser test');
const runtime = await browserRuntime();
try {
    const page = await runtime.browser.newPage({viewport:{width:1440,height:1000}});
    if (test === 'upload') {
        const bytes = Buffer.from([1,2,3]);
        const hash = createHash('sha256').update(bytes).digest('hex');
        let fixture = {url:`https://cdn.example/${hash}.png`,sha256:hash,size:3,type:'image/png',uploaded:1};
        let destinationRequests = 0;
        await page.route('https://cdn.example/**', route => { destinationRequests++; return route.abort(); });
        await page.route('https://upload.example/upload', async route => {
            assert.deepEqual(route.request().postDataBuffer(), bytes);
            await route.fulfill({status:201,contentType:'application/json',body:JSON.stringify(fixture),
                headers:{'Access-Control-Allow-Origin':'*'}});
        });
        await page.goto(runtime.base + '/browser-tests/upload.html');
        await page.evaluate(async () => {
            const {default: ads} = await import('/src/console/nostr-ads.js');
            window.uploadClient = ads.newAdvertiserClient([], '0'.repeat(63) + '1', ['https://upload.example']);
        });
        const upload = () => page.evaluate(async () => {
            try { return {descriptor:await window.uploadClient.uploadImage({imageData:new Uint8Array([1,2,3]),mimeType:'image/png'})}; }
            catch (error) { return {error:String(error)}; }
        });
        const accepted = await upload();
        assert.equal(accepted.descriptor?.url, fixture.url, accepted.error);
        for (const invalid of [{...fixture,size:4}, {...fixture,url:`http://cdn.example/${hash}.png`},
            {...fixture,url:`https://cdn.example/${'0'.repeat(64)}.png`,sha256:'0'.repeat(64)}]) {
            fixture = invalid;
            assert((await upload()).error, 'invalid upload descriptor was accepted');
        }
        assert.equal(destinationRequests, 0, 'descriptor validation unexpectedly requested a CDN');
        await page.evaluate(() => window.uploadClient.close());
    } else if (test !== 'site') {
        await page.goto(`${runtime.base}/browser-tests/${test}.html`);
        await page.waitForFunction(() => ['passed','failed'].includes(document.body.dataset.testStatus), null, {timeout:30000});
        const result = await page.locator('body').evaluate(body => ({status:body.dataset.testStatus,error:body.dataset.testError}));
        assert.equal(result.status, 'passed', result.error);
        if (test === 'renderer') {
            // Real browser input is distinct from dispatchEvent()/element.click().
            await page.evaluate(async () => {
                const {default: renderer} = await import('/src/console/ad-render.js');
                const target = document.createElement('div');
                target.id = 'trusted-click';
                target.style.cssText = 'width:200px;height:100px';
                document.body.appendChild(target);
                window.trustedConfirmations = 0;
                window.open = () => {};
                renderer.render(target, {mimeType:'text/plain',payload:'Activate',link:'https://example.com',actionType:'link'},
                    () => window.trustedConfirmations++, error => { throw new Error(error); });
            });
            await page.locator('#trusted-click').click();
            await page.locator('#trusted-click').press('Enter');
            assert.equal(await page.evaluate(() => window.trustedConfirmations), 1, 'real activations were not confirmed exactly once');
        }
    } else {
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        // The static collection must work without external relays, fonts or APIs.
        await page.route(/^https?:\/\/(?!127\.0\.0\.1:)/, route => route.abort());
        await page.goto(runtime.base + '/src/');
        await page.locator('#gallery img').last().scrollIntoViewIfNeeded();
        await page.waitForFunction(() => [...document.querySelectorAll('#gallery img')].every(img => img.complete && img.naturalWidth > 0));
        assert.equal(await page.locator('.campaign:visible').count(), 6);
        await page.locator('[data-filter="portrait"]').click();
        assert.equal(await page.locator('.campaign:visible').count(), 2);
        await page.locator('[data-filter="all"]').click();
        const qaDir = resolve(moduleRoot, 'build/qa');
        await mkdir(qaDir, {recursive:true});
        for (const width of [1440, 390, 320]) {
            await page.setViewportSize({width,height:1000});
            await page.evaluate(() => window.scrollTo(0,0));
            assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `horizontal overflow at ${width}px`);
            await page.screenshot({path:resolve(qaDir, `landing-${width}.png`),fullPage:true});
        }
        await page.goto(runtime.base + '/src/console/');
        assert.match(await page.locator('#console-status').textContent(), /extension/);
        assert.equal(errors.length, 0, errors.join('\n'));
        console.log(`Screenshots: ${qaDir}`);
    }
    console.log(`Browser ${test} test passed.`);
} finally { await runtime.close(); }
