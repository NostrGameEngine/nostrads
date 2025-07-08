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

package org.ngengine.nostrads.client.services.display;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.function.Function;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.types.AdMimeType;
import org.ngengine.nostrads.protocol.types.AdPriceSlot;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

public class Adspace {

    private final double ratio;
    private final List<AdMimeType> mimetypes;
    private final Function<AdBidEvent, AsyncTask<Boolean>> showBid;
    private final int numBidsToLoad;
    private final AdPriceSlot priceSlot;

    private Runnable onComplete;
    private List<AdTaxonomy.Term> categories;
    private List<NostrPublicKey> advertisersWhitelist;
    private List<String> languages;
    private String uid;

    private Function<AdBidEvent, AsyncTask<Boolean>> acceptBid = bid -> {
        return NGEPlatform
            .get()
            .wrapPromise((res, rej) -> {
                res.accept(true); // accept all bids by default
            });
    };

    public Adspace(
        double ratio,
        int numBidsToLoad,
        @Nonnull AdPriceSlot priceSlot,
        @Nonnull List<AdMimeType> mimetypes,
        @Nonnull Function<AdBidEvent, AsyncTask<Boolean>> showBid
    ) {
        this.ratio = ratio;
        this.mimetypes = mimetypes;
        this.showBid = showBid;
        this.numBidsToLoad = numBidsToLoad;
        this.priceSlot = priceSlot;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getUid() {
        return uid;
    }

    @Override
    public String toString() {
        return (
            "Adspace{" +
            "ratio=" +
            ratio +
            ", mimetypes=" +
            mimetypes +
            ", numBidsToLoad=" +
            numBidsToLoad +
            ", priceSlot=" +
            priceSlot +
            ", categories=" +
            categories +
            ", languages=" +
            languages +
            ", advertisersWhitelist=" +
            advertisersWhitelist +
            '}'
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Adspace)) return false;
        Adspace adspace = (Adspace) o;
        if (uid != null && adspace.uid != null) { // if both have a uid, use the uid as only equality check
            return uid.equals(adspace.uid);
        }
        return (
            Double.compare(adspace.ratio, ratio) == 0 &&
            numBidsToLoad == adspace.numBidsToLoad &&
            mimetypes.equals(adspace.mimetypes) &&
            priceSlot == adspace.priceSlot &&
            (categories != null ? categories.equals(adspace.categories) : adspace.categories == null) &&
            (languages != null ? languages.equals(adspace.languages) : adspace.languages == null) &&
            (
                advertisersWhitelist != null
                    ? advertisersWhitelist.equals(adspace.advertisersWhitelist)
                    : adspace.advertisersWhitelist == null
            )
        );
    }

    @Override
    public int hashCode() {
        if (uid != null) { // if uid is set, use it as hash code
            return uid.hashCode();
        }
        int result;
        long temp;
        temp = Double.doubleToLongBits(ratio);
        result = (int) (temp ^ (temp >>> 32));
        result = 31 * result + mimetypes.hashCode();
        result = 31 * result + numBidsToLoad;
        result = 31 * result + priceSlot.hashCode();
        result = 31 * result + (categories != null ? categories.hashCode() : 0);
        result = 31 * result + (languages != null ? languages.hashCode() : 0);
        result = 31 * result + (advertisersWhitelist != null ? advertisersWhitelist.hashCode() : 0);
        return result;
    }

    public double getRatio() {
        return ratio;
    }

    @Nullable
    public List<AdTaxonomy.Term> getCategories() {
        return categories;
    }

    @Nonnull
    public List<AdMimeType> getMimeTypes() {
        return mimetypes;
    }

    @Nullable
    public List<String> getLanguages() {
        return languages;
    }

    public int getNumBidsToLoad() {
        return numBidsToLoad;
    }

    @Nonnull
    public AdPriceSlot getPriceSlot() {
        return priceSlot;
    }

    @Nonnull
    protected Function<AdBidEvent, AsyncTask<Boolean>> getBidFilter() {
        return acceptBid;
    }

    @Nonnull
    protected Function<AdBidEvent, AsyncTask<Boolean>> getShowBidAction() {
        return showBid;
    }

    public Adspace withCategory(@Nonnull AdTaxonomy.Term category) {
        if (categories == null) {
            categories = List.of(category);
        } else {
            categories.add(category);
        }
        return this;
    }

    public Adspace withLanguage(@Nonnull String language) {
        if (languages == null) {
            languages = List.of(language);
        } else {
            languages.add(language);
        }
        return this;
    }

    public void setBidFilter(@Nullable Function<AdBidEvent, AsyncTask<Boolean>> acceptBid) {
        this.acceptBid =
            acceptBid != null
                ? acceptBid
                : bid -> {
                    return NGEPlatform
                        .get()
                        .wrapPromise((res, rej) -> {
                            res.accept(true); // accept all bids by default
                        });
                };
    }

    public void setOnCompleteCallback(@Nullable Runnable onComplete) {
        this.onComplete = onComplete;
    }

    public Runnable getOnCompleteCallback() {
        return onComplete;
    }

    public void setAdvertisersWhitelist(@Nullable List<NostrPublicKey> advertisersWhitelist) {
        this.advertisersWhitelist = advertisersWhitelist;
    }

    public List<NostrPublicKey> getAdvertisersWhitelist() {
        return advertisersWhitelist;
    }
}
