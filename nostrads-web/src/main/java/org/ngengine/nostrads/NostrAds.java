/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.ngengine.nostrads;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip01.Nip01;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.signer.NostrNIP07Signer;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.client.advertiser.AdvertiserClient;
import org.ngengine.nostrads.client.services.PenaltyStorage;
import org.ngengine.nostrads.client.services.display.AdsDisplayClient;
import org.ngengine.nostrads.client.services.display.Adspace;
import org.ngengine.nostrads.protocol.types.AdActionType;
import org.ngengine.nostrads.protocol.types.AdMimeType;
import org.ngengine.nostrads.protocol.types.AdPriceSlot;
import org.ngengine.nostrads.protocol.types.AdSize;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.nostrads.types.BidCallback;
import org.ngengine.nostrads.types.BidCancelCallback;
import org.ngengine.nostrads.types.BidFilterCallback;
import org.ngengine.nostrads.types.BidsCallback;
import org.ngengine.nostrads.types.Nip01Callback;
import org.ngengine.nostrads.types.PublicKeyCallback;
import org.ngengine.nostrads.types.RejectCallback;
import org.ngengine.nostrads.types.ShowCallback;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.VStore;
import org.ngengine.platform.teavm.TeaVMJsConverter;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSFunction;
import org.teavm.jso.impl.JS;

public class NostrAds {

    private static final Logger logger = Logger.getLogger(NostrAds.class.getName());

    private final NostrPool pool;
    private final AdTaxonomy taxonomy;
    private final NostrSigner signer;

    private AdvertiserClient advClient;
    private AdsDisplayClient displayClient;
    private PenaltyStorage penaltyStore;

    @JSExport
    public synchronized void close() {
        NostrAdsModule.initPlatform();
        pool.close();
    }

    @JSExport
    public NostrAds(
        String[] relays, 
        String auth
    ) throws Exception {
        NostrAdsModule.initPlatform();
        pool = new NostrPool();
        for (int i = 0; i < relays.length; i++) {
            System.out.println("Connecting to relay: " + relays[i]);
            pool.connectRelay(new NostrRelay(relays[i]));
        }

        System.out.println("Connected to relays: " + pool.getRelays().size());

        signer=getSigner(auth);

        System.out.println("Loading taxonomy...");
        taxonomy = new AdTaxonomy();
        System.out.println("Taxonomy loaded");

        System.out.println("Nostr Ads is ready!");
    }

    private NostrPublicKey pubkeyFromString(String pubkeyStr) {
        if (pubkeyStr.startsWith("npub")) {
            return NostrPublicKey.fromBech32(pubkeyStr);
        } else {
            return NostrPublicKey.fromHex(pubkeyStr);
        }
    }

    private NostrSigner getSigner(String signerProps) throws Exception{
        if(signerProps.equals("nip07")){
            NostrNIP07Signer signer = new NostrNIP07Signer();
            boolean v = signer.isAvailable().await();
            System.out.println("Using NIP-07 signer "+v);
            if(!v){
                System.out.println("NIP-07 signer is not available");
                throw new Exception("NIP-07 signer is not available. Please ensure you have a Nostr wallet extension installed and enabled.");
            }
            return signer;
        }else{
            NostrPrivateKey adsKeyN=null;
            if(signerProps==null|| signerProps.isEmpty()) {
                adsKeyN = NostrPrivateKey.generate();
            } else {
                adsKeyN = signerProps.startsWith("nsec")?NostrPrivateKey.fromBech32(signerProps):NostrPrivateKey.fromHex(signerProps);
            }
            System.out.println("App configured");
            return new NostrKeyPairSigner(new NostrKeyPair(adsKeyN));
        }
    }

    @JSExport
    public void getPublicKey(PublicKeyCallback callback) throws Exception{
        synchronized(this){
            NostrAdsModule.initPlatform();
            signer.getPublicKey().then(pkey->{
                callback.accept(pkey.asHex(),null);
                return null;
            }).catchException(err->{
                logger.log(Level.SEVERE, "Error getting public key "+ err);
                callback.accept(null, err.toString());
            });
        }
     }

     @JSExport 
     public void getNip01Meta(String pubkey,Nip01Callback callback){
        synchronized(this){
            NostrAdsModule.initPlatform();
            NostrPublicKey key = pubkeyFromString(pubkey);
            Nip01.fetch(pool, key)
                .then(meta -> {
                    JSObject metaObj = TeaVMJsConverter.toJSObject(meta.metadata);
                    callback.accept(metaObj, null);
                    return null;
                })
                .catchException(err -> {
                    logger.log(Level.SEVERE, "Error fetching NIP-01 metadata", err);
                    callback.accept(null, err.getMessage());
                });
        }
     }

    @JSExport
    public void publishNewBid( BidInputObject bid, BidCallback callback) throws Exception {
        synchronized (this) {
            NostrAdsModule.initPlatform();

            // Get required string properties
            String description = Objects.requireNonNull(bid.getDescription(), "Description is required");
            String mimeType = Objects.requireNonNull(bid.getMimeType(), "Mime type is required");
            String payload = Objects.requireNonNull(bid.getPayload(), "Payload is required");
            String size = Objects.requireNonNull(bid.getSize(), "Size is required");
            String link = Objects.requireNonNull(bid.getLink(), "Link is required");
            String actionType = Objects.requireNonNull(bid.getActionType(), "Action type is required");
            String context = bid.getContext();

            // Get optional string properties
            String callToAction = bid.getCallToAction();
            String delegate = Objects.requireNonNull(bid.getDelegate(), "Delegate is required");

            String nwcUrl = Objects.requireNonNull(bid.getNwc(), "NWC URL is required");
            Number budgetMsats = Objects.requireNonNull(bid.getBudget(), "Budget in msats is required");

            // Get required numeric properties
            long bidMsats = (long) bid.getBid();

            // Get optional numeric property with default
            double expireAtValue = bid.getExpireAt();
            long expireAt = Double.isNaN(expireAtValue) ? -1 : (long) expireAtValue;

            // Get array properties
            String[] categories = bid.getCategory() !=null ? bid.getCategory() : null;
            String[] languages = bid.getLanguages() != null ? bid.getLanguages() : null;
            String[] offerersWhitelist = bid.getOfferersWhitelist() != null ? bid.getOfferersWhitelist() : null;
            String[] appsWhitelist = bid.getAppsWhitelist() != null ? bid.getAppsWhitelist() : null;

            // Process categories
            List<AdTaxonomy.Term> categoriesList = categories != null
                ? Arrays.stream(categories).map(t -> taxonomy.getByPath(t)).toList()
                : null;

            // Process languages
            List<String> languagesList = languages != null ? Arrays.asList(languages) : null;

            // Process whitelist arrays
            List<NostrPublicKey> offerersWhitelistList = offerersWhitelist != null
                ? Arrays.stream(offerersWhitelist).map(this::pubkeyFromString).toList()
                : null;

            List<NostrPublicKey> appsWhitelistList = appsWhitelist != null
                ? Arrays.stream(appsWhitelist).map(this::pubkeyFromString).toList()
                : null;

            // Convert enums
            AdMimeType mimeTypeEnum = AdMimeType.fromString(mimeType);
            AdSize sizeEnum = Objects.requireNonNull(AdSize.fromString(size), "Invalid size: " + size);
            AdActionType actionTypeEnum = AdActionType.fromValue(actionType);

            // Create objects
            Duration holdTimeDuration = Duration.ofSeconds(60);
            NostrPublicKey delegatePublicKey = delegate != null ? pubkeyFromString(delegate) : null;
            Instant expireAtInstant = expireAt > 0 ? Instant.ofEpochMilli(expireAt) : null;

            if (advClient == null) {
                advClient = new AdvertiserClient(pool, signer, taxonomy);
            }

            Map<String,Object> delegatePayload = new HashMap<>();
            delegatePayload.put("nwc", nwcUrl);
            delegatePayload.put("budget", budgetMsats.intValue());

         
            // Call client
            advClient
                .newBid(
                    null,
                    description,
                    context,
                    categoriesList,
                    languagesList,
                    offerersWhitelistList,
                    appsWhitelistList,
                    mimeTypeEnum,
                    payload,
                    sizeEnum,
                    link,
                    callToAction,
                    actionTypeEnum,
                    bidMsats,
                    holdTimeDuration,
                    delegatePublicKey,
                    delegatePayload,
                    expireAtInstant
                )
                .then(bidEvent -> {
                    advClient.publishBid(bidEvent);
                    callback.accept(TeaVMJsConverter.toJSObject(bidEvent.toMap()), null);
                    return null;
                })
                .catchException(err -> {
                    logger.log(Level.SEVERE, "Error publishing bid" + err.getCause());
                    callback.accept(null, err.getMessage());
                });
        }
    }

    @JSExport
    public void listBids(BidsCallback callback) throws Exception {
        synchronized (this) {
            NostrAdsModule.initPlatform();

            if (advClient == null) {
                advClient = new AdvertiserClient(pool, signer, taxonomy);
            }

            advClient
                .listBids()
                .then(l -> {
                    JSObject bids[] = new JSObject[l.size()];
                    for (int i = 0; i < l.size(); i++) {
                        SignedNostrEvent ev = l.get(i);
                        bids[i] = TeaVMJsConverter.toJSObject(ev.toMap());
                    }
                    callback.accept(bids, null);
                    return null;
                })
                .catchException(err -> {
                    logger.log(Level.SEVERE, "Error listing bids", err);
                    callback.accept(null, err.getMessage());
                });
        }
    }

    @JSExport
    public void cancelBid(String eventId, BidCancelCallback callback  ) throws Exception {
        synchronized (this) {
            NostrAdsModule.initPlatform();
            if (advClient == null) {
                advClient = new AdvertiserClient(pool, signer, taxonomy);
            }
            advClient
                .cancelBid( eventId, "cancelled")
                .then(r->{
                    callback.accept(null);
                    return null;
                })
                .catchException(ex -> {
                    logger.log(Level.SEVERE, "Error cancelling bid", ex);
                    callback.accept(ex.getMessage());
                });
              
        }
    }

    private Adspace toAdSpace(AdspaceInput adspaceInput) {
        Adspace adspace = new Adspace(
            adspaceInput.getWidth().doubleValue() / adspaceInput.getHeight().doubleValue(),
            adspaceInput.getNumBidsToLoad() != null ? adspaceInput.getNumBidsToLoad().intValue() : 10,
            adspaceInput.getPriceSlot() != null
                ? AdPriceSlot.fromStringOrValue(adspaceInput.getPriceSlot())
                : AdPriceSlot.BTC1_000,
            Arrays.asList(adspaceInput.getMimeTypes()).stream().map(AdMimeType::fromString).toList(),
            bid -> {
                return NGEPlatform
                    .get()
                    .wrapPromise((res, rej) -> {
                        Map<String, Object> bidMap = bid.toMap();
                        JSObject bidObject = TeaVMJsConverter.toJSObject(bidMap);
                        ShowCallback show = () -> {
                            res.accept(null);
                        };
                        RejectCallback reject = err -> {
                            rej.accept(err);
                        };
                        adspaceInput.getShow().call(null, bidObject, show, reject);
                    });
            }
        );

        if (adspaceInput.getUid() != null) {
            adspace.setUid(adspaceInput.getUid());
        }

        if (adspaceInput.getLanguages() != null && adspaceInput.getLanguages().length > 0) {
            for (String lang : adspaceInput.getLanguages()) {
                adspace.withLanguage(lang);
            }
        }

        if (adspaceInput.getAdvertisersWhitelist() != null && adspaceInput.getAdvertisersWhitelist().length > 0) {
            adspace.setAdvertisersWhitelist(
                Arrays.asList(adspaceInput.getAdvertisersWhitelist()).stream().map(this::pubkeyFromString).toList()
            );
        }

        if (adspaceInput.getCategories() != null && adspaceInput.getCategories().length > 0) {
            for (String cat : adspaceInput.getCategories()) {
                adspace.withCategory(taxonomy.getByPath(cat));
            }
        }
        if (adspaceInput.getBidFilter() != null) {
            JSFunction bidFilter = adspaceInput.getBidFilter();
            adspace.setBidFilter(bid -> {
                return NGEPlatform
                    .get()
                    .wrapPromise((res, rej) -> {
                        BidFilterCallback filterCallback = v -> {
                            res.accept(v);
                        };
                        Map<String, Object> bidMap = bid.toMap();
                        JSObject bidObject = TeaVMJsConverter.toJSObject(bidMap);
                        bidFilter.call(null, bidObject, filterCallback);
                    });
            });
        }
        return adspace;
    }

    @JSExport
    public void loadAd(AdspaceInput adspaceInput) {
        synchronized (this) {
            String appKey = adspaceInput.getAppKey();
            if (penaltyStore == null) {
                VStore v = NGEPlatform.get().getDataStore("nostrads", "penaltyStore");
                penaltyStore = new PenaltyStorage(v);
            }
            if (displayClient == null) {
                displayClient = new AdsDisplayClient(pool, signer, pubkeyFromString(appKey), taxonomy, penaltyStore);
            }

            Adspace adspace = toAdSpace(adspaceInput);

            displayClient.loadNextAd(adspace, false);
        }
    }

    @JSExport
    public void unregisterAdspace(AdspaceInput adspaceInput) {
        synchronized (this) {
            String appKey = adspaceInput.getAppKey();
            if (displayClient == null) {
                displayClient = new AdsDisplayClient(pool, signer, pubkeyFromString(appKey), taxonomy, penaltyStore);
            }
            Adspace adspace = toAdSpace(adspaceInput);
            displayClient.unregisterAdspace(adspace);
        }
    }

    @JSExport
    public void registerAdspace(AdspaceInput adspaceInput) {
        synchronized (this) {
            String appKey = adspaceInput.getAppKey();
            if (displayClient == null) {
                displayClient = new AdsDisplayClient(pool, signer, pubkeyFromString(appKey), taxonomy, penaltyStore);
            }
            Adspace adspace = toAdSpace(adspaceInput);
            displayClient.registerAdspace(adspace);
        }
    }
}
