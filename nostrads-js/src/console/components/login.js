import NostrAds from '../nostr-ads.js';



async function login(view, { relays, blossomEndpoints }) {
    
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
            picture = "/console/imgs/default.png";
        }

        let name = nip01.name || nip01.display_name || nip01.nip05;
        if (!name) {
            name = pubkey.substring(0, 8) + "..." + pubkey.substring(pubkey.length - 8);
        }
        
        userEl.innerHTML = `
            <img src="${picture}" />
            <span>${name}</span>
        `;
        ads.close();

    }



}

export default login;