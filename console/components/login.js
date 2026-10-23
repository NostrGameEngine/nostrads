import NostrAds from '../nostr-ads.js';
import {safeImageUrl} from '../ad-render.js';



async function login(view, { relays, blossomEndpoints }) {
    if(typeof window.nostr === "undefined"){
        const user = view.querySelector('#login');
        if (user) user.textContent = 'Connect a Nostr browser extension (NIP-07) to manage your campaigns.';
        return;
    }
    
    const userEl = view.querySelector('#login');
    if (userEl != null) {
        const ads = NostrAds.newAdvertiserClient(relays, "nip07", blossomEndpoints);
        let nip01 = {};
        let pubkey = "??";

        try{
            pubkey = await ads.getPublicKey();
            nip01 = await ads.getNip01Meta(pubkey);
        } catch(e){
            console.error("Error getting NIP-01 metadata:", e);
            
        }

        console.log("NIP-01 metadata:", nip01);
        let picture = nip01.picture;
        if(!picture){
            picture = new URL('../imgs/default.png', import.meta.url).href;
        }

        let name = nip01.name || nip01.display_name || nip01.nip05;
        if (!name) {
            name = pubkey.substring(0, 8) + "..." + pubkey.substring(pubkey.length - 8);
        }
        
        userEl.textContent = '';
        const pictureEl = document.createElement('img');
        pictureEl.src = safeImageUrl(picture) || new URL('../imgs/default.png', import.meta.url).href;
        pictureEl.alt = '';
        const nameEl = document.createElement('span');
        nameEl.textContent = name;
        userEl.append(pictureEl, nameEl);
        ads.close();

    }



}

export default login;
