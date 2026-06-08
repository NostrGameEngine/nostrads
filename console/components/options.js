
let config = undefined;
export async function getConfig() {
    if (config) return config;
    config = await fetch('/console/config.json').then(r => r.json());
    console.log("Loaded config:", config);
    return config;
}

let _localStorageKeyBase = null;
export async function localStorageKey(unit) {
    if (!_localStorageKeyBase) {
        const config = await getConfig();
        let key = 'nostrads';
        if(config?.testmode){
            key += "-test";
        }
        if (window?.nostr) {
            const pubkey = await window.nostr.getPublicKey();
            key += `-${pubkey}`;
        }
        _localStorageKeyBase = key;
    }
    return _localStorageKeyBase + "-" + unit;
}


export async function getRelays() {
    let relays = ["wss://relay.ngengine.org", "wss://relay2.ngengine.org"];
    try {
        const v = JSON.parse(localStorage.getItem(await localStorageKey("relays")));
        if (!v || !Array.isArray(v) || v.length === 0) {
            throw new Error("No relays found in localStorage");
        }
        relays = v;
        return relays;

    } catch (e) {
        console.log("Error parsing relays from localStorage:", e);
    }


    try {
        const config = await getConfig();

        try {
            if (config?.default_relays) {
                const v = config.default_relays;
                if (v.length === 0) {
                    throw new Error("No relays found in config");
                }
                relays = v;
            }
        } catch (e) {
            console.log("Error loading relays from config:", e);
        }


        try {

            if (config?.testmode && config?.testmode_config?.default_relays) {
                const v = config?.testmode_config?.default_relays;
                if (v.length === 0) {
                    throw new Error("No relays found in config");
                }
                relays = v;
            }
        } catch (e) {
            console.log("Error loading relays from config:", e);
        }


    } catch (e) {
        console.error("Error loading config:", e);
    }

    return relays;
}

export async function getBlossomEndpoints() {
    let relays = ["https://blossom.primal.net", "https://blossom.band"];
    try {
        const v = JSON.parse(localStorage.getItem(await localStorageKey("blossom")).trim());
        if (!v || !Array.isArray(v) || v.length === 0) {
            throw new Error("No relays found in localStorage");
        }
        relays = v;
        return relays;
    } catch (e) {
        console.log("Error parsing relays from localStorage:", e);
    }

 
    try {
        const config = await getConfig();

        try {
            if (config?.default_blossom_relays) {
                const v = config.default_blossom_relays;
                if (v.length === 0) {
                    throw new Error("No relays found in config");
                }
                relays = v;
            }
        } catch (e) {
            console.log("Error loading relays from config:", e);
        }


        try {
            if (config?.testmode && config?.testmode_config?.default_blossom_relays) {
                const v = config?.testmode_config?.default_blossom_relays;
                if (v.length === 0) {
                    throw new Error("No relays found in config");
                }
                relays = v;
            }
        } catch (e) {
            console.log("Error loading relays from config:", e);
        }


    } catch (e) {
        console.error("Error loading config:", e);
    }

    return relays;
}




export default async function showSettingsDialog(view, options) {
    let dialog = view.querySelector('#settings-dialog');
    if (!dialog) {
        dialog = document.createElement('dialog');
        dialog.id = 'settings-dialog';
        dialog.innerHTML = `
        <h1>Settings</h1>
            <form method="dialog">
            <div>
                <h3>Relayset</h2>
                <p>Enter a list of nostr relays, one per line:</p>
                <textarea id="relay-set-textarea" rows="10" cols="50"></textarea>
            </div>
            <div>

                <h3>Blossom relayset</h2>
                <p>Enter a list blossom relays, one per line:</p>
                <textarea id="blossom-set-textarea" rows="10" cols="50"></textarea>
        </div>
            </form>
             <div class="dialog-buttons">
                    <button type="submit">Save</button>
                    <button type="reset">Cancel</button>
                </div>
        `;
        const submitButton = dialog.querySelector('button[type="submit"]');
        submitButton.addEventListener('click', async (e) => {
            e.preventDefault();
            try {
                const textarea = dialog.querySelector('#relay-set-textarea');
                const relays = textarea.value.split('\n').map(line => line.trim()).filter(line => line);
                console.log("Saving relays:", relays);
                localStorage.setItem(
                    await localStorageKey("relays")
                    , JSON.stringify(relays));
            } catch (e) {
                console.error("Error saving relays:", e);
                alert("Error saving relays: " + e.message);
                return;
            }
            try {
                const textarea = dialog.querySelector('#blossom-set-textarea');
                const blossomEndpoints = textarea.value.split('\n').map(line => line.trim()).filter(line => line);
                console.log("Saving blossom endpoints:", blossomEndpoints);
                localStorage.setItem(
                    await localStorageKey("blossom")
                    , JSON.stringify(blossomEndpoints));
            } catch (e) {
                console.error("Error saving blossom endpoints:", e);
                alert("Error saving blossom endpoints: " + e.message);
                return;
            }
            dialog.close();
            window.location.reload();
        });
        const resetButton = dialog.querySelector('button[type="reset"]');
        resetButton.addEventListener('click', (e) => {
            e.preventDefault();
            dialog.close();

        });
        dialog.addEventListener('close', () => {
            dialog.remove();
        });
        view.appendChild(dialog);

    }
    dialog.querySelector('#relay-set-textarea').value = (await getRelays()).join('\n');
    dialog.querySelector('#blossom-set-textarea').value = (await getBlossomEndpoints()).join('\n');
    dialog.showModal();
}

