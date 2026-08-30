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
 



async function getInput(el, globalOptions) {
    const attrList = [
        "appKey", //str (required)
        "priceSlot", // str (optional)
        "mimeTypes", // csv (required)
        "category", // csv (optional)
        "languages", // csv (optional)
        "advertisersWhitelist" // csv (optional)
    ];

    const adspaceInput = {};
    for (const attr of attrList) {
        const value = el.getAttribute("nostrads-"+attr);
        if (value !== null) {
            if (attr === "mimeTypes" || attr === "category" || attr === "languages" || attr === "advertisersWhitelist") {
                adspaceInput[attr] = value.split(',').map(s => s.trim());
            } else {
                adspaceInput[attr] = value;
            }
        }
    }

    // calculate width and height from the element visible dimensions
    const rect = el.getBoundingClientRect();
    adspaceInput.width = Math.floor(rect.width);
    adspaceInput.height = Math.floor(rect.height);
    if (!adspaceInput.width || !adspaceInput.height) {
        return null;
    }

    // get uid
    let uid = el.getAttribute("nostrads-uid");
    if (!uid) {
        uid = (await getId()) + "-" + Date.now();
        el.setAttribute("nostrads-uid", uid);
    } else {
        uid = uid.trim();
    }
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(uid)) {
        throw new Error("Invalid nostrads-uid");
    }
    adspaceInput.uid = uid;

    if (!adspaceInput.appKey) {
        adspaceInput.appKey = globalOptions?.appKey;
    }

    
    if(!adspaceInput.priceSlot){
        adspaceInput.priceSlot = globalOptions.priceSlot ?? "BTC1_000";
    }

    if(!adspaceInput.category){
        adspaceInput.category = globalOptions.category ?? [];
    }

    if(!adspaceInput.languages){
        adspaceInput.languages = globalOptions.languages ?? [];
    }

    if (!adspaceInput.advertisersWhitelist){
        adspaceInput.advertisersWhitelist = globalOptions.advertisersWhitelist ?? [];
    }
    
    if(!adspaceInput.mimeTypes || adspaceInput.mimeTypes.length === 0){
        adspaceInput.mimeTypes = globalOptions.mimeTypes ?? ["image/gif", "image/png", "image/jpeg", "text/plain"];
    }
  
    return adspaceInput;
}


const spacesList = Object.create(null);
let spaceGeneration = 0;

async function prepareSpace(el, globalOptions, timeout) {
    await new Promise(resolve => requestAnimationFrame(resolve));
    const retryDelay = timeout ? Math.floor(Math.min(timeout * 1.8, 20000)) : 1500;
    let props = null;
    try {
        const adspaceInput = await getInput(el, globalOptions);
        if (!adspaceInput || !el.isConnected) return;

        const generation = ++spaceGeneration;
        const previous = spacesList[adspaceInput.uid];
        const exists = !!previous;
        previous?.[2]?.dispose?.();
        props = {
            generation,
            offerId: null,
            retryTimer: null,
            renderDisposers: [],
            disposed: false,
            dispose() {
                if (this.disposed) return;
                this.disposed = true;
                if (this.retryTimer !== null) clearTimeout(this.retryTimer);
                this.renderDisposers.forEach(dispose => dispose());
                this.renderDisposers = [];
                if (this.offerId) executor.invoke("cancelAd", this.offerId).catch(() => {});
            }
        };
        spacesList[adspaceInput.uid] = [el, adspaceInput, props];
        const isCurrent = () => spacesList[adspaceInput.uid]?.[2] === props && !props.disposed;
        const retry = () => {
            if (!isCurrent() || props.retryTimer !== null) return;
            props.retryTimer = setTimeout(() => {
                props.retryTimer = null;
                if (isCurrent()) prepareSpace(el, globalOptions, retryDelay);
            }, retryDelay);
        };

        if (!exists) await executor.invoke("registerAdspace", adspaceInput);
        if (!isCurrent()) return;

        const [ad, offerId] = await executor.invoke("loadAd", adspaceInput);
        if (!isCurrent()) {
            await executor.invoke("cancelAd", offerId);
            return;
        }
        props.offerId = offerId;
        props.renderDisposers = Renderer.renderEvent(el, ad, async () => {
            if (!isCurrent() || props.offerId !== offerId) return;
            const confirmed = await executor.invoke("confirmAd", offerId);
            if (confirmed !== true) throw new Error("Worker did not confirm the ad offer");
            props.offerId = null;
        }, async (error) => {
            if (!isCurrent() || props.offerId !== offerId) return;
            console.error("Error rendering ad for element:", el, error);
            const cancelled = await executor.invoke("cancelAd", offerId);
            props.offerId = null;
            retry();
            if (cancelled !== true) throw new Error("Worker did not cancel the ad offer");
        }, {
            allowedImageOrigins: globalOptions.allowedImageOrigins ?? []
        });
    } catch (e) {
        console.error("Error preparing ad space for element:", el, e);
        if (props && !props.disposed) {
            props.retryTimer = setTimeout(() => {
                props.retryTimer = null;
                if (!props.disposed) prepareSpace(el, globalOptions, retryDelay);
            }, retryDelay);
        }
    }
}

async function releaseSpace(el, globalOptions) {
    const uid = el.getAttribute("nostrads-uid")?.trim();
    const entry = uid ? spacesList[uid] : null;
    if (!entry) return;
    const adspaceInput = entry[1];
    entry[2]?.dispose?.();
    delete spacesList[uid];
    try {
        await executor.invoke("unregisterAdspace", adspaceInput);
    } catch (e) {
        console.error("Error unregistering adspace for element:", el, e);
    }

}

async function onPing(){
    for(const [el,adspaceInput] of Object.values(spacesList)){
        try {
            await executor.invoke("pong", adspaceInput.uid);
        } catch (e) {
            console.error("Error pinging adspace for element:", el, e);
        }
    }
}

async function onInvalidatedAd(offerId, globalOptions ){

    for (const [uid, [el, adspaceInput, props]] of Object.entries(spacesList)) {
        if (props.offerId === offerId) {
            await prepareSpace(el, globalOptions);
            break;
        } 
    }


}

let executor;
async function auto(globalOptions, element) {
    // rerun method if not ready yet
    if (element == null) {
        return new Promise((resolve, reject) => {
            // when window is loaded or immediately if already loaded
            if (document.readyState === 'loading') {
                window.addEventListener("load", () => {
                    auto(globalOptions, document.body).then(resolve).catch(reject);
                }, {once: true});
                return;
            } else {
                auto(globalOptions, document.body).then(resolve).catch(reject);
            }
        });
    }


    // load default global options
    if (!globalOptions) globalOptions={};
    

    if (!globalOptions.appKey) {
        throw new Error("App key is required. Please provide a valid appKey in globalOptions.");
    }
    
    if (!globalOptions.relays) {
        if (globalOptions.devMode) {
            globalOptions.relays = ["wss://nostr.rblb.it"];
        } else {
            globalOptions.relays = ["wss://relay.ngengine.org",
                "wss://relay2.ngengine.org",
                "wss://relay.damus.io",
                "wss://relay.primal.net",
                "wss://relay.nostr.band"];
        }
    }

    // initialize worker
    let initialize = false;
    if (!executor) {
        executor = new SharedExecutorClient(globalOptions.worker ?? 'nostrads-worker.js', {
            type: 'module',
            forceCompat: globalOptions.forceCompatModeForWorker  // uncomment this if you want to force the compat mode even if the SharedWorker API is available (mostly for debug)
        });
       
        initialize = true;
    }

    
    if(initialize){
        executor.registerCallback("invalidateAd", (uid) => {
            onInvalidatedAd(uid, globalOptions);
        });
        executor.registerCallback("ping", () => {
            onPing();
        });
        await executor.invoke("initDisplay", globalOptions);
     }

    // load and unload ads for existing elements
    const slotsInNode = (node) => {
        if (node.nodeType !== Node.ELEMENT_NODE) return [];
        const slots = [];
        if (node.matches('.nostr-ddspace')) slots.push(node);
        slots.push(...node.querySelectorAll('.nostr-ddspace'));
        return slots;
    };
    const observer = new MutationObserver((mutations) => {
        mutations.forEach(async (mutation) => {
            for (const node of mutation.addedNodes) {
                for (const slot of slotsInNode(node)) {
                    try {
                        await prepareSpace(slot, globalOptions);
                    } catch (e) {
                        console.error("Error processing added node:", e);
                    }
                }
            }
            for (const node of mutation.removedNodes) {
                for (const slot of slotsInNode(node)) {
                    try {
                        await releaseSpace(slot, globalOptions);
                    } catch (e) {
                        console.error("Error processing removed node:", e);
                    }
                }
            }
        });
    });
    
    observer.observe(element, {
        childList: true,
        subtree: true
    });

    await Promise.all(Array.from(element.querySelectorAll('.nostr-ddspace')).map(async (el) => {
        try {
            await prepareSpace(el, globalOptions);
        } catch (e) {
            console.error("Error loading ad for element:", el, e);
        }
    }));

    return async () => {
        observer.disconnect();
        await Promise.all(Array.from(element.querySelectorAll('.nostr-ddspace')).map(el => releaseSpace(el, globalOptions)));
    };
}

export default auto;

if (typeof module !== 'undefined' && module.exports) {
    // For Node.js or CommonJS environments
    module.exports = auto;
}
