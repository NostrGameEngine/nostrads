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
import org.ngengine.nostr4j.signer.NostrSigner;

public class AdBailEvent extends AdNegotiationEvent {

    public static enum Reason {
        OUT_OF_BUDGET("out_of_budget"),
        EXPIRED("expired"),
        FAILED_PAYMENT("failed_payment"),
        ACTION_INCOMPLETE("action_incomplete"),
        CANCELLED("cancelled"),
        UNKNOWN("unknown"),
        PAYOUT_LIMIT("payout_limit");

        private final String reason;

        Reason(String reason) {
            this.reason = reason;
        }

        public String getReason() {
            return reason;
        }

        @Override
        public String toString() {
            return reason;
        }

        public static Reason fromString(String reason) {
            for (Reason r : Reason.values()) {
                if (r.getReason().equals(reason)) {
                    return r;
                }
            }
            return UNKNOWN;
        }
    }

    public AdBailEvent(NostrSigner signer, SignedNostrEvent event, AdOfferEvent offer, Map<String, Object> content) {
        super(event, offer, content);
    }

    public Reason getReason() {
        String message = getMessage();
        return Reason.fromString(message);
    }

    @Override
    public void checkValid() throws Exception {
        super.checkValid();
        Object msg = getMessage();
        if (msg == null) {
            throw new Exception("Bail event must have a reason message");
        }
    }

    public static class AdBailBuilder extends AdNegotiationEvent.Builder<AdBailEvent> {

        private static final Factory<AdBailEvent> cstr = new Factory<AdBailEvent>() {
            @Override
            public AdBailEvent create(
                NostrSigner signer,
                SignedNostrEvent event,
                AdOfferEvent offer,
                Map<String, Object> content
            ) {
                return new AdBailEvent(signer, event, offer, content);
            }
        };

        public AdBailBuilder() {
            super(cstr, "bail");
        }

        public AdBailBuilder withReason(Reason reason) {
            withMessage(reason.getReason());
            return this;
        }
    }
}
