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

import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostrads.client.services.PenaltyStorage;
import org.ngengine.nostrads.client.services.display.AdsDisplayClient;
import org.ngengine.nostrads.client.services.display.Adspace;
import org.ngengine.nostrads.protocol.types.AdAspectRatio;
import org.ngengine.nostrads.protocol.types.AdMimeType;
import org.ngengine.nostrads.protocol.types.AdPriceSlot;
import org.ngengine.nostrads.types.InvalidateOfferCallback;
import org.ngengine.nostrads.types.OnShowFunction;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.VStore;
import org.ngengine.platform.teavm.TeaVMJsConverter;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSFunction;
import org.teavm.jso.core.JSString;

public class DisplayClientWrapper extends NostrAds {

    private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(
        DisplayClientWrapper.class.getName()
    );
    private AdsDisplayClient displayClient;
    private PenaltyStorage penaltyStore;
    private final String[] relays;
    private final String auth;
    private final InvalidateOfferCallback invalidateOffer;
    private final JavaContext ctx;

    @JSExport
    public DisplayClientWrapper(String[] relays, String auth, InvalidateOfferCallback invalidateOffer) throws Exception {
        this.relays = relays;
        this.auth = auth;
        this.invalidateOffer = invalidateOffer;
        this.ctx = new JavaContext();
    }

    protected void init(){
        if(!super.init(relays, auth))return;
        VStore v = NGEPlatform.get().getDataStore("nostrads", "penaltyStore");
        penaltyStore = new PenaltyStorage(v);
        displayClient = new AdsDisplayClient(
                pool,
                signer,
                taxonomy,
                penaltyStore,
                (neg, off, reason) -> {
                    logger.log(Level.INFO, "Invalidating offer: " + off.getId() + " for reason: " + reason);
                    invalidateOffer.accept(off.getId());
                });
    }

    @JSExport
    public void close() {
        ctx.run(() -> {
            init();
            super.close();
        });
    }

    @JSExport
    public void loadAd(NextAdInput adspaceInput, OnShowFunction onShow, JSFunction onSuccess, JSFunction onFail) {
        ctx.run(() -> {
            init();
            Adspace adspace = toAdSpace(adspaceInput);
            displayClient
                .loadNextAd(
                    adspace,
                    adspaceInput.getWidth(),
                    adspaceInput.getHeight(),
                    bid -> {
                        return NGEPlatform
                            .get()
                            .wrapPromise((res, rej) -> {
                                res.accept(true);
                            });
                    },
                    (bid, off) -> {
                        return NGEPlatform
                            .get()
                            .wrapPromise((res, rej) -> {
                                JSObject bidObject = TeaVMJsConverter.toJSObject(bid.toMap());
                                String id = off.getId();
                                onShow.accept(
                                    id,
                                    bidObject,
                                    () -> {
                                        res.accept(true);
                                    },
                                    () -> {
                                        res.accept(false);
                                    }
                                );
                            });
                    },
                    (bid, off, success, message) -> {
                        if (success) {
                            onSuccess.call(null, JSString.valueOf(message));
                        } else {
                            onFail.call(null, JSString.valueOf(message));
                        }
                    }
                )
                .catchException(ex -> {
                    onFail.call(null, JSString.valueOf(ex.getMessage()));
                });
            
        });
    }

    @JSExport
    public void unregisterAdspace(NextAdInput adspaceInput) {
        ctx.run(() -> {
            init();
            Adspace adspace = toAdSpace(adspaceInput);
            displayClient.unregisterAdspace(adspace);
        });
    }

    @JSExport
    public void registerAdspace(NextAdInput adspaceInput) {
        ctx.run(() -> {
            init();
            Adspace adspace = toAdSpace(adspaceInput);
            displayClient.registerAdspace(adspace);
        });
    }

    protected Adspace toAdSpace(NextAdInput adspaceInput) {
        try {
            NostrPublicKey appKey = pubkeyFromString(adspaceInput.getAppKey());
            NostrPublicKey userKey = signer.getPublicKey().await();
            AdAspectRatio ratio = AdAspectRatio.fromDimensions(adspaceInput.getWidth(), adspaceInput.getHeight());
            AdPriceSlot priceSlot = AdPriceSlot.fromStringOrValue(adspaceInput.getPriceSlot());
            List<AdMimeType> mimetypes = Arrays
                .asList(adspaceInput.getMimeTypes())
                .stream()
                .map(AdMimeType::fromString)
                .toList();

            Adspace adspace = new Adspace(appKey, userKey, ratio, priceSlot, mimetypes);

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

            if (adspaceInput.getCategory() != null && adspaceInput.getCategory().length > 0) {
                for (String cat : adspaceInput.getCategory()) {
                    adspace.withCategory(taxonomy.getByPath(cat));
                }
            }

            return adspace;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error converting adspace input to Adspace object", e);
            throw new RuntimeException("Invalid adspace input: " + e.getMessage(), e);
        }
    }
}
