import * as _Binds from './nostr-ads.js';

const { NostrAds, generatePrivateKey, getPublicKey } = _Binds;


const newAdvertiserClient = function (relays,  auth) {
    const ads = new NostrAds(
        relays,
        auth
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



const newDisplayClient = function (relays, auth) {
    const ads = new NostrAds(
        relays,
        auth
    );

    return {
        close: () => ads.close(),
        registerAdspace: async (adspaceInput) => {
            ads.registerAdspace(adspaceInput);
        },
        unregisterAdspace: async (adspaceInput) => {
            ads.unregisterAdspace(adspaceInput);
        },
        loadAd: async ({
            appKey, //str (required)
            width, //int (required)
            height, //int (required)
            numBidsToLoad, //int (optional)
            priceSlot, // str (optional)
            mimeTypes, // str[] (required)
            category, // str[] (optional)
            show, // (bid /*obj*/, successCallback /*fn*/, errorCallback /*fn*/) => void (required),
            bidFilter, // (bid /*obj*/, callback /*fn*/) => void (optional),
            languages, // str[] (optional)
            advertisersWhitelist, // str[] (optional)
        }) => {
            ads.loadAd({
                appKey,
                width,
                height,
                numBidsToLoad,
                priceSlot,
                mimeTypes,
                category,
                show,
                bidFilter,
                languages,
                advertisersWhitelist
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
