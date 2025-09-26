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

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.blossom4j.BlossomAuth;
import org.ngengine.blossom4j.BlossomEndpoint;
import org.ngengine.blossom4j.BlossomPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.utils.UniqueId;
import org.ngengine.nostrads.client.advertiser.AdvertiserClient;
import org.ngengine.nostrads.protocol.types.AdActionType;
import org.ngengine.nostrads.protocol.types.AdMimeType;
import org.ngengine.nostrads.protocol.types.AdSize;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.nostrads.types.BidCallback;
import org.ngengine.nostrads.types.BidCancelCallback;
import org.ngengine.nostrads.types.BidsCallback;
import org.ngengine.nostrads.types.Nip01Callback;
import org.ngengine.nostrads.types.PublicKeyCallback;
import org.ngengine.nostrads.types.UploadCallback;
import org.ngengine.platform.teavm.TeaVMJsConverter;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

public class AdvertiserClientWrapper extends NostrAds {

    private static final Logger logger = Logger.getLogger(AdvertiserClientWrapper.class.getName());
    private AdvertiserClient advClient;
    private BlossomPool blossomPool;
    private final String[] relays;
    private final String auth;
    private final String[] blossomEndpoints;
    private final JavaContext ctx;

    @JSExport
    public AdvertiserClientWrapper(String[] relays, String auth, String[] blossomEndpoints) throws Exception {
        super();
        this.relays = relays;
        this.auth = auth;
        this.blossomEndpoints = blossomEndpoints;
        this.ctx = new JavaContext();
    }

    protected void init() {
        if (!super.init(relays, auth)) return;
        this.advClient = new AdvertiserClient(pool, signer, taxonomy);
        this.blossomPool = new BlossomPool(new BlossomAuth(signer));
        for (String server : blossomEndpoints) {
            this.blossomPool.ensureEndpoint(new BlossomEndpoint(server));
        }
    }

    @JSExport
    public void getPublicKey(PublicKeyCallback callback) throws Exception {
        ctx.run(() -> {
            init();
            try {
                super.getPublicKey(callback);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @JSExport
    protected void getNip01Meta(String pubkey, Nip01Callback callback) {
        ctx.run(() -> {
            init();
            super.getNip01Meta(pubkey, callback);
        });
    }

    @JSExport
    public void close() {
        ctx.run(() -> {
            init();
            super.close();
            blossomPool.close();
        });
    }

    @JSExport
    public void publishNewBid(BidInputObject bid, BidCallback callback) throws Exception {
        ctx.run(() -> {
            init();
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
            int budgetMsats = bid.getDailyBudget();

            // Get required numeric properties
            long bidMsats = (long) bid.getBid();

            // Get optional numeric property with default
            double expireAtValue = bid.getExpireAt();
            long expireAt = Double.isNaN(expireAtValue) ? -1 : (long) expireAtValue;

            // Get array properties
            String[] categories = bid.getCategory() != null ? bid.getCategory() : null;
            String[] languages = bid.getLanguages() != null ? bid.getLanguages() : null;
            String[] offerersWhitelist = bid.getOfferersWhitelist() != null ? bid.getOfferersWhitelist() : null;
            String[] appsWhitelist = bid.getAppsWhitelist() != null ? bid.getAppsWhitelist() : null;

            // payout limits
            int maxPayouts = bid.getMaxPayouts();
            int payoutResetIntervalSec = bid.getPayoutResetInterval();
            Duration payoutResetInterval = Duration.ofSeconds(payoutResetIntervalSec);

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
            switch (actionTypeEnum) {
                case ACTION:
                    holdTimeDuration = Duration.ofSeconds(60 * 15); // 5 minutes
                    break;
                case ATTENTION:
                    holdTimeDuration = Duration.ofSeconds(60 * 5); // 2 minutes
                    break;
            }

            NostrPublicKey delegatePublicKey = delegate != null ? pubkeyFromString(delegate) : null;
            Instant expireAtInstant = expireAt > 0 ? Instant.ofEpochMilli(expireAt) : null;

            Map<String, Object> delegatePayload = new HashMap<>();
            delegatePayload.put("nwc", nwcUrl);
            delegatePayload.put("dailyBudget", budgetMsats);

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
                    expireAtInstant,
                    maxPayouts,
                    payoutResetInterval
                )
                .then(bidEvent -> {
                    advClient
                        .publishBid(bidEvent)
                        .then(r -> {
                            callback.accept(TeaVMJsConverter.toJSObject(bidEvent.toMap()), null);
                            return null;
                        });
                    return null;
                })
                .catchException(err -> {
                    logger.log(Level.SEVERE, "Error publishing bid" + err.getCause());
                    callback.accept(null, err.getMessage());
                });
        });
    }

    @JSExport
    public void cancelBid(String eventId, BidCancelCallback callback) throws Exception {
        ctx.run(() -> {
            init();
            advClient
                .cancelBid(eventId, "cancelled")
                .then(r -> {
                    callback.accept(null);
                    return null;
                })
                .catchException(ex -> {
                    logger.log(Level.SEVERE, "Error cancelling bid", ex);
                    callback.accept(ex.getMessage());
                });
        });
    }

    @JSExport
    public void listBids(BidsCallback callback) throws Exception {
        ctx.run(() -> {
            init();
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
        });
    }

    @JSExport
    public void uploadImage(Int8Array imageBuffer, String mimeType, UploadCallback callback) {
        ctx.run(() -> {
            init();
            String fileName = "img-" + UniqueId.getNext();
            String ext = AdMimeType.fromString(mimeType).toString().split("/")[1];
            if (ext.equals("jpeg")) {
                ext = "jpg";
            }
            fileName += "." + ext;

            byte imageData[] = imageBuffer.toJavaArray();

            this.blossomPool.upload(ByteBuffer.wrap(imageData), fileName, mimeType)
                .then(desc -> {
                    Map<String, Object> descMap = desc.toMap();
                    JSObject descObj = TeaVMJsConverter.toJSObject(descMap);
                    callback.accept(descObj, null);
                    return null;
                })
                .catchException(ex -> {
                    callback.accept(null, ex.getMessage());
                });
        });
    }

    @JSExport
    public void deleteImage(String hash, UploadCallback callback) {
        ctx.run(() -> {
            init();
            this.blossomPool.delete(hash)
                .then(r -> {
                    callback.accept(null, null);
                    return null;
                })
                .catchException(ex -> {
                    logger.log(Level.SEVERE, "Error deleting image", ex);
                    callback.accept(null, ex.getMessage());
                });
        });
    }
}
