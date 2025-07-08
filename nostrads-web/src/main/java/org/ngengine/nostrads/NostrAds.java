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
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
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
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.VStore;
import org.ngengine.platform.teavm.TeaVMJsConverter;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSFunction;

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
    public NostrAds(String[] relays, String adsKey) throws IOException {
        NostrAdsModule.initPlatform();
        pool = new NostrPool();
        for (int i = 0; i < relays.length; i++) {
            System.out.println("Connecting to relay: " + relays[i]);
            pool.connectRelay(new NostrRelay(relays[i]));
        }

        System.out.println("Connected to relays: " + pool.getRelays().size());

        NostrPrivateKey adsKeyN = adsKey.startsWith("nsec")
            ? NostrPrivateKey.fromBech32(adsKey)
            : NostrPrivateKey.fromHex(adsKey);

        System.out.println("App configured");

        this.signer = new NostrKeyPairSigner(new NostrKeyPair(adsKeyN));

        System.out.println("Signer initialized");

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

    @JSExport
    public void publishNewBid(BidInputObject bid, BidCallback callback) throws Exception {
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
            long bidMsats = (long) bid.getBidMsats();
            int holdTime = (int) bid.getHoldTime();

            // Get optional numeric property with default
            double expireAtValue = bid.getExpireAt();
            long expireAt = Double.isNaN(expireAtValue) ? -1 : (long) expireAtValue;

            // Get array properties
            String[] categories = bid.getCategories();
            String[] languages = bid.getLanguages();
            String[] offerersWhitelist = bid.getOfferersWhitelist();
            String[] appsWhitelist = bid.getAppsWhitelist();

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
            AdSize sizeEnum = AdSize.fromString(size);
            AdActionType actionTypeEnum = AdActionType.fromValue(actionType);

            // Create objects
            Duration holdTimeDuration = Duration.ofSeconds(holdTime);
            NostrPublicKey delegatePublicKey = delegate != null ? pubkeyFromString(delegate) : null;
            Instant expireAtInstant = expireAt > 0 ? Instant.ofEpochMilli(expireAt) : null;

            if (advClient == null) {
                advClient = new AdvertiserClient(pool, signer, taxonomy);
            }

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
                    Map.of("nwc", nwcUrl, "budget", budgetMsats),
                    expireAtInstant
                )
                .then(bidEvent -> {
                    advClient.publishBid(bidEvent);
                    callback.onBid(TeaVMJsConverter.toJSObject(bidEvent.toMap()), null);
                    return null;
                })
                .catchException(err -> {
                    logger.log(Level.SEVERE, "Error publishing bid", err);
                    callback.onBid(null, err.getMessage());
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
                    callback.onBids(bids, null);
                    return null;
                })
                .catchException(err -> {
                    logger.log(Level.SEVERE, "Error listing bids", err);
                    callback.onBids(null, err.getMessage());
                });
        }
    }

    @JSFunctor
    public static interface ShowCallback {
        void call();
    }

    @JSFunctor
    public static interface RejectCallback {
        void call(Throwable error);
    }

    @JSFunctor
    public static interface BidFilterCallback {
        void call(boolean v);
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
