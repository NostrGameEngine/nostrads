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

package org.ngengine.nostrads.client.services.delegate;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.lnurl.LnUrl;
import org.ngengine.lnurl.LnUrlPay;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip01.Nip01;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.client.negotiation.DelegateNegotiationHandler;
import org.ngengine.nostrads.client.negotiation.DelegateNegotiationHandler.AdvListener;
import org.ngengine.nostrads.client.negotiation.DelegateNegotiationHandler.NotifyPayout;
import org.ngengine.nostrads.client.negotiation.NegotiationHandler;
import org.ngengine.nostrads.client.services.AbstractAdService;
import org.ngengine.nostrads.client.services.PenaltyStorage;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.negotiation.AdBailEvent;
import org.ngengine.nostrads.protocol.negotiation.AdNegotiationEvent;
import org.ngengine.nostrads.protocol.negotiation.AdOfferEvent;
import org.ngengine.nostrads.protocol.negotiation.AdPaymentRequestEvent;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.wallets.PayResponse;
import org.ngengine.wallets.Wallet;
import org.ngengine.wallets.nip47.NWCUri;
import org.ngengine.wallets.nip47.NWCWallet;

public class DelegateService extends AbstractAdService {

    private static final Logger logger = Logger.getLogger(DelegateService.class.getName());
    private final BiFunction<DelegateNegotiationHandler, AdOfferEvent, AsyncTask<Boolean>> filterNegotiations;
    private final Function<AdBidEvent, AsyncTask<Boolean>> filterBids;
    private final PenaltyStorage penaltyStorage;
    private final Tracker tracker;
    private long minFeeMsats = 0;
    private double percentFee = 0;
    private long maxFeeMsats = 10000;
    private LnUrl feeCollector = null;
    private final Map<String, BoundBid> negotiationListeners = new ConcurrentHashMap<>();

    public static class BoundBid {

        @Nonnull
        private final AdBidEvent bidEvent;

        @Nonnull
        private final Listener listener;

        public BoundBid(@Nonnull AdBidEvent bidEvent, @Nonnull Listener listener) {
            this.bidEvent = bidEvent;
            this.listener = listener;
        }

        public @Nonnull AdBidEvent bidEvent() {
            return bidEvent;
        }

        public @Nonnull Listener listener() {
            return listener;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BoundBid boundBid = (BoundBid) o;
            return Objects.equals(bidEvent, boundBid.bidEvent) && Objects.equals(listener, boundBid.listener);
        }

        @Override
        public int hashCode() {
            return Objects.hash(bidEvent, listener);
        }

        @Override
        public String toString() {
            return "BoundBid[bidEvent=" + bidEvent + ", listener=" + listener + "]";
        }
    }

    public DelegateService(
        @Nonnull NostrPool pool,
        @Nonnull NostrSigner signer,
        @Nullable AdTaxonomy taxonomy,
        @Nullable BiFunction<DelegateNegotiationHandler, AdOfferEvent, AsyncTask<Boolean>> filterNegotiations,
        @Nullable Function<AdBidEvent, AsyncTask<Boolean>> filterBids,
        @Nonnull PenaltyStorage penaltyStorage,
        @Nonnull Tracker tracker
    ) {
        super(pool, signer, taxonomy);
        this.tracker = tracker;
        this.filterNegotiations =
            filterNegotiations != null
                ? filterNegotiations
                : (neg, offer) -> NGEPlatform.get().wrapPromise((res, rej) -> res.accept(true));
        this.filterBids =
            filterBids != null ? filterBids : bid -> NGEPlatform.get().wrapPromise((res, rej) -> res.accept(true));
        this.penaltyStorage = penaltyStorage;
    }

    public void setFee(long minFeeMsats, double percentFee, long maxFeeMsats, LnUrl collector) {
        if (minFeeMsats < 0 || percentFee < 0 || maxFeeMsats < 0) {
            throw new IllegalArgumentException("Invalid fee values");
        }
        this.minFeeMsats = minFeeMsats;
        this.percentFee = percentFee;
        this.maxFeeMsats = maxFeeMsats;
        this.feeCollector = collector;
    }

    public AsyncTask<Void> listen(Instant since) throws Exception {
        if (since == null) {
            since = Instant.now().minus(Duration.ofMinutes(5));
        }

        String pubkeyHex = getSigner().getPublicKey().await().asHex();

        logger.info("Listening for bids since: " + since);
        NostrSubscription bidDelegationSub = getPool()
            .subscribe(new NostrFilter().withKind(AdBidEvent.KIND).withTag("D", pubkeyHex).since(since));
        bidDelegationSub.addEventListener(this::onNewBid);
        bidDelegationSub
            .open()
            .catchException(ex -> {
                logger.log(Level.SEVERE, "Error opening subscription for bids", ex);
                this.close();
            });

        NostrSubscription negotiationsSub = getPool()
            .subscribe(
                new NostrFilter().withTag("p", pubkeyHex).withKind(AdNegotiationEvent.KIND).limit(0).since(Instant.now())
            );

        negotiationsSub.addEventListener((event, stored) -> {
            String dTag = event.getFirstTag("d").get(0);
            BoundBid b = negotiationListeners.get(dTag);
            if (b == null) return; // bid not handled
            logger.info(b.bidEvent().getId() + " New negotiation event received: " + event);

            Listener listener = b.listener();
            AdBidEvent bidEvent = b.bidEvent();

            List<NostrPublicKey> bidTargets = bidEvent.getTargetedOfferers();
            List<NostrPublicKey> appTargets = bidEvent.getTargetedApps();

            AdNegotiationEvent
                .cast(getSigner(), event, null)
                .then(ev -> {
                    if (!(ev instanceof AdOfferEvent)) return null;
                    if (isClosed()) return null;
                    logger.info(b.bidEvent().getId() + " Processing offer event: " + ev.getId());
                    AdOfferEvent offer = (AdOfferEvent) ev;

                    if (bidTargets != null && !bidTargets.contains(offer.getPubkey())) {
                        logger.info(
                            b.bidEvent().getId() + " Ignoring offer from non-targeted offerer: " + offer.getPubkey().asHex()
                        );
                        return null;
                    }

                    if (appTargets != null && !appTargets.contains(offer.getAppPubkey())) {
                        logger.info(
                            b.bidEvent().getId() + " Ignoring offer from non-targeted app: " + offer.getAppPubkey().asHex()
                        );
                        return null;
                    }
                    Nip01
                        .fetch(getPool(), offer.getAppPubkey())
                        .then(nip01 -> {
                            try {
                                if (isClosed()) return null;
                                logger.info(b.bidEvent().getId() + " Nip01 fetched for offer: " + offer.getId() + ":" + nip01);
                                LnUrl lnurl = nip01.getPaymentAddress();

                                DelegateNegotiationHandler neg = new DelegateNegotiationHandler(
                                    lnurl,
                                    getPool(),
                                    getSigner(),
                                    bidEvent,
                                    getMaxDiff()
                                );

                                // --- PAYOUT LIMIT CHECK BEFORE ACCEPTING OFFER ---
                                long maxPayouts = bidEvent.getMaxPayouts();
                                long payoutResetInterval = bidEvent.getPayoutResetInterval().getSeconds();
                                String bidId = bidEvent.getId();
                                if (!tracker.canIncrement(bidId, "payouts", payoutResetInterval, maxPayouts)) {
                                    logger.warning(
                                        b.bidEvent().getId() + " Max payouts reached for bid: " + bidId + " (pre-accept)"
                                    );
                                    neg.bail(AdBailEvent.Reason.PAYOUT_LIMIT);
                                    return null;
                                }

                                neg.markAccepted();

                                this.filterNegotiations.apply(neg, offer)
                                    .compose(accepted -> {
                                        return penaltyStorage
                                            .get(neg.getBidEvent())
                                            .then(penalty -> {
                                                if (isClosed()) return null;
                                                logger.info(
                                                    b.bidEvent().getId() +
                                                    " Negotiation filter result for offer " +
                                                    offer.getId() +
                                                    ": " +
                                                    accepted
                                                );
                                                if (accepted) {
                                                    registerNegotiation(neg);
                                                    neg.addListener(listener);

                                                    neg.setCounterpartyPenalty(penalty);
                                                    if (penalty > 0) {
                                                        logger.info(
                                                            b.bidEvent().getId() +
                                                            " Negotiation has a penalty: " +
                                                            penalty +
                                                            " msats"
                                                        );
                                                    } else {
                                                        logger.info(b.bidEvent().getId() + " Negotiation has no penalty");
                                                    }

                                                    logger.info("Accepting offer: " + offer.getId());
                                                    neg.acceptOffer(offer);
                                                } else {
                                                    logger.info(
                                                        b.bidEvent().getId() +
                                                        " Negotiation rejected by filter: " +
                                                        offer.getId()
                                                    );
                                                }

                                                return null;
                                            });
                                    })
                                    .catchException(ex -> {
                                        logger.log(
                                            Level.WARNING,
                                            b.bidEvent().getId() + " Error filtering negotiation: " + bidEvent.getId(),
                                            ex
                                        );
                                        neg.close();
                                    });
                            } catch (Exception e) {
                                logger.log(
                                    Level.WARNING,
                                    b.bidEvent().getId() + " Error processing event: " + event.getId(),
                                    e
                                );
                            }
                            return null;
                        })
                        .catchException(ex -> {
                            logger.log(Level.WARNING, "Error fetching nip01 for event: " + event.getId(), ex);
                        });
                    return null;
                });
        });

        negotiationsSub.open();

        registerCloser(
            NGEPlatform
                .get()
                .registerFinalizer(
                    this,
                    () -> {
                        try {
                            bidDelegationSub.close();
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Error closing bid delegation subscription", e);
                        }
                        try {
                            negotiationsSub.close();
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Error closing negotiations subscription", e);
                        }
                    }
                )
        );

        return NGEPlatform
            .get()
            .wrapPromise((res, rej) -> {
                registerCloser(() -> res.accept(null));
            });
    }

    protected void onNewBid(SignedNostrEvent event, boolean stored) {
        if (isClosed()) return;
        AdBidEvent bid = new AdBidEvent(getTaxonomy(), event);
        if (!bid.isValid()) {
            logger.warning("Invalid bid event received: " + bid.getId());
            return;
        }

        this.filterBids.apply(bid)
            .then(accepted -> {
                if (accepted) {
                    logger.info("New bid received: " + bid.getId());
                    handleBid(bid)
                        .catchException(ex -> {
                            logger.log(Level.WARNING, "Error handling bid: " + bid.getId(), ex);
                        });
                } else {
                    logger.info("Bid rejected by filter: " + bid.getId());
                }
                return null;
            })
            .catchException(ex -> {
                logger.log(Level.WARNING, "Error applying filter to bid: " + bid.getId(), ex);
            });
    }

    private class Listener implements AdvListener {

        private final Wallet wallet;
        private final Tracker tracker;

        Listener(Wallet wallet, Tracker tracker) {
            this.wallet = wallet;
            this.tracker = tracker;
        }

        @Override
        public synchronized void onBail(NegotiationHandler neg, AdBailEvent event, boolean initiatedByCounterparty) {
            logger.info("Bail event received: " + event);
        }

        @Override
        public synchronized void onPaymentRequest(
            NegotiationHandler neg,
            AdPaymentRequestEvent event,
            String invoice,
            NotifyPayout notifyPayout
        ) {
            logger.info("Payment request event received: " + event);

            AdBidEvent bidEvent = neg.getBidEvent();
            String bidId = bidEvent.getId();
            bidEvent
                .getDecryptedDelegatePayload(getSigner())
                .then(payload -> {
                    try {
                        logger.finer("With payload: " + payload);
                        // --- DAILY BUDGET TRACKING ---
                        long dailyBudgetMsats = NGEUtils.safeLong(Objects.requireNonNull(payload.get("dailyBudget")));
                        long budgetResetInterval = 86400; // 1 day in seconds

                        if (!tracker.canIncrement(bidId, "budget", budgetResetInterval, dailyBudgetMsats)) {
                            logger.warning("Not enough daily budget left for bid: " + bidId);
                            neg.bail(AdBailEvent.Reason.OUT_OF_BUDGET);
                            return null;
                        }
                        tracker.increment(bidId, "budget", budgetResetInterval, dailyBudgetMsats);

                        // --- PAYOUT TRACKING ---
                        long maxPayouts = bidEvent.getMaxPayouts(); // Add getter to AdBidEvent if needed
                        long payoutResetInterval = bidEvent.getPayoutResetInterval().getSeconds(); // Add getter to
                        // AdBidEvent if
                        // needed

                        if (!tracker.canIncrement(bidId, "payouts", payoutResetInterval, maxPayouts)) {
                            logger.warning("Max payouts reached for bid: " + bidId);
                            neg.bail(AdBailEvent.Reason.PAYOUT_LIMIT);
                            return null;
                        }
                        tracker.increment(bidId, "payouts", payoutResetInterval, maxPayouts);

                        logger.finer("Paying invoice: " + invoice + " for " + bidEvent.getBidMsats() + " msats");
                        PayResponse res = wallet.payInvoice(invoice, bidEvent.getBidMsats()).await();
                        logger.finer("Invoice paid, preimage: " + res.preimage());

                        notifyPayout
                            .call("NOSTR-Ads: Payout for " + bidEvent.getAdId() + " completed!", res.preimage())
                            .then(v -> {
                                neg.markCompleted();
                                return null;
                            })
                            .catchException(ex -> {
                                logger.log(Level.WARNING, "Error notifying payout: " + ex.getMessage(), ex);
                                neg.bail(AdBailEvent.Reason.FAILED_PAYMENT);
                            });

                        if (feeCollector != null) {
                            long fee = Math.min(
                                maxFeeMsats,
                                Math.max(minFeeMsats, (long) (bidEvent.getBidMsats() * percentFee))
                            );
                            logger.info("Collecting fee of " + fee + " msats");
                            feeCollector
                                .getService()
                                .compose(serv -> {
                                    LnUrlPay payService = (LnUrlPay) serv;
                                    try {
                                        return payService.fetchInvoice(fee, "Delegate fee for nostr-ads", null);
                                    } catch (Exception e) {
                                        throw new RuntimeException("Failed to fetch fee invoice", e);
                                    }
                                })
                                .compose(payResp -> {
                                    String feeInvoice = payResp.getPr();
                                    return wallet.payInvoice(feeInvoice, fee);
                                })
                                .catchException(ex -> {
                                    logger.log(Level.SEVERE, "Failed to pay fee for negotiation: " + bidEvent.getId(), ex);
                                });
                        }
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Failed to process payment request: " + e.getMessage(), e);
                        neg.bail(AdBailEvent.Reason.FAILED_PAYMENT);
                    }
                    return null;
                })
                .catchException(ex -> {
                    logger.log(Level.WARNING, "Failed to decrypt delegate payload for bid: " + bidEvent.getId(), ex);
                    neg.bail(AdBailEvent.Reason.FAILED_PAYMENT);
                });
        }

        @Override
        public void onClose(NegotiationHandler neg, AdOfferEvent offer) {}
    }

    public synchronized AsyncTask<Void> handleBid(AdBidEvent bidEvent) {
        if (negotiationListeners.containsKey(bidEvent.getId())) {
            throw new IllegalStateException("Bid already being handled: " + bidEvent.getId());
        }

        logger.info("Handling bid: " + bidEvent.getId());
        return bidEvent
            .getDecryptedDelegatePayload(getSigner())
            .then(payload -> {
                try {
                    String nwc = NGEUtils.safeString(Objects.requireNonNull(payload.get("nwc")));
                    NWCWallet wallet = new NWCWallet(new NWCUri(nwc));
                    Listener listener = new Listener(wallet, tracker);
                    negotiationListeners.put(bidEvent.getId(), new BoundBid(bidEvent, listener));
                    return null;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to handle bid: " + bidEvent, e);
                }
            });
    }

    @Override
    protected void onAdCancelledById(@Nonnull String id) {
        super.onAdCancelledById(id);
        negotiationListeners.remove(id);
    }

    @Override
    protected void onAdCancelledByCoordinates(@Nonnull String addr) {
        super.onAdCancelledByCoordinates(addr);
        negotiationListeners
            .entrySet()
            .removeIf(b -> {
                AdBidEvent bidEvent = b.getValue().bidEvent();
                if (bidEvent.getCoordinates() != null && bidEvent.getCoordinates().coords().equals(addr)) {
                    logger.info("Bid event cancelled by coordinates: " + addr);
                    return true;
                }
                return false;
            });
    }
}
