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

import static org.junit.Assert.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.Test;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.platform.NGEPlatform;

public class TestBidLifetime {

    private AdBidEvent bid(Instant expiration) throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        UnsignedNostrEvent event = new UnsignedNostrEvent()
            .withKind(30100)
            .withTag("d", "lifetime-test")
            .withTag("k", "link")
            .withTag("m", "text/plain")
            .withTag("D", signer.getPublicKey().await().asHex())
            .withTag("f", "BTC1_000")
            .withTag("s", "480x60")
            .withTag("S", "8:1")
            .withContent(
                NGEPlatform
                    .get()
                    .toJSON(
                        Map.of(
                            "description",
                            "Test campaign",
                            "payload",
                            "Text ad",
                            "link",
                            "https://example.com/",
                            "bid",
                            1000,
                            "hold_time",
                            900,
                            "max_payouts",
                            3,
                            "payout_reset_interval",
                            3600
                        )
                    )
            );
        if (expiration != null) event.withExpiration(expiration);
        return new AdBidEvent(new AdTaxonomy(), signer.sign(event).await());
    }

    @Test
    public void acceptsTenYearCampaigns() throws Exception {
        bid(Instant.now().plus(Duration.ofDays(3650))).checkValid();
    }

    @Test
    public void rejectsExpiredCampaignsAndMissingExpirations() throws Exception {
        assertFalse(bid(Instant.now().minusSeconds(60)).isValid());
        assertFalse(bid(null).isValid());
    }

    @Test
    public void rejectsCampaignsLongerThanTenYears() throws Exception {
        assertFalse(bid(Instant.now().plus(Duration.ofDays(3651))).isValid());
    }
}
