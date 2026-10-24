import {adSizes, adContent, isAppKey, websiteOptions} from './website-options.js';

const params = new URLSearchParams(location.search);
const appKey = params.get('appKey') ?? '';
const size = params.get('size');
const content = params.get('content');
const network = params.get('network') ?? 'production';
const slot = document.querySelector('#website-ad');

try {
    if ((network !== 'demo' && !isAppKey(appKey)) || !adSizes.includes(size) || !Object.hasOwn(adContent, content)) {
        throw new Error('Invalid ad settings');
    }
    if (network === 'demo') slot.querySelector('span').textContent = 'Waiting for a demo ad in this format…';
    const options = await websiteOptions(network);
    const dispose = await NostrAds({
        ...options,
        appKey: options.appKey ?? appKey,
        mimeTypes: adContent[content],
        worker: new URL('./nostrads-worker.js', import.meta.url).href
    });
    window.addEventListener('pagehide', () => dispose(), {once: true});
} catch (_) {
    slot.textContent = 'Ad unavailable';
}
