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
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.client.negotiation.NegotiationHandler;
import org.ngengine.nostrads.client.negotiation.OffererNegotiationHandler;
import org.ngengine.nostrads.client.negotiation.OffererNegotiationHandler.OfferListener;
import org.ngengine.nostrads.client.services.AbstractAdService;
import org.ngengine.nostrads.client.services.PenaltyStorage;
import org.ngengine.nostrads.client.services.display.fun.CompletedCallback;
import org.ngengine.nostrads.client.services.display.fun.RefreshAdspaceCallback;
import org.ngengine.nostrads.client.services.display.fun.ShowCallback;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.negotiation.AdAcceptOfferEvent;
import org.ngengine.nostrads.protocol.negotiation.AdBailEvent;
import org.ngengine.nostrads.protocol.negotiation.AdBailEvent.Reason;
import org.ngengine.nostrads.protocol.negotiation.AdOfferEvent;
import org.ngengine.nostrads.protocol.negotiation.AdPayoutEvent;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

/**
 * Client for displaying ads from the Ad network.
 */
public class AdsDisplayClient extends AbstractAdService {

    private static final Logger logger = Logger.getLogger(AdsDisplayClient.class.getName());

    private final Map<String, GlobalRankedAd> bidsCache = new WeakHashMap<>();
    private final Map<Adspace, RankedAdsQueue> queues = new ConcurrentHashMap<>();
    private final PenaltyStorage penaltyStorage;
    private int penaltyIncrease = 1; // default penalty increase for failed negotiations
    private final RefreshAdspaceCallback refreshCallback;

    /**
     * Constructor for AdsDisplayClient.
     * @param pool the NostrPool to use for network operations
     * @param signer the NostrSigner to use for signing events
     * @param taxonomy the AdTaxonomy to use for categorizing ads (null to instantiate a default taxonomy)
     * @param penaltyStorage the PenaltyStorage to use for storing and retrieving POW penalties
     */
    public AdsDisplayClient(
        @Nonnull NostrPool pool,
        @Nonnull NostrSigner signer,
        @Nullable AdTaxonomy taxonomy,
        @Nonnull PenaltyStorage penaltyStorage,
        @Nonnull RefreshAdspaceCallback refreshCallback
    ) {
        super(pool, signer, taxonomy);
        this.penaltyStorage = penaltyStorage;
        this.refreshCallback = refreshCallback;
    }

    /**
     * Set the value to sum to the penalty for each negotiation that ends with a punishment.
     * @param penaltyIncrease
     */
    public void setPenaltyIncrease(int penaltyIncrease) {
        this.penaltyIncrease = penaltyIncrease;
    }

    /**
     * Register an adspace for displaying ads.
     * If two adspaces are equals, they will share the same queue of bids.
     * @param adspace the adspace to register
     */
    public RankedAdsQueue registerAdspace(Adspace adspace) {
        return queues.compute(
            adspace,
            (k, v) -> {
                if (v == null) {
                    return new RankedAdsQueue(getTaxonomy(), getPool(), penaltyStorage, bidsCache, adspace);
                } else {
                    v.refs.incrementAndGet();
                    return v;
                }
            }
        );
    }

    /**
     * Unregister an adspace.
     * @param adspace the adspace to unregister
     */
    public void unregisterAdspace(Adspace adspace) {
        queues.computeIfPresent(
            adspace,
            (k, v) -> {
                if (v.refs.decrementAndGet() <= 0) {
                    return null; // Remove the adspace if no more references
                } else {
                    return v;
                }
            }
        );
    }

    /**
     * Listener for handling ad negotiation lifecycle.
     */
    private class Listener implements OfferListener {

        private volatile boolean requestedPayment = false;
        private final ShowCallback showAd;
        private final RefreshAdspaceCallback invalidate;
        private final CompletedCallback completedCallback;

        Listener(ShowCallback showAd, RefreshAdspaceCallback invalidate, CompletedCallback completedCallback) {
            this.showAd = showAd;
            this.invalidate = invalidate;
            this.completedCallback = completedCallback;
        }

        @Override
        public void verifyPayout(NegotiationHandler neg, AdPayoutEvent event) {
            logger.finer("Received payout event: " + event.getId() + " for bidding: " + neg.getBidEvent().getId());
            // we do not verify the payouts for now,

            // mark negotiation as completed
            neg.markCompleted();

            // remember the penalty
            penaltyStorage.set(neg);
        }

        @Override
        public void onRequestingPayment(NegotiationHandler neg) {
            // this flag will be used to know when to punish the counterparty
            requestedPayment = true;
        }

        @Override
        public void onBail(NegotiationHandler neg, AdBailEvent event, boolean initiatedByCounterparty) {
            // if bailed after requesting payment, we punish automatically
            if (initiatedByCounterparty) {
                if (requestedPayment) {
                    logger.finer("Bail after payment request, punishing counterparty directly");
                    try {
                        neg.punishCounterparty(penaltyIncrease);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Failed to punish counterparty", e);
                    }
                }
                penaltyStorage.set(neg);
            }
        }

        @Override
        public void showAd(NegotiationHandler neg, AdAcceptOfferEvent acp, Consumer<String> notifyShown) {
            neg.markAccepted();
            AdOfferEvent offer = acp.getOffer();
            if (offer == null) return;
            this.showAd.apply(neg.getBidEvent(), offer)
                .catchException(ex -> {
                    // any exception will automatically bail the negotiation
                    neg.bail(Reason.ACTION_INCOMPLETE);
                })
                .then(result -> {
                    // if the advertiser has penalized us, we counter-penalize them (assuming we are
                    // always honest)  this will naturally deprioritize their ads in the future
                    if (neg.getLocalPenalty() > 0) {
                        int p = neg.getCounterpartyPenalty();
                        if (neg.getLocalPenalty() > p) {
                            neg.setCounterpartyPenalty(p);
                        }
                    }
                    notifyShown.accept("Ad shown successfully");
                    return null;
                });
        }

        @Override
        public void onClose(NegotiationHandler neg, AdOfferEvent offer) {
            if (offer == null) return;
            boolean completed = neg.isCompleted();
            if (completedCallback != null) {
                completedCallback.accept(
                    neg,
                    offer,
                    completed,
                    "Negotiation closed" + (completed ? " successfully" : " without payment")
                );
            }
            if (!completed) {
                invalidate.accept(neg, offer, "negotiation closed");
            }
        }
    }

    /**
     * Load the best next ad for the given adspace.
     * @param adspace the adspace to load the next ad for
     */
    public AsyncTask<AdBidEvent> loadNextAd(
        Adspace adspace,
        int width,
        int height,
        Function<AdBidEvent, AsyncTask<Boolean>> filter,
        ShowCallback showCallback,
        CompletedCallback completedCallback
    ) {
        if (isClosed()) throw new IllegalStateException("AdClient is closing");
        return NGEPlatform
            .get()
            .wrapPromise((res, rej) -> {
                executor
                    .run(() -> {
                        RankedAdsQueue queue = queues.get(adspace);
                        if (queue == null) {
                            throw new IllegalStateException("Adspace not registered: " + adspace);
                        }
                        GlobalRankedAd gad = queue.get(
                            width,
                            height,
                            bid -> {
                                try {
                                    return filter.apply(bid).await();
                                } catch (Exception e) {
                                    logger.log(Level.WARNING, "Error applying filter for bid: " + bid.getId(), e);
                                    e.printStackTrace();
                                    return false;
                                }
                            }
                        );
                        if (gad == null) {
                            throw new IllegalStateException("No ads available for adspace: " + adspace);
                        }
                        AdBidEvent ad = gad.get();

                        if (ad == null) {
                            throw new IllegalStateException("No ads available for adspace: " + adspace);
                        }

                        Listener l = new Listener(
                            (bid, offer) -> {
                                return showCallback
                                    .apply(bid, offer)
                                    .then(result -> {
                                        if (!result) {
                                            rej.accept(new RuntimeException("Ad display rejected by user"));
                                        } else {
                                            res.accept(ad);
                                        }
                                        return result;
                                    });
                            },
                            (neg, offer, reason) -> {
                                refreshCallback.accept(neg, offer, reason);
                                gad.derank(true);
                                rej.accept(new Exception("Ad negotiation failed: " + reason));
                            },
                            completedCallback
                        );
                        logger.finer("Creating negotiation handler for bid: " + ad.getId());

                        openNegotiation(adspace.getAppKey(), ad, l)
                            .then(n -> {
                                OffererNegotiationHandler oneg = (OffererNegotiationHandler) n;
                                logger.finer("Negotiation opened for bid: " + ad.getId());
                                oneg
                                    .makeOffer()
                                    .catchException(ex -> {
                                        logger.log(
                                            Level.WARNING,
                                            "Error making offer for bid: " + ad.getId() + " in adspace: " + adspace,
                                            ex
                                        );
                                        ex.printStackTrace();
                                    });
                                return ad;
                            })
                            .catchException(e -> {
                                e.printStackTrace();
                                gad.derank(true);

                                logger.log(
                                    Level.WARNING,
                                    "Error opening negotiation for bid: " + ad.getId() + " in adspace: " + adspace,
                                    e
                                );
                                rej.accept(
                                    new RuntimeException(
                                        "Error opening negotiation for bid: " + ad.getId() + " in adspace: " + adspace,
                                        e
                                    )
                                );
                            });
                        return null;
                    })
                    .catchException(ex -> {
                        logger.log(Level.WARNING, "Error loading next ad for adspace: " + adspace, ex);
                        ex.printStackTrace();
                        rej.accept(ex);
                    });
            });
    }

    /**
     * Open a negotiation for the given bid event manually.
     * This is not needed when using loadNextAd, but can be used to handle
     * bids and offers manually by calling {@link OffererNegotiationHandler#makeOffer()} on the negotiation handler returned.
     *
     *
     * @param listener the listener to notify when the negotiation is ready, can be null
     * @return an AsyncTask that will complete with the AdOffererNegotiationHandler instance
     * @throws IllegalStateException if the AdClient is closing
     * @throws IllegalArgumentException if the event is not a valid AdBidEvent
     */
    public AsyncTask<NegotiationHandler> openNegotiation(
        NostrPublicKey appKey,
        AdBidEvent bid,
        OffererNegotiationHandler.OfferListener listener
    ) {
        if (isClosed()) {
            throw new IllegalStateException("AdClient is closing");
        }
        OffererNegotiationHandler negotiation = new OffererNegotiationHandler(
            appKey,
            getPool(),
            getSigner(),
            bid,
            getMaxDiff()
        );

        // load the initial penalty for the negotiation
        return penaltyStorage
            .get(negotiation.getBidEvent())
            .then(penalty -> {
                negotiation.setCounterpartyPenalty(penalty);
                if (penalty > 0) {
                    logger.finer("Setting counterparty penalty for " + negotiation.getBidEvent().getId() + ": " + penalty);
                }
                return negotiation;
            })
            .then(neg -> {
                // add the listener if provided
                if (listener != null) neg.addListener(listener);
                // we register the negotiation, the parent class will track and close it when needed (eg. if it expires)
                registerNegotiation(neg);
                return neg;
            });
    }
}
