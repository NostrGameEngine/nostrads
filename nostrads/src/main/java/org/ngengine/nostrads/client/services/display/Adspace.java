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
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostrads.protocol.AdBidFilter;
import org.ngengine.nostrads.protocol.types.AdAspectRatio;
import org.ngengine.nostrads.protocol.types.AdMimeType;
import org.ngengine.nostrads.protocol.types.AdPriceSlot;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;

public class Adspace {

    public static int NUM_BIDS_TO_LAOD = 10;

    private final AdAspectRatio ratio;
    private final NostrPublicKey appKey;
    private final NostrPublicKey userKey;
    private final List<AdMimeType> mimetypes;
    private final AdPriceSlot priceSlot;

    private List<AdTaxonomy.Term> categories;
    private List<NostrPublicKey> advertisersWhitelist;
    private List<String> languages;

    public Adspace(
        @Nonnull NostrPublicKey appKey,
        @Nonnull NostrPublicKey userKey,
        @Nonnull AdAspectRatio ratio,
        @Nonnull AdPriceSlot priceSlot,
        @Nonnull List<AdMimeType> mimetypes
    ) {
        this.ratio = ratio;
        this.mimetypes = mimetypes;
        this.priceSlot = priceSlot;
        this.appKey = appKey;
        this.userKey = userKey;
    }

    /**
     * Returns a filter that matches some of this adspace properties.
     * This filter is used as a base for fetching bids for this adspace to reduce the number of
     * events to process. (can lead to false positives, but it is better than nothing).
     *
     *
     * @return a NostrFilter that matches bids for this adspace
     */
    public NostrFilter toFilter() {
        AdBidFilter filter = new AdBidFilter();
        filter.limit(NUM_BIDS_TO_LAOD);
        filter.withPriceSlot(getPriceSlot());
        if (getAdvertisersWhitelist() != null) {
            for (NostrPublicKey advertiser : getAdvertisersWhitelist()) {
                filter.withAuthor(advertiser);
            }
        }

        List<String> langs = getLanguages();
        if (langs != null && langs.size() > 0) {
            filter.withLanguages(langs.toArray(new String[0]));
        }

        filter.withMimeTypes(getMimeTypes().toArray(new AdMimeType[0]));
        return filter;
    }

    public AdAspectRatio getRatio() {
        return ratio;
    }

    public NostrPublicKey getAppKey() {
        return appKey;
    }

    public NostrPublicKey getUserKey() {
        return userKey;
    }

    @Override
    public String toString() {
        return (
            "Adspace{" +
            "ratio=" +
            ratio +
            ", appKey=" +
            appKey +
            ", userKey=" +
            userKey +
            ", mimetypes=" +
            mimetypes +
            ", priceSlot=" +
            priceSlot +
            ", categories=" +
            categories +
            ", advertisersWhitelist=" +
            advertisersWhitelist +
            ", languages=" +
            languages +
            '}'
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Adspace)) return false;
        Adspace adspace = (Adspace) o;
        if (adspace.getRatio() != ratio) return false;
        if (!appKey.equals(adspace.appKey)) return false;
        if (!userKey.equals(adspace.userKey)) return false;
        if (!mimetypes.equals(adspace.mimetypes)) return false;
        if (!priceSlot.equals(adspace.priceSlot)) return false;
        if (categories != null ? !categories.equals(adspace.categories) : adspace.categories != null) return false;
        if (languages != null ? !languages.equals(adspace.languages) : adspace.languages != null) return false;
        if (
            advertisersWhitelist != null
                ? !advertisersWhitelist.equals(adspace.advertisersWhitelist)
                : adspace.advertisersWhitelist != null
        ) return false;
        return true;
    }

    @Override
    public int hashCode() {
        int result;
        result = ratio.hashCode();
        result = 31 * result + appKey.hashCode();
        result = 31 * result + userKey.hashCode();
        result = 31 * result + mimetypes.hashCode();
        result = 31 * result + priceSlot.hashCode();
        result = 31 * result + (categories != null ? categories.hashCode() : 0);
        result = 31 * result + (advertisersWhitelist != null ? advertisersWhitelist.hashCode() : 0);
        result = 31 * result + (languages != null ? languages.hashCode() : 0);

        return result;
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

    @Nonnull
    public AdPriceSlot getPriceSlot() {
        return priceSlot;
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

    public void setAdvertisersWhitelist(@Nullable List<NostrPublicKey> advertisersWhitelist) {
        this.advertisersWhitelist = advertisersWhitelist;
    }

    public List<NostrPublicKey> getAdvertisersWhitelist() {
        return advertisersWhitelist;
    }
}
