

import Renderer from "../ad-render.js";
import NostrAds from '../nostr-ads.js';
import showSettingsDialog, {getRelays} from "./components/options.js";
import showLogin from "./components/login.js";
import {getAds} from "./components/adstore.js";

 

async function showAdlist(view, {relays}) {
    const client = NostrAds.newAdvertiserClient(relays, "nip07");
    const savedAds = getAds();

    const adListEl = view.querySelector('#adlist');
    if (!adListEl) {
        console.error("No ad list element found");
        return;
    }

    const ads = await client.list();
    console.log("Loading ad list:", ads);

    const renderAd = (adListEl, ad, isLive) => {
        const tags = ad.tags || [];
        const size = tags.find(tag => tag[0] === 's')[1].split("x").map(s => parseInt(s, 10));
        const ratio = tags.find(tag => tag[0] === 'S')[1].split(":").map(s => parseFloat(s)).reduce((a, b) => a / b);

        const maxSize = window.innerWidth * 0.8;
        const width = Math.min(size[0], maxSize);
        const height = Math.min(size[1], maxSize * ratio);

        const adEl = document.createElement('div');
        adEl.className = 'nostr-adspace';
        adEl.style.width = `${width}px`;
        adEl.style.height = `${height}px`;
        console.log("Creating ad element with size:", width, height, "for ad:", ad);
        adListEl.appendChild(adEl);
        adEl.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            console.log("Ad clicked:", ad);
            const uriComponent = encodeURIComponent(JSON.stringify(ad));
            const url = new URL("new.html", window.location.href);
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
                noLink: true
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

        renderAd(adListEl, ad,true);
    }

    const stoppedAdListEl = view.querySelector('#stoppedAdlist');

    for(const ad of savedAds) {
        renderAd(stoppedAdListEl, ad, false);
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

    showLogin(document.body, {
        relays: getRelays()
    });

    showAdlist(document.body, {
        relays: getRelays()
    });
}

window.addEventListener('load', () => {
    main();
});