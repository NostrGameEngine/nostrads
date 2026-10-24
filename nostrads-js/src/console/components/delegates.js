import NostrAds from '../nostr-ads.js';
import {getConfig, getRelays} from './options.js';
import {safeLinkUrl} from '../ad-render.js';

let cachedDirectory;
let cachedRelays;
export async function loadDelegateDirectory() {
    const relays = await getRelays();
    const key = JSON.stringify(relays);
    if (!cachedDirectory || cachedRelays !== key) {
        cachedRelays = key;
        const network = NostrAds.newConsoleNetwork();
        cachedDirectory = network.discoverDelegates(relays).then(({profiles}) => profiles.flatMap(event => {
            try {
                const profile = JSON.parse(event.content);
                if (!profile || typeof profile !== 'object' || Array.isArray(profile)) return [];
                return [{...profile, pubkey: event.pubkey}];
            } catch (_) { return []; }
        })).finally(() => network.close());
        cachedDirectory.catch(() => { if (cachedRelays === key) cachedDirectory = null; });
    }
    return cachedDirectory;
}
window.addEventListener('nostrads:network', () => { cachedDirectory = null; });

function feeDescription(profile) {
    if (typeof profile['nostrads:fees'] !== 'string') return 'Service fee not published';
    const parts = profile['nostrads:fees'].split(':');
    if (parts.length !== 3 || parts.some(value => !value.trim())) return 'Service fee not published';
    const [min, rate, max] = parts.map(Number);
    if (!Number.isSafeInteger(min) || !Number.isSafeInteger(max) || !Number.isFinite(rate)
        || min < 0 || max < min || rate < 0 || rate > 1) return 'Service fee not published';
    if (min === 0 && rate === 0) return 'No service fee';
    const percent = Number((rate * 100).toFixed(8));
    return `${percent}% service fee · Min ${min / 1000} sats · Max ${max / 1000} sats`;
}

export async function setupDelegates(view, lifecycle) {
    const config = await getConfig();
    const relays = await getRelays();
    if (!lifecycle.active) return;
    const input = view.querySelector('[name="delegate"]');
    const cards = view.querySelector('#delegate-cards');
    const warning = view.querySelector('#delegate-trust-warning');
    const status = view.querySelector('#delegate-search-status');
    const identity = key => key.startsWith('npub1') ? key : NostrAds.publicKeyToBech32(key);
    const verified = new Set((config.verified_delegates ?? []).map(identity));
    const candidates = new Map();
    const reader = NostrAds.newAdvertiserClient(relays, '', []);
    lifecycle.cleanup.push(() => reader.close());
    let selection = 0;
    const matches = key => {
        try { return identity(input.value.trim().toLowerCase()) === identity(key); }
        catch (_) { return false; }
    };
    const approved = key => verified.has(identity(key));
    function render() {
        cards.replaceChildren();
        const sorted = [...candidates.values()].sort((a, b) => Number(approved(b.pubkey)) - Number(approved(a.pubkey)));
        for (const item of sorted) {
            const card = document.createElement('article');
            card.className = 'delegate-card';
            card.dataset.pubkey = item.pubkey;
            card.classList.toggle('is-selected', matches(item.pubkey));
            const select = document.createElement('button');
            select.type = 'button';
            select.className = 'delegate-select';
            select.setAttribute('aria-pressed', String(matches(item.pubkey)));
            const name = document.createElement('strong');
            name.textContent = item.display_name || item.name || `${identity(item.pubkey).slice(0, 16)}…`;
            select.appendChild(name);
            if (approved(item.pubkey)) {
                const badge = document.createElement('span');
                badge.className = 'delegate-verified';
                badge.textContent = 'Verified';
                badge.title = 'Approved by NostrAds';
                select.appendChild(badge);
            }
            const description = document.createElement('span');
            description.textContent = item.about || 'No description published.';
            description.className = 'delegate-description';
            select.appendChild(description);
            const fees = document.createElement('span');
            fees.className = 'delegate-fees';
            fees.textContent = feeDescription(item);
            select.appendChild(fees);
            select.addEventListener('click', () => {
                input.value = item.pubkey;
                input.dispatchEvent(new Event('input', {bubbles: true}));
            });
            card.appendChild(select);
            const website = safeLinkUrl(item.website);
            if (website) {
                const link = document.createElement('a');
                link.className = 'delegate-website';
                link.href = website;
                link.target = '_blank';
                link.rel = 'noopener noreferrer';
                link.textContent = new URL(website).hostname;
                card.appendChild(link);
            }
            cards.appendChild(card);
        }
    }
    async function update() {
        const version = ++selection;
        render();
        warning.hidden = !input.value.trim() || [...verified].some(matches);
        if (!input.value.trim()) return;
        try {
            const pubkey = input.value.trim().toLowerCase();
            const key = identity(pubkey);
            if (candidates.has(key)) return;
            const profile = await reader.getNip01Meta(pubkey);
            if (!lifecycle.active || version !== selection) return;
            if (!Object.keys(profile).length) return;
            candidates.set(key, {...profile, pubkey});
            render();
        } catch (_) {}
    }
    input.addEventListener('input', update);
    input.addEventListener('change', update);
    update();
    status.textContent = 'Loading delegates…';
    loadDelegateDirectory().then(profiles => {
        if (!lifecycle.active) return;
        const otherMode = config.testmode ? config.default_delegate : config.testmode_config?.default_delegate;
        for (const profile of profiles) if (profile.pubkey !== otherMode) candidates.set(identity(profile.pubkey), profile);
        status.textContent = candidates.size ? '' : 'No delegates found on these relays. You can enter a public key below.';
        update();
    }).catch(() => {
        if (lifecycle.active) status.textContent = 'Could not load delegates. You can enter a public key below.';
    });
}
