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

import java.util.Locale;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.nip01.Nip01UserMetadata;

/**
 * Discovery markers and public fee metadata for NIP-AD profiles.
 */
public final class AdProfileMetadata {

    public static final String DELEGATE = "nip-ad:delegate";
    public static final String ADVERTISER = "nip-ad:advertiser";
    public static final String FEES = "nostrads:fees";

    private AdProfileMetadata() {}

    public static NostrFilter delegateFilter() {
        return new NostrFilter().withKind(0).withTag("t", DELEGATE);
    }

    public static UnsignedNostrEvent delegateEvent(Nip01UserMetadata profile) {
        UnsignedNostrEvent event = profile.toUpdateEvent();
        if (!hasRole(event, DELEGATE)) event.withTag("t", DELEGATE);
        return event;
    }

    public static boolean hasRole(NostrEvent event, String role) {
        if (event.getKind() != 0) return false;
        var tags = event.getTag("t");
        return tags != null && tags.stream().anyMatch(tag -> tag.size() > 0 && role.equals(tag.get(0)));
    }

    public static String fees(long minMsats, double rate, long maxMsats) {
        if (minMsats < 0 || maxMsats < minMsats || !Double.isFinite(rate) || rate < 0 || rate > 1) {
            throw new IllegalArgumentException("Invalid delegate fee");
        }
        return String.format(Locale.ROOT, "%d:%s:%d", minMsats, Double.toString(rate), maxMsats);
    }
}
