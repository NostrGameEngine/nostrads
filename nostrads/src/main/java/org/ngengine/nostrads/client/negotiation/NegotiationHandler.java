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

package org.ngengine.nostrads.client.negotiation;

import jakarta.annotation.Nonnull;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.negotiation.AdBailEvent;
import org.ngengine.nostrads.protocol.negotiation.AdNegotiationEvent;
import org.ngengine.nostrads.protocol.negotiation.AdOfferEvent;
import org.ngengine.nostrads.protocol.negotiation.AdPowNegotiationEvent;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

/**
 * Handle negotiation messaging in the Ad network.
 */
public abstract class NegotiationHandler {

    private static final Logger logger = Logger.getLogger(NegotiationHandler.class.getName());

    public static interface Listener {
        /**
         * Called when a bail event is received or sent.
         * @param neg
         * @param event
         */
        void onBail(NegotiationHandler neg, AdBailEvent event, boolean initiatedByCounterparty);

        void onClose(NegotiationHandler neg, AdOfferEvent offer);
    }

    private final NostrPool pool;
    private final int maxDiff;
    private final AdBidEvent bid;

    private final NostrSigner signer;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Instant createAt = Instant.now();

    private AdOfferEvent offer; // the offer we are negotiating on
    private int counterpartyPenalty = 0;
    private int localPenalty = 0;

    private volatile boolean closed = false;
    private volatile boolean completed = false;
    private volatile boolean accepted = false;

    /**
     * Returns the list of listeners registered to this negotiation handler.
     * @return
     */
    protected List<Listener> getListeners() {
        return listeners;
    }

    public void markAccepted() {
        this.accepted = true;
    }

    public boolean isAccepted() {
        return accepted;
    }

    /**
     * Returns true if the negotiation is completed and can be safely closed and untracked
     * @return
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * Mark the negotiation as completed.
     */
    public void markCompleted() {
        this.completed = true;
    }

    /**
     * Returns true if the negotiation is closed and can be safely untracked.
     * @return
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Add a listener to this negotiation handler.
     * @param listener the listener to add
     */
    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a listener from this negotiation handler.
     * @param listener the listener to remove
     */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Get the bid event that this negotiation is related to.
     * @return the AdBidEvent associated with this negotiation
     */
    public AdBidEvent getBidEvent() {
        return bid;
    }

    /**
     * Constructor for AdNegotiationHandler.
     * @param pool the NostrPool to use for subscriptions and publishing
     * @param signer the NostrSigner to use for signing events
     * @param bid the AdBidEvent that this negotiation is related to
     * @param maxDiff the maximum difficulty for proof of work in this negotiation, if the difficulty exceeds this value, the negotiation will be automatically bailed
     */
    protected NegotiationHandler(@Nonnull NostrPool pool, @Nonnull NostrSigner signer, @Nonnull AdBidEvent bid, int maxDiff) {
        this.signer = signer;
        this.pool = pool;
        this.bid = bid;
        this.maxDiff = maxDiff;
    }

    public void open(@Nonnull AdOfferEvent offer) {
        this.offer = offer;
    }

    /**
     * Close the negotiation, stop listening for events and free resources.
     * Once a negotiation is closed, it cannot be reopened.
     */
    public void close() {
        if (closed) return; // already closed
        closed = true;

        for (Listener listener : listeners) {
            try {
                listener.onClose(this, offer);
            } catch (Exception e) {
                e.printStackTrace();
                logger.log(Level.WARNING, "Error in onClose callback: ", e);
            }
        }
    }

    /**
     * Bail AND close the negotiation.
     *
     * This will send a bail event to the counterparty and when the event is acknowledged, it will close the negotiation by calling {@link #close()}.
     * @param reason the reason for bailing, this will be sent to the counterparty
     * @return
     */
    public AsyncTask<Void> bail(AdBailEvent.Reason reason) {
        if (this.offer == null) {
            return NGEPlatform
                .get()
                .wrapPromise((res, rej) -> {
                    res.accept(null);
                });
        }
        return bail(reason, this.offer);
    }

    public AsyncTask<Void> bail(AdBailEvent.Reason reason, AdOfferEvent offer) {
        if (isCompleted()) { // if completed, we just close the negotiation without bailing
            close();
            return NGEPlatform
                .get()
                .wrapPromise((res, rej) -> {
                    res.accept(null);
                });
        }
        AdBailEvent.AdBailBuilder builder = new AdBailEvent.AdBailBuilder();
        builder.withReason(reason);

        logger.fine("Bailing negotiation for offer: " + offer.getId() + " with reason: " + reason);
        return builder
            .build(signer, offer)
            .compose(sevent -> {
                return pool
                    .publish(sevent)
                    .then(ack -> {
                        for (Listener listener : listeners) {
                            listener.onBail(this, sevent, false);
                        }
                        close();
                        return null;
                    });
            });
    }

    public final void onEvent(SignedNostrEvent ev) {
        AdNegotiationEvent
            .cast(signer, ev, offer)
            .then(event -> {
                try {
                    if (event instanceof AdOfferEvent) return null;

                    if (!event.isValid()) return null; // continue only if the event is valid

                    if (event instanceof AdPowNegotiationEvent) {
                        // ensure the counterparty if providing the requested proof of work (if any)
                        if (
                            // offer is a special case, we won't ask for pow right away as the counterparty has no
                            // idea we want pow from them, yet
                            !(event instanceof AdOfferEvent) && event.checkPow(counterpartyPenalty)
                        ) {
                            // if valid pow, we reset the counterparty penalty
                            counterpartyPenalty = 0;
                        } else {
                            throw new RuntimeException("Counterparty failed to provide valid proof of work");
                        }

                        // handle penalty increase driven by the counterparty
                        AdPowNegotiationEvent powEvent = (AdPowNegotiationEvent) event;
                        if (powEvent.getRequestedDifficultyToRespond() > localPenalty) {
                            int p = powEvent.getRequestedDifficultyToRespond();
                            if (p < 0) p = 0;
                            if (p > maxDiff) {
                                throw new Exception("Too difficult");
                            }
                            this.localPenalty = p;
                        }
                    }

                    // handle bailing
                    if (event instanceof AdBailEvent) {
                        AdBailEvent bailEvent = (AdBailEvent) event;
                        for (Listener listener : listeners) {
                            listener.onBail(this, bailEvent, true);
                        }
                        close();
                    }

                    onEvent(event);
                } catch (Exception e) {
                    throw new RuntimeException("Error processing event: " + event.getId(), e);
                }
                return null;
            });
    }

    protected abstract void onEvent(AdNegotiationEvent ev);

    /**
     * Punish the counterparty by increasing the penalty amount.
     *
     * @param increaseAmount the amount to increase the penalty by
     * @throws Exception
     */
    public int punishCounterparty(int increaseAmount) throws Exception {
        this.counterpartyPenalty += increaseAmount;
        if (this.counterpartyPenalty > maxDiff) {
            this.counterpartyPenalty = maxDiff;
        }
        return this.counterpartyPenalty;
    }

    /**
     * Get the current penalty applied to the counterparty.
     * @return
     */
    public int getCounterpartyPenalty() {
        return counterpartyPenalty;
    }

    /**
     * Get the current penalty applied BY the counterparty. (ie. to us)
     * @return
     */
    public int getLocalPenalty() {
        return localPenalty;
    }

    /**
     * Set the penalty applied to the counterparty.
     *
     * Useful to initialize the penalty when starting a negotiation.
     *
     * @param penaltyAppliedToTheCounterparty
     */
    public void setCounterpartyPenalty(int penaltyAppliedToTheCounterparty) {
        this.counterpartyPenalty = penaltyAppliedToTheCounterparty;
    }

    /**
     * Get the NostrSigner used by this negotiation handler.
     * @return
     */
    protected NostrSigner getSigner() {
        return signer;
    }

    /**
     * Get the NostrPool used by this negotiation handler.
     */
    protected NostrPool getPool() {
        return pool;
    }

    /**
     * Get the offer that this negotiation is based on.
     * @return the AdOfferEvent that this negotiation is based on or null if the negotiation is not open yet
     */
    public AdOfferEvent getOffer() {
        return offer;
    }

    /**
     * Get the creation time of this negotiation handler.
     * @return
     */
    public Instant getCreatedAt() {
        return createAt;
    }
}
