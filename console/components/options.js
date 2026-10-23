import NostrAds from '../nostr-ads.js';
let config = undefined;
export async function getConfig() {
    if (config) return config;
    config = await fetch(new URL('../config.json', import.meta.url)).then(r => {
        if (!r.ok) throw new Error('Unable to load console configuration');
        return r.json();
    });
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


function isEndpointList(value) {
    return Array.isArray(value) && value.length > 0 && value.every(item => typeof item === 'string' && item.trim());
}

async function getEndpoints(unit, configField, fallback) {
    try {
        const saved = JSON.parse(localStorage.getItem(await localStorageKey(unit)));
        if (isEndpointList(saved)) return saved;
    } catch (_) {
        // Missing or outdated local settings use the configured defaults.
    }
    try {
        const config = await getConfig();
        const defaults = (config.testmode && config.testmode_config?.[configField]) || config[configField];
        if (isEndpointList(defaults)) return defaults;
    } catch (error) {
        console.warn('Could not load network settings; using built-in defaults.', error);
    }
    return fallback;
}

export async function getRelays() {
    return getEndpoints('relays', 'default_relays', [
        'wss://relay.ngengine.org', 'wss://relay2.ngengine.org', 'wss://nostr.rblb.it',
        'wss://relay.primal.net', 'wss://nos.lol'
    ]);
}

export async function getBlossomEndpoints() {
    return getEndpoints('blossom', 'default_blossom_relays', [
        'https://blossom.band', 'https://blossom.azzamo.media', 'https://24242.io'
    ]);
}

export async function getImageOrigins() {
    const endpoints = await getBlossomEndpoints();
    const origins = endpoints.map(endpoint => new URL(endpoint).origin);
    const config = await getConfig();
    for (const origin of [...origins]) {
        for (const cdn of config.blossom_image_origins?.[origin] ?? []) {
            origins.push(new URL(cdn).origin);
        }
    }
    if (window.nostr && origins.includes('https://blossom.band')) {
        const npub = NostrAds.publicKeyToBech32(await window.nostr.getPublicKey());
        origins.push(`https://${npub}.blossom.band`);
    }
    return [...new Set(origins)];
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
                <h3>Nostr relays</h3>
                <p>Enter a list of nostr relays, one per line:</p>
                <textarea id="relay-set-textarea" rows="10" cols="50"></textarea>
            </div>
            <div>

                <h3>Blossom servers</h3>
                <p>Enter upload servers, one per line. Uploads are authorized with your Nostr key:</p>
                <textarea id="blossom-set-textarea" rows="10" cols="50"></textarea>
        </div>
            </form>
             <div class="dialog-buttons">
                    <button type="submit">Save</button>
                    <button type="button" id="restore-defaults">Restore defaults</button>
                    <button type="reset">Cancel</button>
                </div>
        `;
        dialog.querySelector('#restore-defaults').addEventListener('click', async () => {
            const config = await getConfig();
            const defaults = config.testmode ? {...config, ...config.testmode_config} : config;
            dialog.querySelector('#relay-set-textarea').value = defaults.default_relays.join('\n');
            dialog.querySelector('#blossom-set-textarea').value = defaults.default_blossom_relays.join('\n');
        });
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

