document.querySelector('#copy-code').addEventListener('click', async event => {
    try {
        await navigator.clipboard.writeText(document.querySelector('#code').textContent);
        event.target.textContent = 'Copied';
    } catch (_) {
        event.target.textContent = 'Select the code to copy';
    }
});

const placements = document.querySelector('#demo-placements');
const status = document.querySelector('#network-status');
let waiting = false;
let failed = false;
let demoOptions;

function updateCode() {
    if (!demoOptions) return;
    const slots = [...placements.querySelectorAll('.nostr-ddspace')];
    document.querySelector('#demo-code').textContent =
        `NostrAds(${JSON.stringify(demoOptions, null, 2)}, document.querySelector('#demo-placements'));\n\n` +
        '<div id="demo-placements">\n' + slots.map(slot => {
            const rect = slot.getBoundingClientRect();
            return `  <div class="nostr-ddspace" style="width:${Math.floor(rect.width)}px;height:${Math.floor(rect.height)}px"></div>`;
        }).join('\n') + '\n</div>';
}

function updateStatus() {
    const slots = [...placements.querySelectorAll('.nostr-ddspace')];
    const loaded = slots.filter(slot => slot.style.backgroundImage && slot.style.backgroundImage !== 'none').length;
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

document.querySelector('#demo-format').addEventListener('change', event => {
    const format = event.target.value;
    const slot = document.createElement('div');
    slot.id = 'flexible-ad';
    slot.className = `nostr-ddspace demo-${format}`;
    slot.setAttribute('aria-label', `${format === 'square' ? 'Square' : 'Landscape'} ad from the demo network`);
    const placeholder = document.createElement('span');
    placeholder.className = 'loading-label';
    placeholder.textContent = 'Waiting for an ad…';
    slot.appendChild(placeholder);
    // Replacing the element lets the regular client release the previous offer
    // and register the new dimensions through its normal DOM observer.
    document.querySelector('#flexible-ad').replaceWith(slot);
    requestAnimationFrame(updateCode);
});

// The renderer sets a background only after the actual network image has loaded.
const observer = new MutationObserver(updateStatus);
observer.observe(placements, {attributes:true, attributeFilter:['style'], childList:true, subtree:true});
const waitingTimer = setTimeout(() => { waiting = true; updateStatus(); }, 30000);

try {
    const response = await fetch(new URL('./demo/network.json', import.meta.url));
    if (!response.ok) throw new Error('Demo configuration unavailable');
    const config = await response.json();
    demoOptions = {
        appKey: config.appKey,
        relays: config.relays,
        advertisersWhitelist: [config.advertiser],
        worker: new URL('./nostrads-worker.js', import.meta.url).href,
        allowedImageOrigins: config.imageOrigins
    };
    updateCode();
    const dispose = await NostrAds(demoOptions, placements);
    window.addEventListener('pagehide', () => { dispose(); observer.disconnect(); clearTimeout(waitingTimer); }, {once:true});
} catch (_) {
    failed = true;
    clearTimeout(waitingTimer);
    status.dataset.state = 'error';
    status.textContent = 'Could not start the network demo. Reload the page to retry.';
}
