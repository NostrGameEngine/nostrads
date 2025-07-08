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

package org.ngengine.nostrads.protocol.negotiation;

import java.util.Map;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.NGEUtils;

public class AdOfferEvent extends AdPowNegotiationEvent {

    public AdOfferEvent(NostrSigner signer, SignedNostrEvent event, Map<String, Object> content) {
        super(event, null, content);
    }

    @Override
    public AdOfferEvent getOffer() {
        return this;
    }

    public NostrPublicKey getAppPubkey() {
        return NostrPublicKey.fromHex(NGEUtils.safeString(getTagData("y", true)));
    }

    @Override
    public void checkValid() throws Exception {
        super.checkValid();
        getAppPubkey();
    }

    public static class OfferBuilder extends AdPowNegotiationEvent.PowBuilder<AdOfferEvent> {

        private static final Factory<AdOfferEvent> cstr = new Factory<AdOfferEvent>() {
            @Override
            public AdOfferEvent create(
                NostrSigner signer,
                SignedNostrEvent event,
                AdOfferEvent offer,
                Map<String, Object> content
            ) {
                return new AdOfferEvent(signer, event, content);
            }
        };

        public OfferBuilder(NostrPublicKey appPubkey) {
            super(cstr, "offer");
            event.replaceTag("y", appPubkey.asHex());
        }
    }
}
