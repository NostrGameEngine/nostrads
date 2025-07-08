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

package org.ngengine.nostrads.protocol.types;

/**
 * Enum for supported slot bid values
 */
public enum AdPriceSlot {
    BTC1_000("BTC1_000", 1_000),
    BTC2_000("BTC2_000", 2_000),
    BTC10_000("BTC10_000", 10_000),
    BTC100_000("BTC100_000", 100_000),
    BTC1_000_000("BTC1_000_000", 1_000_000),
    BTC2_000_000("BTC2_000_000", 2_000_000),
    BTC5_000_000("BTC5_000_000", 5_000_000),
    BTC10_000_000("BTC10_000_000", 10_000_000),
    BTC50_000_000("BTC50_000_000", 50_000_000);

    private final String value;
    private final long msats;

    AdPriceSlot(String value, long msats) {
        this.value = value;
        this.msats = msats;
    }

    @Override
    public String toString() {
        return value;
    }

    public long getValueMsats() {
        return msats;
    }

    public static AdPriceSlot fromValue(long msats) {
        for (int i = AdPriceSlot.values().length - 1; i >= 0; i--) {
            AdPriceSlot type = AdPriceSlot.values()[i];
            if (type.msats <= msats) {
                return type;
            }
        }
        return AdPriceSlot.BTC1_000; // Default to the smallest slot if none match
    }

    public static AdPriceSlot fromString(String value) {
        for (AdPriceSlot type : AdPriceSlot.values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown MIME type: " + value);
    }

    public static AdPriceSlot fromStringOrValue(String value) {
        try {
            return fromString(value);
        } catch (IllegalArgumentException e) {
            try {
                long msats = Long.parseLong(value);
                return fromValue(msats);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid AdPriceSlot value: " + value, ex);
            }
        }
    }
}
