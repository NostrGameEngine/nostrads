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


const spacesList = {};

async function prepareSpace(el, globalOptions, timeout) {
    return new Promise((resolve, reject) => {  
        requestAnimationFrame(async () => {
            try{
                const adspaceInput = await getInput(el, globalOptions);
                if(!adspaceInput) {
                    resolve();
                    return;
                }

                const props = {};
                const exists = !!spacesList[adspaceInput.uid];
                spacesList[adspaceInput.uid] = [el, adspaceInput, props];


    
                if (!exists){
                    // if new register it
                    await executor.invoke("registerAdspace", adspaceInput)
                }

                if (!timeout){
                    timeout=1500;
                } else {
                    timeout = Math.floor(Math.min(timeout*1.8,20000));
                }

                const  [ad, offerId] = await executor.invoke("loadAd", adspaceInput)
                props.offerId = offerId;
 
                Renderer.renderEvent(el, ad, async () => {
                    await executor.invoke("confirmAd", offerId);
                    timeout = 0;
                }, async (error) => {
                    console.error("Error rendering ad for element:", el, error);
                    await executor.invoke("cancelAd", offerId);
                    setTimeout(async() => {
                        await prepareSpace(el, globalOptions, timeout); // re-prepare the space
                    }, timeout);
                });
                resolve();
            } catch (e) {
                console.error("Error preparing ad space for element:", el, e);
                console.log("Retrying in ", timeout, "ms");
                setTimeout(async () => {
                    await prepareSpace(el, globalOptions, timeout); // re-prepare the space
                }, timeout);
            }
        });
     });
}

async function releaseSpace(el, globalOptions) {
    const adspaceInput = await getInput(el, globalOptions);
    if (adspaceInput == null) {
        return;
    }
    if (spacesList[adspaceInput.uid]) {
        delete spacesList[adspaceInput.uid];
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
                });
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

    
    if (!globalOptions.userKey) {
        globalOptions.userKey = await executor.invoke("generatePrivateKey");
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
    const observer = new MutationObserver((mutations) => {
        mutations.forEach(async (mutation) => {
            for (const node of mutation.addedNodes) {
                try {
                    if (node.nodeType !== Node.ELEMENT_NODE || !node.classList.contains('nostr-ddspace')) continue;
                    await prepareSpace(node, globalOptions);
                } catch (e) {
                    console.error("Error processing added node:", e);
                }
            }
            for (const node of mutation.removedNodes) {
                try {
                    if (node.nodeType !== Node.ELEMENT_NODE || !node.classList.contains('nostr-ddspace')) continue;
                    releaseSpace(node, globalOptions);
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

    element.querySelectorAll('.nostr-ddspace').forEach(async (el) => {
        try {
            prepareSpace(el, globalOptions);
        } catch (e) {
            console.error("Error loading ad for element:", el, e);
        }
    });
}

export default auto;

if (typeof module !== 'undefined' && module.exports) {
    // For Node.js or CommonJS environments
    module.exports = auto;
}
