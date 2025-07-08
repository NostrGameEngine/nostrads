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

import java.util.List;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;

final class RankedBid {

    final AdBidEvent bid;
    private final Adspace adspace;
    private int impressionCount;
    private Double score;
    private double penalty = 0;

    RankedBid(AdBidEvent bid, Adspace adspace) {
        this.bid = bid;
        this.adspace = adspace;
        this.impressionCount = 0;
    }

    void markImpression() {
        this.impressionCount++;
        score = null;
    }

    void setPenalty(int penalty) {
        this.penalty = penalty;
        score = null; // Reset score since penalty affects it
    }

    private double aspectRatioRatio(double aspect1, double aspect2) {
        // Ensure the ratio is >= 1 by dividing the larger by the smaller
        if (aspect1 > aspect2) {
            return aspect1 / aspect2;
        } else {
            return aspect2 / aspect1;
        }
    }

    Double getScore() {
        if (score != null) {
            return score;
        }
        double spaceAspect = adspace.getRatio();
        List<AdTaxonomy.Term> spaceCategories = adspace.getCategories() != null ? adspace.getCategories() : List.of();

        // Extract aspect ratio from bid
        double bidAspect = bid.getAspectRatio().getFloatValue();

        // Calculate how different the aspects are (closer to 0 is better)
        double aspectDiff = Math.abs(aspectRatioRatio(bidAspect, spaceAspect) - 1.0);

        // If aspect ratios are too different, reject the bid
        if (aspectDiff > 0.4) {
            return Double.MIN_VALUE;
        }

        // Score for aspect ratio: 1.0 when identical, decreasing as they differ
        double aspectScore = Math.max(0, 1.0 - aspectDiff);

        // Category matching boost
        List<AdTaxonomy.Term> bidCategories = bid.getCategories();
        boolean categoryMatch = bidCategories.stream().anyMatch(c -> spaceCategories.contains(c));
        double categoryScore = categoryMatch ? 1.2 : 1.0;

        // Price score using logarithmic scale to dampen large variations
        double priceScore = Math.log(bid.getBidMsats() + 1);

        // Apply impression penalty (existing from markImpression method)
        double impressionFactor = Math.max(0.7, Math.pow(0.9, impressionCount));

        // Calculate base score
        double baseScore = priceScore * aspectScore * categoryScore * impressionFactor;

        // Apply penalty factor - higher penalty means lower score
        score = penalty > 0 ? baseScore / (1.0 + penalty) : baseScore;

        return score;
    }
}
