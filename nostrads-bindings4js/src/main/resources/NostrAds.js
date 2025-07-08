import * as _Binds from './nostr-ads.js';

const { AdvertiserClientWrapper, DisplayClientWrapper, generatePrivateKey, getPublicKey } = _Binds;


const newAdvertiserClient = function (relays, auth, blossomEndpoints) {
    console.log("Creating new AdvertiserClient with relays:", relays, "and auth:", auth, "and blossomEndpoints:", blossomEndpoints);
    const ads = new AdvertiserClientWrapper(
        relays,
        auth,
        blossomEndpoints
    );
    return {
        close: () => ads.close(),
        publish: async (bid) => {
            return new Promise((resolve, reject) => {
                ads.publishNewBid(bid, (ev, error) => {
                    if (!error) {
                        resolve(ev);
                    } else {
                        reject(error);
                    }
                });
            });
        },
        cancel: async(eventId) =>{
            return new Promise((resolve, reject) => {
                ads.cancelBid(eventId, (err) => {
                    if (!err) {
                        resolve();
                    } else {
                        reject(err);
                    }
                });
            });
        },
        getPublicKey: async () => {
            return new Promise((resolve, reject) => {
                ads.getPublicKey((pubkey, error) => {
                    if (!error) {
                        resolve(pubkey);
                    } else {
                        reject(error);
                    }
                });
            });
        },
        uploadImage: async ({imageData,  mimeType}) => {
            return new Promise((resolve, reject) => {
                ads.uploadImage(imageData, mimeType,  (meta, error) => {
                    if (!error) {
                        resolve(meta);
                    } else {
                        reject(error);
                    }
                });
            }); 
        },
        deleteImage: async (hash) => {
            return new Promise((resolve, reject) => {
                ads.deleteImage(hash, (_,error) => {
                    if (!error) {
                        resolve();
                    } else {
                        reject(error);
                    }
                });
            });
        },
        getNip01Meta: async (pubkey) => {
            return new Promise((resolve, reject) => {
                ads.getNip01Meta(pubkey, (meta, error) => {
                    if (!error) {
                        resolve(meta);
                    } else {
                        reject(error);
                    }
                });
            });
        },
        list: async () => {
            return new Promise((resolve, reject) => {
                ads.listBids((bids, error) => {
                    if (!error) {
                        resolve(bids);
                    } else {
                        reject(error);
                    }
                });
            });
        }

    };
};



const newDisplayClient = function (relays, auth, onInvalidatedAd) {
    const ads = new DisplayClientWrapper(
        relays,
        auth,
        onInvalidatedAd
    );

    return {
        close: () => ads.close(),
        registerAdspace: async (adspaceInput) => {
            ads.registerAdspace(adspaceInput);
        },
        unregisterAdspace: async (adspaceInput) => {
            ads.unregisterAdspace(adspaceInput);
        },
        loadAd: async (adspaceInput, onShow) => {        
            return new Promise((resolve, reject) => {
                ads.loadAd(adspaceInput, (id ,bid,confirm,cancel)=>{
                    return onShow(id,bid,confirm,cancel);
                }, (msg)=>{
                    resolve();
                }, (error) => {
                    reject(error);
                });

            });
        }
    };
}


export default {
    newAdvertiserClient,
    newDisplayClient,
    getPublicKey,
    generatePrivateKey
};

if (typeof module !== 'undefined' && module.exports) {
    // For Node.js or CommonJS environments
    module.exports = {
        newAdvertiserClient,
        newDisplayClient,
        generatePrivateKey,
        getPublicKey
    };
}
