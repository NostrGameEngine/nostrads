

import Renderer from "./ad-render.js";
import NostrAds from './nostr-ads.js';
import showSettingsDialog, { getRelays, getBlossomEndpoints, getImageOrigins } from "./components/options.js";
import showLogin from "./components/login.js";
import {getAds} from "./components/adstore.js";

 

async function showAdlist(view, {relays, blossomEndpoints}) {
    const client = NostrAds.newAdvertiserClient(relays, "nip07", blossomEndpoints);
    try {
        const savedAds = await getAds();

        const adListEl = view.querySelector('#ddlist');
        if (!adListEl) {
            console.error("No ad list element found");
            return;
        }

        const ads = await client.list();
        const imageOrigins = await getImageOrigins();
        console.log("Loading ad list:", ads);

        const render = (adListEl, ad, isLive) => {
            const tags = ad.tags || [];
            const size = tags.find(tag => tag[0] === 's')?.[1]?.split("x").map(Number);
            if (!size || size.length !== 2 || size.some(value => !Number.isFinite(value) || value <= 0)) return;

            const maxSize = window.innerWidth * 0.8;
            const width = Math.min(size[0], maxSize);
            const height = width * size[1] / size[0];

            const adEl = document.createElement('div');
            adEl.className = 'nostr-ddspace';
            adEl.style.width = `${width}px`;
            adEl.style.height = `${height}px`;
            console.log("Creating ad element with size:", width, height, "for ad:", ad);
            adListEl.appendChild(adEl);
            adEl.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                console.log("Ad clicked:", ad);
                const uriComponent = encodeURIComponent(JSON.stringify(ad));
                const url = new URL("./edit", window.location.href);
                url.searchParams.set('see', uriComponent);
                url.searchParams.set('isLive', isLive ? 'true' : 'false');
                window.location.href = url.toString();
            });
            Renderer.renderEvent(
                adEl,
                ad,
                () => { },
                () => { },
                {
                    noLink: true,
                    allowedImageOrigins: imageOrigins
                }
            );
        }

        for(const ad of ads) {

            // remove from savedAds if it exists
            for(let i = 0; i < savedAds.length; i++) {
                const savedAd = savedAds[i];
                if (savedAd.id === ad.id) {
                    savedAds.splice(i, 1);
                    break;
                }
            }

            render(adListEl, ad,true);
        }

        const stoppedAdListEl = view.querySelector('#stoppedAdlist');

        for(const ad of savedAds) {
            render(stoppedAdListEl, ad, false);
        }
    } finally {
        client.close();
    }
}

async function main() {
    const settingsBtn = document.querySelector('#settings');
    if (settingsBtn) {
        settingsBtn.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            showSettingsDialog(document.body);
        });
    }

    const createNewBtn = document.querySelector('#createNew');
    if (createNewBtn) {
        createNewBtn.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            const url = new URL("./edit", window.location.href);
            window.location.href = url.toString();
        });
    }

    const status = document.querySelector('#console-status');
    if (!window.nostr) return;
    status.textContent = 'Loading your campaigns…';
    try {
        await showLogin(document.body, {
            relays: await getRelays(),
            blossomEndpoints: await getBlossomEndpoints()
        });

        await showAdlist(document.body, {
            relays: await getRelays(),
            blossomEndpoints: await getBlossomEndpoints()
        });
        status.textContent = 'Campaigns loaded. Select an ad to inspect or update it.';
    } catch (_) {
        status.textContent = 'Could not load campaigns. Check your extension and relay settings, then reload.';
    }
}

window.addEventListener('load', () => {
    main();
});
