import assert from 'node:assert/strict';
import {createCipheriv, createDecipheriv, randomBytes, createHmac, timingSafeEqual} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {secp256k1, schnorr} from '../../nostrads-bindings4js/node_modules/@noble/curves/esm/secp256k1.js';
import {chacha20} from '../../nostrads-bindings4js/node_modules/@noble/ciphers/esm/chacha.js';
import {signEvent, verifyEvent} from '../../nostrads-bindings4js/scripts/sync-demo.mjs';
import {browserRuntime} from './browser-runtime.mjs';
const key = n => Buffer.from(n.toString(16).padStart(64, '0'), 'hex');
const walletKey = key(3), clientKey = key(4), advertiserKey = key(5);
const hex = bytes => Buffer.from(bytes).toString('hex');
const walletPubkey = hex(schnorr.getPublicKey(walletKey));
const clientPubkey = hex(schnorr.getPublicKey(clientKey));
const connection = `nostr+walletconnect://${walletPubkey}?relay=wss%3A%2F%2Fwallet.example&secret=${hex(clientKey)}`;
const shared = secp256k1.getSharedSecret(walletKey, '02' + clientPubkey).slice(1);
const mac = (key, ...values) => createHmac('sha256', key).update(Buffer.concat(values)).digest();
function keys(nonce) {
    const conversation = mac(Buffer.from('nip44-v2'), Buffer.from(shared));
    let previous = Buffer.alloc(0);
    const blocks = [];
    for (let n = 1; n <= 3; n++) { previous = mac(conversation, previous, nonce, Buffer.from([n])); blocks.push(previous); }
    const bytes = Buffer.concat(blocks);
    return [bytes.subarray(0, 32), bytes.subarray(32, 44), bytes.subarray(44, 76)];
}
function encrypt(text, mode) {
    if (mode === 'nip04') {
        const iv = randomBytes(16), cipher = createCipheriv('aes-256-cbc', shared, iv);
        return Buffer.concat([cipher.update(text), cipher.final()]).toString('base64') + '?iv=' + iv.toString('base64');
    }
    const bytes = Buffer.from(text), power = 2 ** Math.ceil(Math.log2(bytes.length)), chunk = power <= 256 ? 32 : power / 8;
    const padded = Buffer.alloc(2 + (bytes.length <= 32 ? 32 : chunk * Math.ceil(bytes.length / chunk)));
    padded.writeUInt16BE(bytes.length); bytes.copy(padded, 2);
    const nonce = randomBytes(32), [streamKey, streamNonce, hmacKey] = keys(nonce);
    const ciphertext = Buffer.from(chacha20(streamKey, streamNonce, padded));
    return Buffer.concat([Buffer.from([2]), nonce, ciphertext, mac(hmacKey, nonce, ciphertext)]).toString('base64');
}
function decrypt(content, mode) {
    if (mode === 'nip04') {
        const [ciphertext, iv] = content.split('?iv='), cipher = createDecipheriv('aes-256-cbc', shared, Buffer.from(iv, 'base64'));
        return Buffer.concat([cipher.update(Buffer.from(ciphertext, 'base64')), cipher.final()]).toString();
    }
    const bytes = Buffer.from(content, 'base64'), nonce = bytes.subarray(1, 33), ciphertext = bytes.subarray(33, -32);
    const [streamKey, streamNonce, hmacKey] = keys(nonce);
    assert(timingSafeEqual(bytes.subarray(-32), mac(hmacKey, nonce, ciphertext)));
    const padded = Buffer.from(chacha20(streamKey, streamNonce, ciphertext));
    return padded.subarray(2, 2 + padded.readUInt16BE()).toString();
}
const runtime = await browserRuntime();
let mode = 'nip44_v2', supported = true, budget = {total_budget: 100000, used_budget: 12000, renewal_period: 'never'};
const methods = [], published = [];
const now = Math.floor(Date.now() / 1000);
const profile = (privateKey, createdAt, tags, content) => signEvent({kind:0, created_at:createdAt, tags, content:JSON.stringify(content)}, privateKey);
const delegate = profile(walletKey, now, [['t','nip-ad:delegate']], {
    name:'Public delegate', about:'A public provider', verified:true, 'nostrads:fees':'1000:0.01:10000'
});
const older = profile(walletKey, now - 10, [['t','nip-ad:delegate']], {name:'Outdated delegate'});
const unmarked = profile(advertiserKey, now, [['t','nip-ad:advertiser']], {name:'Advertiser'});
const malformed = signEvent({kind:0, created_at:now, tags:[['t','nip-ad:delegate']], content:'broken JSON'}, key(6));
let discoveryQueries = 0;
try {
    const context = await runtime.browser.newContext();
    await context.routeWebSocket(/.*/, socket => {
        const subscriptions = new Map(), pending = new Map();
        const flush = () => {
            for (const [requestId, event] of pending) for (const [id, filter] of subscriptions) {
                if (!filter.kinds?.includes(23195) || !filter['#e']?.includes(requestId)) continue;
                socket.send(JSON.stringify(['EVENT',id,{...event,sig:'0'.repeat(128)}]));
                const wrongCorrelation = signEvent({...event, tags:[['p',clientPubkey],['e','f'.repeat(64)]]},walletKey);
                socket.send(JSON.stringify(['EVENT',id,wrongCorrelation]));
                socket.send(JSON.stringify(['EVENT',id,event]));
                pending.delete(requestId);
            }
        };
        socket.onMessage(message => {
            const [type, value, filter] = JSON.parse(String(message));
            if (type === 'REQ') {
                subscriptions.set(value, filter);
                if (filter.kinds?.includes(13194)) {
                    const info = signEvent({kind:13194,created_at:Math.floor(Date.now()/1000),tags:[['encryption',mode]],content:supported?'get_budget get_info':'get_info'},walletKey);
                    socket.send(JSON.stringify(['EVENT',value,info]));
                } else if (filter.kinds?.includes(0) && filter['#t']?.includes('nip-ad:delegate')) {
                    discoveryQueries++;
                    for (const event of [{...delegate,sig:'0'.repeat(128)}, delegate, older, unmarked, malformed]) {
                        socket.send(JSON.stringify(['EVENT',value,event]));
                    }
                } else if (filter.kinds?.includes(0) && filter.authors?.includes(walletPubkey)) {
                    socket.send(JSON.stringify(['EVENT',value,delegate]));
                }
                socket.send(JSON.stringify(['EOSE',value]));
                flush();
            } else if (type === 'CLOSE') subscriptions.delete(value);
            else if (type === 'EVENT') {
                assert(verifyEvent(value));
                published.push(value.kind);
                socket.send(JSON.stringify(['OK',value.id,true,'']));
                if (value.kind !== 23194) return;
                const request = JSON.parse(decrypt(value.content, mode));
                methods.push(request.method);
                assert.equal(request.method,'get_budget','budget verification attempted a wallet payment');
                assert.deepEqual(request.params,{});
                const event = signEvent({kind:23195,created_at:Math.floor(Date.now()/1000),tags:[['p',clientPubkey],['e',value.id]],content:encrypt(JSON.stringify({result_type:'get_budget',result:budget}),mode)},walletKey);
                pending.set(value.id,event); flush();
            }
        });
    });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror',error=>errors.push(error.message));
    await page.goto(runtime.base+'/browser-tests/upload.html');
    const check = () => page.evaluate(async connection => {
        const {default:ads} = await import('/src/console/nostr-ads.js');
        const client = ads.newConsoleNetwork();
        try { return {result:await client.checkWalletBudget(connection)}; }
        catch (_) { return {error:true}; }
        finally {client.close();}
    },connection);
    assert.deepEqual((await check()).result,{status:'limited',totalMsats:100000,usedMsats:12000,period:'never'});
    mode='nip04'; budget={};
    assert.equal((await check()).result.status,'unlimited');
    budget={total_budget:0,used_budget:0,renewal_period:'never'};
    assert.equal((await check()).result.status,'limited','zero limit was treated as unlimited');
    budget={total_budget:-1,used_budget:0,renewal_period:'never'};
    assert((await check()).error,'a malformed spending limit was accepted');
    supported=false;
    const count=methods.length;
    assert.equal((await check()).result.status,'unsupported');
    assert.equal(methods.length,count,'an unsupported method was sent to the wallet');
    const discovered = await page.evaluate(async () => {
        const {default:ads} = await import('/src/console/nostr-ads.js');
        const client=ads.newConsoleNetwork();
        try{return await client.discoverDelegates(['wss://relay.example']);}finally{client.close();}
    });
    assert.equal(discovered.profiles.length,2);
    assert.equal(discovered.profiles.find(event=>event.pubkey===walletPubkey).id,delegate.id,'older metadata replaced the current profile');
    assert(discovered.profiles.every(verifyEvent),'an unsigned profile reached the client');

    const queriesBefore = discoveryQueries;
    await page.goto(runtime.base+'/src/console/');
    for (let attempt=0; attempt<100 && discoveryQueries<=queriesBefore; attempt++) await page.waitForTimeout(50);
    assert(discoveryQueries>queriesBefore,'opening the console did not automatically discover delegates');
    await page.goto(runtime.base+'/src/console/#new');
    const card=page.locator(`.delegate-card[data-pubkey="${walletPubkey}"]`);
    await card.waitFor();
    assert.equal(await page.locator('#delegate-cards .delegate-card').count(),1,'malformed or unmarked profiles became delegate cards');
    assert.equal(await page.locator('#find-delegates, #check-wallet-budget').count(),0);
    assert.equal(await page.locator('#nwcInfo').isVisible(),false);
    assert.equal(await page.locator('[name="nwc"]').inputValue(),'');
    assert.equal(await page.locator('#wallet-limit-warning').isVisible(),false);
    assert.equal(await card.locator('.delegate-verified').count(),0,'public metadata granted a verified badge');
    assert.match(await card.textContent(),/Public delegate/);
    assert.match(await card.textContent(),/1% service fee/);
    await card.click();
    assert(await page.locator('#delegate-trust-warning').isVisible());
    assert.equal(await page.locator('[name="delegate"]').inputValue(),walletPubkey);
    const calculated = await page.evaluate(async()=>{
        const {campaignBudget}=await import('/src/console/components/wallet-budget.js');
        const now=Date.parse('2026-10-24T12:00:00Z');
        return [campaignBudget('100','2026-10-27',now),campaignBudget('100','2026-10-24',now),campaignBudget('0','2026-10-27',now)];
    });
    assert.equal(calculated[0].days,3);
    assert.equal(calculated[0].totalMsats,300000);
    assert.equal(calculated[1],null);
    assert.equal(calculated[2],null);
    const tomorrow = new Date(Date.now()+86400000).toISOString().slice(0,10);
    await page.locator('[name="dailyBudget"]').fill('100');
    await page.locator('[name="expire_at"]').fill(tomorrow);
    let refreshes=0;
    const refresh=async()=>{
        await page.locator('[name="nwc"]').fill(connection+(++refreshes%2 ? ' ' : ''));
    };
    supported=true; mode='nip44_v2'; budget={};
    await refresh();
    await page.waitForFunction(()=>!document.querySelector('#wallet-limit-warning').hidden && document.querySelector('#wallet-budget-warning-detail').textContent.includes('no spending limit'));
    assert(await page.locator('#wallet-limit-warning #confirm-wallet-limit').isVisible());
    await page.locator('#confirm-wallet-limit').check();
    await page.locator('[name="dailyBudget"]').fill('101');
    assert.equal(await page.locator('#confirm-wallet-limit').isChecked(),false,'changing the campaign preserved an outdated confirmation');
    await page.locator('[name="dailyBudget"]').fill('100');
    budget={total_budget:112000,used_budget:12000,renewal_period:'never'};
    await refresh();
    await page.waitForFunction(()=>document.querySelector('#wallet-budget-status').dataset.state==='success');
    assert.match(await page.locator('#wallet-budget-status').textContent(),/100 sats/);
    assert.equal(await page.locator('#wallet-budget-confirm').isVisible(),false);
    await page.locator('[name="dailyBudget"]').fill('200');
    await page.waitForFunction(()=>document.querySelector('#wallet-budget-status').dataset.state==='error');
    await page.locator('[name="dailyBudget"]').fill('100');
    budget={total_budget:100000,used_budget:12000,renewal_period:'never'};
    await refresh();
    await page.waitForFunction(()=>document.querySelector('#wallet-budget-status').dataset.state==='error');
    assert.match(await page.locator('#wallet-budget-status').textContent(),/88 sats/);
    assert.equal(await page.locator('#wallet-budget-confirm').isVisible(),false,'a known budget mismatch was bypassable with a checkbox');
    await page.locator('[name="expire_at"]').fill(new Date(Date.now()+3*86400000).toISOString().slice(0,10));
    budget={total_budget:100000,used_budget:0,renewal_period:'daily',renews_at:Math.floor(Date.now()/1000)+3600};
    await refresh();
    await page.waitForFunction(()=>!document.querySelector('#wallet-limit-warning').hidden && document.querySelector('#wallet-budget-warning-detail').textContent.includes('renews before'));
    assert(await page.locator('#wallet-budget-confirm').isVisible());
    await page.locator('[name="expire_at"]').fill(tomorrow);
    supported=false;
    await refresh();
    await page.waitForFunction(()=>!document.querySelector('#wallet-limit-warning').hidden && document.querySelector('#wallet-budget-warning-detail').textContent.includes('get_budget'));
    assert(await page.locator('#wallet-budget-confirm').isVisible());
    const config=JSON.parse(await readFile(new URL('../src/console/config.json',import.meta.url),'utf8'));
    const approved = config.verified_delegates[0];
    await page.locator('[name="delegate"]').fill(approved);
    assert.equal(await page.locator('#delegate-trust-warning').isVisible(),false);
    await page.evaluate(async () => {
        const {login} = await import('/src/console/components/session.js');
        window.nostr = {getPublicKey: async () => '79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798'};
        await login('extension','','',[],()=>{});
    });
    await page.locator('[name="description"]').fill('Budget validation');
    await page.locator('[name="link"]').fill('https://example.com');
    await page.locator('[name="bid"]').fill('1');
    await page.locator('[name="expire_at"]').fill(new Date(Date.now()+86400000).toISOString().slice(0,10));
    await page.locator('[name="mimeType"]').selectOption('text/plain');
    await page.locator('#payloadText').fill('Test campaign');
    await page.locator('[name="callToAction"]').fill('Open');
    await page.locator('[name="context"]').fill('Independent budget verification');
    supported=true; budget={total_budget:100000,used_budget:12000,renewal_period:'never'};
    await refresh();
    await page.waitForFunction(()=>document.querySelector('#wallet-budget-status').dataset.state==='error');
    await page.locator('#submit').click();
    await page.waitForFunction(()=>document.querySelector('#error').textContent.includes('must match the campaign budget'));
    budget={total_budget:100000,used_budget:0,renewal_period:'never'};
    await refresh();
    await page.waitForFunction(()=>document.querySelector('#wallet-budget-status').dataset.state==='success');
    budget={};
    await page.locator('#submit').click();
    await page.waitForFunction(()=>document.querySelector('#error').textContent.includes('Confirm that this NWC'));
    assert(await page.locator('#wallet-budget-confirm').isVisible(),'fresh pre-publish check did not detect a removed limit');
    config.testmode=true; config.testmode_config.nwc=connection;
    await page.route('**/console/config.json',route=>route.fulfill({contentType:'application/json',body:JSON.stringify(config)}));
    await page.reload();
    await page.locator('#nwcInfo').waitFor();
    assert.match(await page.locator('#nwcInfo').textContent(),/Demo console/);
    assert.equal(await page.locator('[name="delegate"]').inputValue(),config.testmode_config.default_delegate);
    assert.equal(await page.locator('#wallet-limit-warning').isVisible(),false);
    assert.deepEqual(errors,[]);
    assert(published.every(kind=>kind===23194),'read-only checks published a campaign');
    console.log('Console discovery and wallet budget tests passed (tagged signed profiles, automatic queries, NIP-04/44, campaign allowance, confirmation fallback).');
    await context.close();
} finally {await runtime.close();}
