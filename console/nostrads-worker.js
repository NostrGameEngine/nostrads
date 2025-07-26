import  SharedExecutor  from './sharedexecutor/SharedExecutor.js';
import NostrAds from './nostr-ads.js';
let displayClient;
let advClient;

const managedSpaces = {};
const offerCallbacks = {}; // to store callbacks for ad offers
let loopTimeout = null;
let isMaster = false;

const loop = async () => {
    for (const [k, v] of Object.entries(managedSpaces)) {
        if (v[2]) clearTimeout(v[2]); // clear previous timeout if exists
        v[1] = 0;
        executor.triggerCallback("ping");
        v[2] = setTimeout(() => { // if no ping response after 5 seconds release the adspace
            if (v[1] === 0) {
                console.log("No activity for adspace:", v[0].uid, "unregistering it.");
                displayClient.unregisterAdspace(v[0]);
                delete managedSpaces[k];
            } else {
                console.log("Adspace still active:", v[0].uid);
            }
        }, 5000);

    }
    if (!isMaster) return;
    loopTimeout = setTimeout(loop, 10000); // repeat every 10 seconds
};


const executor = new SharedExecutor(async (v) => { 
    isMaster= v;
    console.log("SharedExecutor initialized, isMaster:", isMaster);
    if(isMaster){
        loop();
    } else {
        clearInterval(loopTimeout); // clear the loop if not master
    }
});


executor.bindToClient();


// utility methods
executor.registerMethod('generatePrivateKey', () => {
    console.log("Creating new priv key");
    const v = NostrAds.generatePrivateKey();
    console.log("New priv Key created:", v);
    return v;
});

executor.registerMethod('getPublicKey', (priv) => {
    const v = NostrAds.getPublicKey(priv);
     return v;
});


// display methods
executor.registerMethod('initDisplay', async (globalOptions) => {
    let {relays, auth} = globalOptions;
    if (!displayClient){
        if (!auth) {
            auth = NostrAds.generatePrivateKey();
        }
        displayClient = await NostrAds.newDisplayClient(relays, auth, (offerId)=>{
            console.log("Ad was invalidated:", offerId);
            executor.triggerCallback("invalidateAd", offerId);
            delete offerCallbacks[offerId]; // remove the callback for this offer
            // for (const [uid, space] of Object.entries(managedSpaces)) {
            //     if ( space[4] === offerId) {
            //         console.log("Adspace invalidated:", uid);
            //         executor.triggerCallback("invalidateAd", uid);
            //         delete managedSpaces[uid]; // remove the adspace from managed spaces
            //     }
            // }
        });
        console.log("Display client initialized with relays:", relays );
    } else {
        console.log("Display client already initialized, relays:", relays );
    }
});


executor.registerMethod('pong', async (uid) => {
    managedSpaces[uid][1] += 1; // increment usage count
});

executor.registerMethod('registerAdspace', async (adspaceInput) => {
    if (!displayClient) throw new Error("Display client not initialized. Call initDisplay first.");
    if (!managedSpaces[adspaceInput.uid]){
        managedSpaces[adspaceInput.uid] = [adspaceInput, 0, null,]; // [adspaceInput, usageCount, timeoutId]
    }
    managedSpaces[adspaceInput.uid][1] += 1; // increment usage count
    return displayClient.registerAdspace(adspaceInput);
}); 


executor.registerMethod('unregisterAdspace', async (adspaceInput) => {
    if (!displayClient) throw new Error("Display client not initialized. Call initDisplay first.");
    return displayClient.unregisterAdspace(adspaceInput);
}); 

executor.registerMethod('loadAd', async (adspaceInput) => {
    if (!displayClient) throw new Error("Display client not initialized. Call initDisplay first.");
    // const registeredSpace = managedSpaces[adspaceInput.uid];
    // if(!registeredSpace)  throw new Error(`Adspace with uid ${adspaceInput.uid} is not registered.`);
    return new Promise((resolve, reject) => {
         displayClient.loadAd( adspaceInput ,
             (offerId, bid, successCallback, errorCallback) => {
                 offerCallbacks[offerId] = (v) => {
                     delete offerCallbacks[offerId]; // remove the callback after use
                     if (v) {
                         console.log("Ad was confirmed:", v);
                         console.log(successCallback, errorCallback);
                         successCallback();
                     } else {
                         console.error("Ad was cancelled or failed to load.");
                         errorCallback();
                     }

                 }
                 resolve([bid, offerId]);

             }).catch((error) => {
                console.error("Error loading ad:", error);
                reject(error);
            });
        })
 }); 

executor.registerMethod('confirmAd', async (offerId) => {
    console.log("Confirming ad offer:", offerId,offerCallbacks);
    offerCallbacks[offerId] (true);
});

executor.registerMethod('cancelAd', async (offerId) => {
    offerCallbacks[offerId] (false);
});

