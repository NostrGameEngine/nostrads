import {adContent} from './website-options.js';

const placements = document.querySelector('#demo-placements');
const status = document.querySelector('#network-status');
const formatSelect = document.querySelector('#demo-format');
const contentSelect = document.querySelector('#demo-content');
const formats = {
    square: '256x256', 'small-square': '250x250', landscape: '512x256', portrait: '256x512',
    wide: '512x128', banner: '480x60', leaderboard: '720x90', skyscraper: '128x512'
};
let waiting = false;
let failed = false;

function updateStatus() {
    const slots = [...placements.querySelectorAll('.nostr-ddspace')];
    const loaded = slots.filter(slot => (slot.style.backgroundImage && slot.style.backgroundImage !== 'none')
        || (slot.firstChild?.nodeType === Node.TEXT_NODE && slot.getAttribute('role') === 'link')).length;
    if (loaded) {
        status.dataset.state = 'loaded';
        status.textContent = `${loaded} of ${slots.length} demo placements loaded from the network.`;
    } else if (!failed) {
        status.dataset.state = 'loading';
        status.textContent = waiting
            ? 'No demo ad has loaded yet. The client is still trying; the test network may be unavailable.'
            : 'Loading demo ads from the network…';
    }
}

function changePlacement() {
    const format = formatSelect.value;
    if (!Object.hasOwn(formats, format) || !Object.hasOwn(adContent, contentSelect.value)) return;
    const slot = document.createElement('div');
    slot.id = 'flexible-ad';
    slot.className = `nostr-ddspace demo-${format}`;
    const [width, height] = formats[format].split('x');
    slot.style.width = `${width}px`;
    slot.style.height = `${height}px`;
    slot.setAttribute('nostrads-mimeTypes', adContent[contentSelect.value].join(','));
    slot.setAttribute('aria-label', `${formatSelect.selectedOptions[0].textContent}, ${contentSelect.selectedOptions[0].textContent.toLowerCase()} ad from the demo network`);
    const placeholder = document.createElement('span');
    placeholder.className = 'loading-label';
    placeholder.textContent = 'Waiting for an ad…';
    slot.appendChild(placeholder);
    // Replacing the element lets the regular client release the previous offer
    // and register the new dimensions through its normal DOM observer.
    document.querySelector('#flexible-ad').replaceWith(slot);
}
formatSelect.addEventListener('change', changePlacement);
contentSelect.addEventListener('change', changePlacement);

// The renderer sets a background only after the actual network image has loaded.
const observer = new MutationObserver(updateStatus);
observer.observe(placements, {attributes:true, attributeFilter:['style'], childList:true, subtree:true});
const waitingTimer = setTimeout(() => { waiting = true; updateStatus(); }, 30000);

try {
    const response = await fetch(new URL('./demo/network.json', import.meta.url));
    if (!response.ok) throw new Error('Demo configuration unavailable');
    const config = await response.json();
    const demoOptions = {
        appKey: config.appKey,
        relays: config.relays,
        advertisersWhitelist: [config.advertiser],
        worker: new URL('./nostrads-worker.js', import.meta.url).href,
        allowedImageOrigins: config.imageOrigins
    };
    const dispose = await NostrAds(demoOptions, placements);
    window.addEventListener('pagehide', () => { dispose(); observer.disconnect(); clearTimeout(waitingTimer); }, {once:true});
} catch (_) {
    failed = true;
    clearTimeout(waitingTimer);
    status.dataset.state = 'error';
    status.textContent = 'Could not start the network demo. Reload the page to retry.';
}
