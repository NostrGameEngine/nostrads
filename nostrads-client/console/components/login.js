import Renderer from "../../ad-render.js";
import NostrAds from '../../nostr-ads.js';



async function login(view, {relays}) {
    const ads = NostrAds.newAdvertiserClient(relays, "nip07");
    const nip01 = await ads.getNip01Meta(await ads.getPublicKey());
    console.log("NIP-01 metadata:", nip01);
    const userEl = view.querySelector('#login');
    if (userEl != null) {
        userEl.innerHTML = `
            <img src="${nip01.picture}" />
            <span>${nip01.name || nip01.display_name || nip01.nip05 || ads.getPublicKey()}</span>
        `;
    }
    ads.close();



}

export default login;