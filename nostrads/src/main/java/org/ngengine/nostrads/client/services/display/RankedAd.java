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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;

public final class RankedAd {

    private static class DerankEvent {

        final Instant timestamp;
        final boolean hard;

        DerankEvent(Instant timestamp, boolean hard) {
            this.timestamp = timestamp;
            this.hard = hard;
        }
    }

    private final AdBidEvent bid;
    private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(RankedAd.class.getName());

    private final List<DerankEvent> derankEvents = new ArrayList<>();
    private double penalty = 0;

    // Deranking configuration
    private static final double INITIAL_DERANK_IMPACT = 0.02;
    private static final double MINIMUM_DERANK_IMPACT = 0.3;
    private static final Duration DERANK_DECAY_TIME = Duration.ofSeconds(60);
    private static final double DERANK_ACCUMULATION_FACTOR = 0.9;
    private static final double HARD_DERANK_FACTOR = 0.00001; // Effectively puts ad at the end

    RankedAd(AdBidEvent bid) {
        this.bid = bid;
    }

    public void derank(boolean errored) {
        Instant currentTime = Instant.now();
        derankEvents.add(new DerankEvent(currentTime, errored));
        logger.fine(
            "Deranking bid: " + bid + " hard=" + errored + " total deranks: " + derankEvents.size() + " at time: " + currentTime
        );
    }

    public void setPenalty(int penalty) {
        this.penalty = penalty;
    }

    /**
     * Calculate the current derank factor based on timestamps and decay
     */
    private double calculateDerankFactor() {
        if (derankEvents.isEmpty()) {
            return 1.0; // No deranking
        }

        Instant currentTime = Instant.now();
        double totalDerankImpact = 0.0;

        // Clean up old deranks that have fully decayed (3x the decay time)
        Duration cleanupThreshold = DERANK_DECAY_TIME.multipliedBy(3);
        derankEvents.removeIf(event -> Duration.between(event.timestamp, currentTime).compareTo(cleanupThreshold) > 0);

        // If any active "hard" derank, return minimal factor
        for (DerankEvent event : derankEvents) {
            if (event.hard && Duration.between(event.timestamp, currentTime).compareTo(DERANK_DECAY_TIME) < 0) {
                logger.fine("Hard derank active for bid " + bid.getId());
                return HARD_DERANK_FACTOR;
            }
        }

        // Otherwise, normal derank logic
        for (int i = 0; i < derankEvents.size(); i++) {
            DerankEvent event = derankEvents.get(i);
            if (event.hard) continue; // already handled above
            Duration timeElapsed = Duration.between(event.timestamp, currentTime);

            if (timeElapsed.compareTo(DERANK_DECAY_TIME) >= 0) {
                continue;
            }

            double timeDecayFactor = (double) timeElapsed.toMillis() / DERANK_DECAY_TIME.toMillis();
            double currentImpact =
                INITIAL_DERANK_IMPACT +
                (MINIMUM_DERANK_IMPACT - INITIAL_DERANK_IMPACT) *
                (1.0 - Math.exp(-1.5 * timeDecayFactor));
            double accumulationFactor = Math.pow(DERANK_ACCUMULATION_FACTOR, i);
            totalDerankImpact += currentImpact * accumulationFactor;
        }

        totalDerankImpact = Math.min(totalDerankImpact, 0.99);
        double derankFactor = 1.0 - totalDerankImpact;

        logger.fine(
            "Derank calculation for bid " +
            bid.getId() +
            ": activeDeranks=" +
            derankEvents.size() +
            ", totalImpact=" +
            totalDerankImpact +
            ", finalFactor=" +
            derankFactor
        );

        return derankFactor;
    }

    private double aspectRatioRatio(double aspect1, double aspect2) {
        // Ensure the ratio is >= 1 by dividing the larger by the smaller
        if (aspect1 > aspect2) {
            return aspect1 / aspect2;
        } else {
            return aspect2 / aspect1;
        }
    }

    public AdBidEvent get() {
        return bid;
    }

    /**
     * Get the base score for global ranking
     *
     * @return the base score for this bid
     */
    protected double getBaseScore() {
        // Apply time-based deranking with stronger initial impact
        double derankFactor = calculateDerankFactor();

        // Price score using logarithmic scale to dampen large variations
        double priceScore = Math.log(bid.getBidMsats() + 1);

        // Apply penalty factor: higher penalty means lower score
        double penaltyFactor = 1.0 / (1.0 + penalty / 100.0);

        double finalScore = priceScore * derankFactor * penaltyFactor;

        logger.fine(
            "Base score calculation for bid " +
            bid.getId() +
            ": price=" +
            priceScore +
            ", derankFactor=" +
            derankFactor +
            ", penaltyFactor=" +
            penaltyFactor +
            ", final=" +
            finalScore
        );

        return finalScore;
    }

    /**
     * Get the score for a specific adspace.
     * This score is based on how much the bid fits the specified adspace.
     *
     * @param width the width of the adspace in pixels
     * @param height the height of the adspace in pixels
     * @return the score for this bid in the context of the adspace
     */
    // public double getContextualScore(Adspace adspace, int width, int height) {
    //     // 1) aspect ratio
    //     double spaceAspect=adspace.getRatio().getFloatValue();
    //     double bidAspect=bid.getAspectRatio().getFloatValue();

    //     boolean spaceIsWide=spaceAspect>1.0; // wider than tall
    //     boolean bidIsWide=bidAspect>1.0; // wider than tall
    //     double aspectRatio=aspectRatioRatio(bidAspect,spaceAspect);
    //     double aspectDiff=aspectRatio-1.0; // How much larger the bigger ratio is

    //     // If the aspect ratio difference is too large, it will look bad with fit: contain
    //     // You can tune this threshold (e.g. 0.5 means 2:1 vs 1:1)
    //     if(aspectRatio>2.0||aspectDiff>0.7){
    //         logger.fine("Aspect ratio difference too large for bid "+bid.getId()+": bidAspect="+bidAspect+", spaceAspect="+spaceAspect+", aspectRatio="+aspectRatio+", aspectDiff="+aspectDiff);
    //         return -1;
    //     }

    //     double aspectScore;
    //     if(spaceIsWide!=bidIsWide){
    //         // Different orientations - return -1
    //         logger.fine("Different orientations for bid "+bid.getId()+": bidAspect="+bidAspect+" vs spaceAspect="+spaceAspect);
    //         return -1;
    //     }

    //     // Same orientation - calculate how close they are
    //     // Base score for same orientation: 0.7 (good baseline)
    //     // Bonus for close ratios: up to +0.3 for perfect match
    //     aspectScore=0.7+(0.3*Math.exp(-aspectDiff*2.0)); // Exponential bonus for closeness

    //     // 2) category boost only when space defines categories
    //     List<AdTaxonomy.Term> spaceCats=adspace.getCategories();
    //     double categoryScore=1.0;
    //     if(spaceCats!=null&&!spaceCats.isEmpty()){
    //         boolean match=bid.getCategories().stream().anyMatch(spaceCats::contains);
    //         categoryScore=match?1.2:1.0;
    //     }

    //     // 3) size fit based on per‐axis ratios
    //     double bidW=bid.getDimensions().getWidth();
    //     double bidH=bid.getDimensions().getHeight();
    //     if(width<=0||height<=0||bidW<=0||bidH<=0){
    //         logger.fine("Invalid dimensions for bid "+bid.getId()+": adspace ("+width+"x"+height+"), bid ("+bidW+"x"+bidH+"). Returning 0 score.");
    //         return 0.0;
    //     }
    //     double wRatio=Math.min(width,bidW)/Math.max(width,bidW);
    //     double hRatio=Math.min(height,bidH)/Math.max(height,bidH);
    //     double sizeScore=(wRatio+hRatio)/2; // 1.0 = perfect dim match

    //     // 4) combine
    //     return getBaseScore()*aspectScore*categoryScore*sizeScore;
    // }

    public double getContextualScore(Adspace space, int width, int height) {
        // 0) Compute scaling on each axis (±20% allowed)
        int bidW = bid.getDimensions().getWidth();
        int bidH = bid.getDimensions().getHeight();
        double scaleX = (double) width / bidW;
        double scaleY = (double) height / bidH;

        final double MIN_SCALE = 0.8; // allow up to 20% shrink
        final double MAX_SCALE = 1.2; // allow up to 20% enlarge
        if (scaleX < MIN_SCALE || scaleY < MIN_SCALE || scaleX > MAX_SCALE || scaleY > MAX_SCALE) {
            logger.fine(String.format("Bid %s scale out of bounds (%.2fx, %.2fx)", bid.getId(), scaleX, scaleY));
            return -1;
        }

        // 1) Aspect–ratio compatibility
        double spaceAR = space.getRatio().getFloatValue();
        double bidAR = bid.getAspectRatio().getFloatValue();
        double arRatio = aspectRatioRatio(spaceAR, bidAR);
        if (arRatio > 2.0) {
            return -1;
        }
        double aspectDiff = Math.abs(spaceAR - bidAR);
        double aspectScore = 0.7 + 0.3 * Math.exp(-aspectDiff * 2.0);

        // 2) Category boost
        double categoryScore = 1.0;
        List<AdTaxonomy.Term> cats = space.getCategories();
        if (cats != null && !cats.isEmpty()) {
            boolean match = bid.getCategories() != null && bid.getCategories().stream().anyMatch(cats::contains);
            categoryScore = match ? 1.2 : 1.0;
        }

        // 3) Geometric-mean size score (rewards closer fit)
        double sizeScore = Math.sqrt(
            Math.min(width, bidW) / (double) Math.max(width, bidW) * Math.min(height, bidH) / (double) Math.max(height, bidH)
        );

        // 4) Combine with global base score
        return getBaseScore() * aspectScore * categoryScore * sizeScore;
    }
}
