import * as _Binds from './nostr-ads.js';

const { NostrAds, newAdsKey } = _Binds;


const newAdvertiserClient = function (relays, appKey, adsKey) {
    const ads = new NostrAds(
        relays,
        appKey,
        adsKey
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



const newDisplayClient = function (relays, appKey, adsKey) {
    const ads = new NostrAds(
        relays,
        appKey,
        adsKey
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
            categories, // str[] (optional)
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
                categories,
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
    newAdsKey
};