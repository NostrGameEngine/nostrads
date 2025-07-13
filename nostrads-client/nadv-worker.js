import  SharedExecutor  from './sharedexecutor/SharedExecutor.js';
import NostrAds from './nostr-ads.js';
let displayClient;
let advClient;

const executor = new SharedExecutor(async (isMaster) => { 
    console.log("SharedExecutor initialized, isMaster:", isMaster);
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
executor.registerMethod('initDisplay', async (relays, auth) => {
    if (!auth) {
        auth = NostrAds.generatePrivateKey();
    }
    displayClient = await NostrAds.newDisplayClient(relays, auth);
});

executor.registerMethod('registerAdspace', async (adspaceInput) => {
    if (!displayClient) throw new Error("Display client not initialized. Call initDisplay first.");
    return displayClient.registerAdspace(adspaceInput);
}); 


executor.registerMethod('unregisterAdspace', async (adspaceInput) => {
    if (!displayClient) throw new Error("Display client not initialized. Call initDisplay first.");
    return displayClient.unregisterAdspace(adspaceInput);
}); 

executor.registerMethod('loadAd', async (adspaceInput) => {
    if (!displayClient) throw new Error("Display client not initialized. Call initDisplay first.");
    return displayClient.loadAd(adspaceInput);
}); 


// advertiser methods
executor.registerMethod('initAdvertiser', async (relays, auth) => {
    if (!auth) {
        auth = NostrAds.generatePrivateKey();
    }
    advClient = await NostrAds.newAdvertiserClient(relays, auth);
});

executor.registerMethod('advPublishBid', async (bidInput) => {
    if (!advClient) throw new Error("Advertiser client not initialized. Call initAdvertiser first.");         
    return advClient.publish(bidInput);
}); 

executor.registerMethod('advCancelBid', async ( bidId) => {
    if (!advClient) throw new Error("Advertiser client not initialized. Call initAdvertiser first.");         
    return advClient.cancel(bidId);
}); 

executor.registerMethod('advListBids', async () => {
    if (!advClient) throw new Error("Advertiser client not initialized. Call initAdvertiser first.");         
    return advClient.list();
}); 