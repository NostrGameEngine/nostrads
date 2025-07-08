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

package org.ngengine.nostrads.protocol;

import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostrads.protocol.types.AdActionType;
import org.ngengine.nostrads.protocol.types.AdAspectRatio;
import org.ngengine.nostrads.protocol.types.AdMimeType;
import org.ngengine.nostrads.protocol.types.AdPriceSlot;
import org.ngengine.nostrads.protocol.types.AdSize;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;

/**
 * A nostr filter with helper methods for Ad
 */
public class AdBidFilter extends NostrFilter {

    public AdBidFilter() {
        super();
        withKind(AdBidEvent.KIND);
    }

    /**
     * If set the filter will selected only bids that target the specified apps
     * @param apps the list of apps to target
     * @return
     */
    public AdBidFilter onlyForApp(NostrPublicKey... apps) {
        String[] appIds = new String[apps.length];
        for (int i = 0; i < apps.length; i++) {
            appIds[i] = apps[i].asHex();
        }
        return (AdBidFilter) withTag("y", appIds);
    }

    /**
     * If set the filter will selected only bids that are for the specified offerers
     * @param offerers
     * @return
     */
    public AdBidFilter onlyForOfferers(NostrPublicKey... offerers) {
        String[] offerIds = new String[offerers.length];
        for (int i = 0; i < offerers.length; i++) {
            offerIds[i] = offerers[i].asHex();
        }
        return (AdBidFilter) withTag("p", offerIds);
    }

    /**
     * If set the filter will selected only bids tat request the specified action type.
     * @param actionType
     * @return
     */
    public AdBidFilter withActionType(AdActionType actionType) {
        withTag("k", actionType.toString());
        return this;
    }

    /**
     * If set the filter will selected only bids with payloads that match the specified MIME type.
     * @param mimeType
     * @return
     */
    public AdBidFilter withMimeType(AdMimeType mimeType) {
        withTag("m", mimeType.toString());
        return this;
    }

    /**
     * If set the filter will selected only bids that match the specified category.
     * @param category
     * @return
     */
    public AdBidFilter withCategory(AdTaxonomy.Term category) {
        withTag("t", category.id());
        return this;
    }

    /**
     * If set the filter will selected only bids that specifically target the specified language.
     * @param language
     * @return
     */
    public AdBidFilter withLanguage(String language) {
        withTag("l", language);
        return this;
    }

    /**
     * If set the filter will selected only bids that match the specified size.
     * @param size
     * @return
     */
    public AdBidFilter withSize(AdSize size) {
        withTag("s", size.toString());
        return this;
    }

    /**
     * If set the filter will selected only bids that match the specified aspect ratio.
     * @param aspectRatio
     * @return
     */
    public AdBidFilter withAspectRatio(AdAspectRatio aspectRatio) {
        withTag("S", aspectRatio.toString());
        return this;
    }

    /**
     * If set the filter will selected only bids that offer at least the specified minimum bid amount.
     * @param minBidAmount
     * @return
     */
    public AdBidFilter withPriceSlot(AdPriceSlot minBidAmount) {
        int pos = minBidAmount.ordinal();
        String[] slots = new String[AdPriceSlot.values().length - pos];
        for (int i = pos; i < AdPriceSlot.values().length; i++) {
            slots[i - pos] = AdPriceSlot.values()[i].toString();
        }
        withTag("f", slots);
        return this;
    }
}
