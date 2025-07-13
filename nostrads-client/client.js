import SharedExecutorClient from './sharedexecutor/SharedExecutorClient.js';
import Renderer from './ad-render.js';

async function getId() {
    try {
        if (typeof indexedDB === 'undefined') {
            throw new Error('IndexedDB is not available');
        }
        // console.log("Using IndexedDB for counter");            
        return new Promise((resolve) => {
            const request = indexedDB.open("nostrads", 1);

            request.onupgradeneeded = (event) => {
                const db = event.target.result;
                if (!db.objectStoreNames.contains('counters')) {
                    db.createObjectStore('counters', { keyPath: 'id' });
                }
            };

            request.onsuccess = (event) => {
                const db = event.target.result;
                const transaction = db.transaction(['counters'], 'readwrite');
                const store = transaction.objectStore('counters');

                const countRequest = store.get('instanceCounter');
                countRequest.onsuccess = () => {
                    let counter = 1;
                    if (countRequest.result) {
                        counter = countRequest.result.value + 1;
                    }
                    store.put({id: "instanceCounter",
                         value: counter });
                    resolve(counter);
                };

                countRequest.onerror = () => {
                    resolve(Date.now());
                };
            };

            request.onerror = () => {
                resolve(Date.now());
            };
        });
    } catch (e) {
        console.warn("Using fallback for ID generation:", e);
        // Fallback if IndexedDB is not available
        return Date.now();
    }
}

async function loadAd(el, adspaceInput, ads, executor, options) {
    const disposer = [];
    // ads.registerAdspace(adspaceInput);
    requestAnimationFrame(async () => {
        await executor.invoke("registerAdspace", options.relays, options.adsKey, options.appKey, adspaceInput)
        adspaceInput.show = (bid, successCallback, errorCallback) => {
            Renderer.renderEvent(el, bid, successCallback, errorCallback);          
        };
        console.log("Loading ad for element:", el, "with input:", adspaceInput);
        await executor.invoke("loadAd", options.relays, options.adsKey, options.appKey, adspaceInput)

    });
    return disposer;
}

async function unloadAd(adspaceInput, ads, options) {
    await executor.invoke("unregisterAdspace", options.relays, options.adsKey, options.appKey, adspaceInput);
}


async function attributesToAdspaceInput(el, options) {
    const attrList = [
        "appKey", //str (required)
        "numBidsToLoad", //int (optional)
        "priceSlot", // str (optional)
        "mimeTypes", // csv (required)
        "category", // csv (optional)
        "languages", // csv (optional)
        "advertisersWhitelist" // csv (optional)
    ];
    const adspaceInput = {};
    for (const attr of attrList) {
        const value = el.getAttribute(attr);
        if (value !== null) {
            if (attr === "numBidsToLoad") {
                adspaceInput["nostrads-" + attr] = parseInt(value, 10);
            } else if (attr === "mimeTypes" || attr === "category" || attr === "languages" || attr === "advertisersWhitelist") {
                adspaceInput["nostrads-" + attr] = value.split(',').map(s => s.trim());
            } else {
                adspaceInput["nostrads-" + attr] = value;
            }
        }
    }

    // calculate width and height from the element visible dimensions
    const rect = el.getBoundingClientRect();
    adspaceInput.width = Math.floor(rect.width);
    adspaceInput.height = Math.floor(rect.height);
    if (!adspaceInput.width || !adspaceInput.height) {
        throw new Error("Adspace element must have a visible width and height");
    }

    // get uid
    let uid = el.getAttribute("nostrads-uid");
    if (!uid) {
        uid = (await getId()) + "-" + Date.now();
        el.setAttribute("nostrads-uid", uid);
    } else {
        uid = uid.trim();
    }

    adspaceInput.uid = uid;

    if (!adspaceInput.appKey) {
        adspaceInput.appKey = options?.appKey;
    }


    return adspaceInput;
}

let executor;
async function auto(options, element) {
    if (!options) options={};
    
    if(!executor){
        executor = new SharedExecutorClient(options.worker ?? 'worker.js', {
            type: 'module',
            forceCompat: options.forceCompatModeForWorker  // uncomment this if you want to force the compat mode even if the SharedWorker API is available (mostly for debug)
        });
    }
    
    if (!options.relays) {
        if (options.devMode) {
            options.relays = ["wss://nostr.rblb.it"];
        } else {
            options.relays = ["wss://relay.ngengine.org"];
        }
    }

    if (!options.adsKey) {
        options.adsKey = await executor.invoke("newAdsKey");
    }    
    
    if (element==null){
        return new Promise((resolve, reject) => {
            // when window is loaded or immediately if already loaded
            if (document.readyState === 'loading') {
                window.addEventListener("load", () => {
                    auto(options, document.body).then(resolve).catch(reject);
                });
                return;
            } else {
                auto(options, document.body).then(resolve).catch(reject);
            }
        });
    }
    const load = async (node, ads)=>{
        const adspaceInput = await attributesToAdspaceInput(node, options);
        loadAd(node, adspaceInput, ads, executor, options);
    }
    const unload = (node, ads)=>{
        const adspaceInput = attributesToAdspaceInput(node, options);
        unloadAd(adspaceInput, ads, executor, options);
    }
    const observer = new MutationObserver((mutations) => {
        mutations.forEach((mutation) => {
            for (const node of mutation.addedNodes) {
                try {
                    if (node.nodeType !== Node.ELEMENT_NODE || !node.classList.contains('nostr-adspace')) continue;
                    load(node, null);
                } catch (e) {
                    console.error("Error processing added node:", e);
                }
            }
            for (const node of mutation.removedNodes) {
                try {
                    if (node.nodeType !== Node.ELEMENT_NODE || !node.classList.contains('nostr-adspace')) continue;
                    unload(node, null);              
                } catch (e) {
                    console.error("Error processing removed node:", e);
                }
            }
        });
    });
    observer.observe(element, {
        childList: true,
        subtree: true
    });
    element.querySelectorAll('.nostr-adspace').forEach((el) => {
        try {
            load(el, null);
        } catch (e) {
            console.error("Error loading ad for element:", el, e);
        }
    });
}

export default auto;