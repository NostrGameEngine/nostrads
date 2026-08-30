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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.bolt11.Bolt11NetworkType;
import org.ngengine.lnurl.LnUrl;
import org.ngengine.lnurl.LnUrlPay;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip01.Nip01;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.client.negotiation.DelegateNegotiationHandler;
import org.ngengine.nostrads.client.negotiation.DelegateNegotiationHandler.AdvListener;
import org.ngengine.nostrads.client.negotiation.DelegateNegotiationHandler.NotifyPayout;
import org.ngengine.nostrads.client.negotiation.InvoiceValidator;
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
import org.ngengine.platform.VStore;
import org.ngengine.wallets.PayResponse;
import org.ngengine.wallets.nip47.NWCException;
import org.ngengine.wallets.nip47.NWCUri;

public class DelegateService extends AbstractAdService {

    private static final Logger logger = Logger.getLogger(DelegateService.class.getName());
    private final BiFunction<DelegateNegotiationHandler, AdOfferEvent, AsyncTask<Boolean>> filterNegotiations;
    private final Function<AdBidEvent, AsyncTask<Boolean>> filterBids;
    private final PenaltyStorage penaltyStorage;
    private final Tracker tracker;
    private long minFeeMsats = 0;
    private double percentFee = 0;
    private long maxFeeMsats = 10000;
    private long maxRoutingFeeMsats = 10000;
    private LnUrl feeCollector = null;
    private Bolt11NetworkType invoiceNetwork = Bolt11NetworkType.MAINNET;
    private final Map<String, BoundBid> negotiationListeners = new ConcurrentHashMap<>();
    private final Map<String, String> pendingBids = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> seenOffersByBid = new ConcurrentHashMap<>();
    private final Map<String, Integer> inFlightOffersByBid = new ConcurrentHashMap<>();
    private final Map<String, SignedNostrEvent> currentBidRevisions = new HashMap<>();
    private static final int MAX_TRACKED_BIDS = 64;
    private static final int MAX_TRACKED_BIDS_PER_ADVERTISER = 8;
    private static final int MAX_IN_FLIGHT_OFFERS_PER_BID = 8;
    private static final int MAX_SEEN_OFFERS_PER_BID = 256;
    private static final int MAX_RECOVERY_EVENTS = 20_000;
    private final Map<String, SignedNostrEvent> persistedBids = new LinkedHashMap<>();
    private VStore bidStateStore;

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

        public void close() {
            listener.close();
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
        Logger.getLogger("org.ngengine.wallets.nip47.NWCWallet").setLevel(Level.WARNING);
        this.filterNegotiations =
            filterNegotiations != null
                ? filterNegotiations
                : (neg, offer) -> NGEPlatform.get().wrapPromise((res, rej) -> res.accept(true));
        this.filterBids =
            filterBids != null ? filterBids : bid -> NGEPlatform.get().wrapPromise((res, rej) -> res.accept(true));
        this.penaltyStorage = penaltyStorage;
        registerCloser(() -> {
            negotiationListeners.values().forEach(BoundBid::close);
            negotiationListeners.clear();
        });
        startService();
    }

    public void setFee(long minFeeMsats, double percentFee, long maxFeeMsats, LnUrl collector) {
        if (minFeeMsats < 0 || !Double.isFinite(percentFee) || percentFee < 0 || percentFee > 1 || maxFeeMsats < minFeeMsats) {
            throw new IllegalArgumentException("Invalid fee values");
        }
        this.minFeeMsats = minFeeMsats;
        this.percentFee = percentFee;
        this.maxFeeMsats = maxFeeMsats;
        this.feeCollector = collector;
    }

    public void setInvoiceNetwork(@Nonnull Bolt11NetworkType invoiceNetwork) {
        this.invoiceNetwork = Objects.requireNonNull(invoiceNetwork);
    }

    public void setMaxRoutingFeeMsats(long maxRoutingFeeMsats) {
        if (maxRoutingFeeMsats < 0) throw new IllegalArgumentException("Maximum routing fee cannot be negative");
        this.maxRoutingFeeMsats = maxRoutingFeeMsats;
    }

    public void setCancellationStore(@Nonnull VStore store) throws Exception {
        configureCancellationStore(store);
        bidStateStore = store;
        if (!store.exists("nostrads/accepted-bids").await()) return;
        Map<String, Object> saved = NGEPlatform
            .get()
            .fromJSON(new String(store.readFully("nostrads/accepted-bids").await(), StandardCharsets.UTF_8), Map.class);
        for (Map.Entry<String, Object> entry : saved.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) entry.getValue();
            SignedNostrEvent event = new SignedNostrEvent(raw);
            persistedBids.put(entry.getKey(), event);
            rememberCurrentRevision(event);
        }
    }

    public AsyncTask<Void> listen(Instant since) throws Exception {
        if (since == null) {
            since = Instant.now().minus(Duration.ofMinutes(5));
        }

        String pubkeyHex = getSigner().getPublicKey().await().asHex();
        Instant recoveryUntil = Instant.now();

        logger.info("Listening for bids since: " + since);
        NostrFilter historicalBidFilter = new NostrFilter()
            .withKind(AdBidEvent.KIND)
            .withTag("D", pubkeyHex)
            .since(since)
            .until(recoveryUntil);
        List<SignedNostrEvent> historicalBids = new ArrayList<>(fetchComplete(historicalBidFilter, MAX_RECOVERY_EVENTS, false));
        Set<String> recoveredIds = new HashSet<>();
        historicalBids.forEach(event -> recoveredIds.add(event.getId()));
        persistedBids
            .values()
            .forEach(event -> {
                if (recoveredIds.add(event.getId())) historicalBids.add(event);
            });
        historicalBids.sort(
            Comparator.comparing(SignedNostrEvent::getCreatedAt).reversed().thenComparing(SignedNostrEvent::getId)
        );

        Set<String> bidAuthors = new HashSet<>();
        Map<String, Integer> acceptedPerAuthor = new HashMap<>();
        Set<String> authoritativeCoordinates = new HashSet<>();
        List<SignedNostrEvent> validHistoricalBids = new ArrayList<>();
        for (SignedNostrEvent event : historicalBids) {
            AdBidEvent bid = new AdBidEvent(getTaxonomy(), event);
            try {
                if (!event.verify()) continue;
            } catch (Exception verificationError) {
                logger.log(Level.WARNING, "Rejected historical bid with invalid signature", verificationError);
                continue;
            }
            String coordinates = bidCoordinate(bid);
            if (!authoritativeCoordinates.add(coordinates) || !claimBidRevision(bid)) continue;
            if (!bid.isValid() || bid.isExpired()) {
                continue;
            }
            if (!filterBids.apply(bid).await()) {
                continue;
            }
            String author = bid.getPubkey().asHex();
            if (
                validHistoricalBids.size() >= MAX_TRACKED_BIDS ||
                acceptedPerAuthor.getOrDefault(author, 0) >= MAX_TRACKED_BIDS_PER_ADVERTISER
            ) {
                logger.warning("Historical bid capacity reached; ignoring additional bid: " + bid.getId());
                continue;
            }
            if (!trackCancellationTargets(bid)) continue;
            acceptedPerAuthor.put(author, acceptedPerAuthor.getOrDefault(author, 0) + 1);
            validHistoricalBids.add(event);
            bidAuthors.add(event.getPubkey().asHex());
        }
        if (!bidAuthors.isEmpty()) {
            List<String> bidIds = new ArrayList<>();
            List<String> bidCoordinates = new ArrayList<>();
            for (SignedNostrEvent bid : validHistoricalBids) {
                bidIds.add(bid.getId());
                if (bid.getCoordinates() != null) bidCoordinates.add(bid.getCoordinates().coords());
            }
            List<NostrFilter> cancellationFilters = new ArrayList<>();
            NostrFilter byId = new NostrFilter()
                .withKind(5)
                .withTag("e", bidIds.toArray(new String[0]))
                .since(since)
                .until(recoveryUntil);
            for (String author : bidAuthors) byId.withAuthor(author);
            cancellationFilters.add(byId);
            if (!bidCoordinates.isEmpty()) {
                NostrFilter byCoordinates = new NostrFilter()
                    .withKind(5)
                    .withTag("a", bidCoordinates.toArray(new String[0]))
                    .since(since)
                    .until(recoveryUntil);
                for (String author : bidAuthors) byCoordinates.withAuthor(author);
                cancellationFilters.add(byCoordinates);
            }
            List<SignedNostrEvent> cancellations = fetchComplete(cancellationFilters, MAX_CANCELLATION_TOMBSTONES, false);
            processCancellationEvents(cancellations);
        }

        for (SignedNostrEvent historicalBid : validHistoricalBids) {
            try {
                processBid(historicalBid).await();
            } catch (Exception error) {
                logger.log(Level.WARNING, "Unable to restore historical bid: " + historicalBid.getId(), error);
            }
        }

        NostrSubscription bidDelegationSub = getPool()
            .subscribe(
                new NostrFilter().withKind(AdBidEvent.KIND).withTag("D", pubkeyHex).since(recoveryUntil.minusSeconds(1))
            );
        bidDelegationSub.addEventListener(this::onNewBid);
        AsyncTask
            .any(bidDelegationSub.open())
            .catchException(ex -> {
                logger.log(Level.SEVERE, "Error opening subscription for bids", ex);
                this.close();
            });

        NostrSubscription negotiationsSub = getPool()
            .subscribe(new NostrFilter().withTag("p", pubkeyHex).withKind(AdNegotiationEvent.KIND).since(Instant.now()));

        negotiationsSub.addEventListener((s, event, stored) -> {
            try {
                if (!event.verify()) {
                    logger.warning("Rejected negotiation event with invalid signature: " + event.getId());
                    return;
                }
            } catch (Exception verificationError) {
                logger.log(Level.WARNING, "Rejected negotiation event with invalid signature", verificationError);
                return;
            }
            var dTagValue = event.getFirstTag("d");
            if (dTagValue == null || dTagValue.size() == 0 || dTagValue.get(0) == null) return;
            String dTag = dTagValue.get(0);
            BoundBid b = negotiationListeners.get(dTag);
            if (b == null) return; // bid not handled
            AdBidEvent bidEvent = b.bidEvent();
            if (!isActiveBid(b)) return;
            List<NostrPublicKey> bidTargets = bidEvent.getTargetedOfferers();
            List<NostrPublicKey> appTargets = bidEvent.getTargetedApps();
            if (bidTargets != null && !bidTargets.contains(event.getPubkey())) return;
            if (!reserveInFlightOffer(bidEvent.getId())) return;

            logger.info(b.bidEvent().getId() + " New negotiation event received: " + event.getId());
            Listener listener = b.listener();

            AdNegotiationEvent
                .cast(getSigner(), event, null)
                .then(ev -> {
                    if (!(ev instanceof AdOfferEvent) || !isActiveBid(b)) {
                        releaseInFlightOffer(bidEvent.getId());
                        return null;
                    }
                    if (!rememberValidOffer(bidEvent.getId(), ev.getId())) {
                        releaseInFlightOffer(bidEvent.getId());
                        return null;
                    }
                    logger.info(b.bidEvent().getId() + " Processing offer event: " + ev.getId());
                    AdOfferEvent offer = (AdOfferEvent) ev;

                    if (bidTargets != null && !bidTargets.contains(offer.getPubkey())) {
                        logger.info(
                            b.bidEvent().getId() + " Ignoring offer from non-targeted offerer: " + offer.getPubkey().asHex()
                        );
                        releaseInFlightOffer(bidEvent.getId());
                        return null;
                    }

                    if (appTargets != null && !appTargets.contains(offer.getAppPubkey())) {
                        logger.info(
                            b.bidEvent().getId() + " Ignoring offer from non-targeted app: " + offer.getAppPubkey().asHex()
                        );
                        releaseInFlightOffer(bidEvent.getId());
                        return null;
                    }
                    if (!isActiveBid(b)) {
                        releaseInFlightOffer(bidEvent.getId());
                        return null;
                    }
                    Nip01
                        .fetch(getPool(), offer.getAppPubkey())
                        .then(nip01 -> {
                            try {
                                if (!isActiveBid(b)) return null;
                                logger.info(b.bidEvent().getId() + " Nip01 fetched for offer: " + offer.getId() + ":" + nip01);
                                LnUrl lnurl = nip01.getPaymentAddress();

                                DelegateNegotiationHandler neg = new DelegateNegotiationHandler(
                                    lnurl,
                                    getPool(),
                                    getSigner(),
                                    bidEvent,
                                    getMaxDiff(),
                                    invoiceNetwork
                                );

                                // --- PAYOUT LIMIT CHECK BEFORE ACCEPTING OFFER ---
                                long maxPayouts = bidEvent.getMaxPayouts();
                                long payoutResetInterval = bidEvent.getPayoutResetInterval().getSeconds();
                                String bidId = bidEvent.getId();
                                if (tracker.getValue(bidId, "payouts") >= maxPayouts) {
                                    logger.warning(
                                        b.bidEvent().getId() + " Max payouts reached for bid: " + bidId + " (pre-accept)"
                                    );
                                    neg.bail(AdBailEvent.Reason.PAYOUT_LIMIT, offer);
                                    releaseInFlightOffer(bidEvent.getId());
                                    return null;
                                }

                                this.filterNegotiations.apply(neg, offer)
                                    .compose(accepted -> {
                                        return penaltyStorage
                                            .get(neg.getBidEvent())
                                            .compose(penalty -> {
                                                if (!isActiveBid(b)) return AsyncTask.completed(null);
                                                logger.info(
                                                    b.bidEvent().getId() +
                                                    " Negotiation filter result for offer " +
                                                    offer.getId() +
                                                    ": " +
                                                    accepted
                                                );
                                                if (accepted) {
                                                    if (!isActiveBid(b)) return AsyncTask.completed(null);
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
                                                    // Install the handler before publishing the acceptance: a relay may
                                                    // deliver a counterparty reply before it acknowledges our publish.
                                                    registerNegotiation(neg, offer.getPubkey());
                                                    return neg
                                                        .acceptOffer(offer)
                                                        .then(ignored -> {
                                                            neg.markAccepted();
                                                            return null;
                                                        });
                                                } else {
                                                    logger.info(
                                                        b.bidEvent().getId() +
                                                        " Negotiation rejected by filter: " +
                                                        offer.getId()
                                                    );
                                                    neg.close();
                                                }

                                                return AsyncTask.completed(null);
                                            });
                                    })
                                    .then(result -> {
                                        releaseInFlightOffer(bidEvent.getId());
                                        return result;
                                    })
                                    .catchException(ex -> {
                                        releaseInFlightOffer(bidEvent.getId());
                                        logger.log(
                                            Level.WARNING,
                                            b.bidEvent().getId() + " Error filtering negotiation: " + bidEvent.getId(),
                                            ex
                                        );
                                        unregisterNegotiation(neg);
                                    });
                            } catch (Exception e) {
                                releaseInFlightOffer(bidEvent.getId());
                                logger.log(
                                    Level.WARNING,
                                    b.bidEvent().getId() + " Error processing event: " + event.getId(),
                                    e
                                );
                            }
                            return null;
                        })
                        .catchException(ex -> {
                            releaseInFlightOffer(bidEvent.getId());
                            logger.log(Level.WARNING, "Error fetching nip01 for event: " + event.getId(), ex);
                        });
                    return null;
                })
                .catchException(ex -> {
                    releaseInFlightOffer(bidEvent.getId());
                    logger.log(Level.WARNING, "Unable to decrypt negotiation event: " + event.getId(), ex);
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

    private List<SignedNostrEvent> fetchComplete(NostrFilter filter, int maximumEvents, boolean failOnLimit) throws Exception {
        return fetchComplete(List.of(filter), maximumEvents, failOnLimit);
    }

    private List<SignedNostrEvent> fetchComplete(Collection<NostrFilter> filters, int maximumEvents, boolean failOnLimit)
        throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (
            (getPool().getRelays().isEmpty() || getPool().getRelays().stream().anyMatch(relay -> !relay.isConnected())) &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(50);
        }
        Set<NostrRelay> expectedRelays = new HashSet<>(getPool().getRelays());
        if (expectedRelays.isEmpty() || expectedRelays.stream().anyMatch(relay -> !relay.isConnected())) {
            throw new IllegalStateException("Cannot perform safe recovery without all configured relays connected");
        }
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

        List<SignedNostrEvent> events = new CopyOnWriteArrayList<>();
        Set<NostrRelay> relaysAtEose = ConcurrentHashMap.newKeySet();
        NostrSubscription subscription = getPool().subscribe(filters);
        subscription.addEventListener((sub, event, stored) -> {
            if (events.size() <= maximumEvents) events.add(event);
        });
        subscription.addEoseListener((sub, relay, stored) -> relaysAtEose.add(relay));
        subscription.open();
        try {
            while (!relaysAtEose.containsAll(expectedRelays) && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            if (!relaysAtEose.containsAll(expectedRelays)) {
                throw new IllegalStateException("Relay recovery timed out before complete EOSE; refusing to start");
            }
            if (events.size() > maximumEvents && failOnLimit) {
                throw new IllegalStateException("Relay recovery exceeded its safe event limit");
            }
            if (events.size() > maximumEvents) return List.copyOf(events.subList(0, maximumEvents));
            return List.copyOf(events);
        } finally {
            subscription.close();
        }
    }

    protected void onNewBid(NostrSubscription sub, SignedNostrEvent event, boolean stored) {
        processBid(event)
            .catchException(ex -> {
                logger.log(Level.WARNING, "Error handling bid event: " + event.getId(), ex);
            });
    }

    private AsyncTask<Void> processBid(SignedNostrEvent event) {
        if (isClosed()) return AsyncTask.completed(null);
        AdBidEvent bid = new AdBidEvent(getTaxonomy(), event);
        try {
            if (!event.verify()) {
                logger.warning("Rejected bid event with invalid signature: " + event.getId());
                return AsyncTask.completed(null);
            }
        } catch (Exception verificationError) {
            logger.log(Level.WARNING, "Rejected bid event with invalid signature", verificationError);
            return AsyncTask.completed(null);
        }
        if (!claimBidRevision(bid)) {
            logger.fine("Ignoring superseded bid revision: " + bid.getId());
            return AsyncTask.completed(null);
        }
        if (!bid.isValid()) {
            logger.warning("Invalid bid event received: " + bid.getId());
            return AsyncTask.completed(null);
        }
        if (!trackCancellationTargets(bid)) {
            return AsyncTask.failed(new IllegalStateException("Cancellation-target tracking capacity reached"));
        }
        if (isCancelled(bid)) {
            logger.info("Ignoring cancelled bid: " + bid.getId());
            forgetAcceptedBid(bid.getId());
            untrackCancellationTargets(bid);
            return AsyncTask.completed(null);
        }

        AsyncTask<Void> processing =
            this.filterBids.apply(bid)
                .compose(accepted -> {
                    if (accepted) {
                        if (!isCurrentRevision(bid)) return AsyncTask.completed(null);
                        logger.info("New bid received: " + bid.getId());
                        return handleBid(bid);
                    } else {
                        logger.info("Bid rejected by filter: " + bid.getId());
                        forgetAcceptedBid(bid.getId());
                        untrackCancellationTargets(bid);
                        return AsyncTask.completed(null);
                    }
                });
        processing.catchException(ex -> {
            if (!negotiationListeners.containsKey(bid.getId())) untrackCancellationTargets(bid);
        });
        return processing;
    }

    private class Listener implements AdvListener {

        private final SecureNWCWallet wallet;
        private final Tracker tracker;

        Listener(SecureNWCWallet wallet, Tracker tracker) {
            this.wallet = wallet;
            this.tracker = tracker;
        }

        void close() {
            wallet.close();
        }

        private void refundUnusedRoutingReserve(String bidId, PayResponse payment) {
            if (payment.feesPaid() == null) return;
            long unused = maxRoutingFeeMsats - payment.feesPaid();
            if (unused > 0) tracker.refund(bidId, "budget", unused);
        }

        private boolean isDefinitiveWalletFailure(Throwable error) {
            Throwable current = error;
            while (current != null) {
                if (current instanceof NWCException) return true;
                current = current.getCause();
            }
            return false;
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
            BoundBid boundBid = negotiationListeners.get(bidId);
            if (boundBid == null || boundBid.listener() != this || !isActiveBid(boundBid)) {
                logger.warning("Ignoring payment request for inactive or cancelled bid: " + bidId);
                neg.bail(AdBailEvent.Reason.CANCELLED);
                return;
            }
            bidEvent
                .getDecryptedDelegatePayload(getSigner())
                .then(payload -> {
                    try {
                        if (!isActiveBid(boundBid)) {
                            neg.bail(AdBailEvent.Reason.CANCELLED);
                            return null;
                        }
                        logger.finer("Decrypted delegate payload for bid: " + bidId);
                        // Reserve the actual outgoing amount atomically before contacting the wallet.
                        long dailyBudgetMsats = NGEUtils.safeLong(Objects.requireNonNull(payload.get("dailyBudget")));
                        long budgetResetInterval = 86400; // 1 day in seconds
                        long payoutMsats = bidEvent.getBidMsats();
                        long feeMsats = calculateFee(payoutMsats);
                        boolean collectFee = feeCollector != null && feeMsats > 0;
                        long routingReserve = Math.multiplyExact(maxRoutingFeeMsats, collectFee ? 2L : 1L);
                        long feeOutflow = collectFee ? feeMsats : 0;
                        long totalMsats = Math.addExact(Math.addExact(payoutMsats, feeOutflow), routingReserve);
                        long maxPayouts = bidEvent.getMaxPayouts();
                        long payoutResetInterval = bidEvent.getPayoutResetInterval().getSeconds();

                        if (!tracker.tryConsume(bidId, "budget", budgetResetInterval, dailyBudgetMsats, totalMsats)) {
                            logger.warning("Not enough daily budget left for bid: " + bidId);
                            neg.bail(AdBailEvent.Reason.OUT_OF_BUDGET);
                            return null;
                        }
                        if (!tracker.tryConsume(bidId, "payouts", payoutResetInterval, maxPayouts, 1)) {
                            tracker.refund(bidId, "budget", totalMsats);
                            logger.warning("Max payouts reached for bid: " + bidId);
                            neg.bail(AdBailEvent.Reason.PAYOUT_LIMIT);
                            return null;
                        }

                        try {
                            logger.finer("Paying invoice for " + payoutMsats + " msats");
                            AsyncTask<PayResponse> paymentTask;
                            synchronized (DelegateService.this) {
                                if (!isActiveBid(boundBid)) {
                                    tracker.refund(bidId, "budget", totalMsats);
                                    tracker.refund(bidId, "payouts", 1);
                                    neg.bail(AdBailEvent.Reason.CANCELLED);
                                    return null;
                                }
                                paymentTask =
                                    wallet.payInvoice(
                                        invoice,
                                        payoutMsats,
                                        maxRoutingFeeMsats,
                                        Instant.now().plusSeconds(60),
                                        invoiceNetwork
                                    );
                            }
                            PayResponse payment = paymentTask.await();
                            refundUnusedRoutingReserve(bidId, payment);
                            logger.finer("Invoice paid");
                        } catch (Exception paymentError) {
                            if (isDefinitiveWalletFailure(paymentError)) {
                                tracker.refund(bidId, "budget", totalMsats);
                                tracker.refund(bidId, "payouts", 1);
                            } else if (collectFee) {
                                tracker.refund(bidId, "budget", Math.addExact(feeMsats, maxRoutingFeeMsats));
                            }
                            logger.log(
                                Level.SEVERE,
                                "Wallet returned an uncertain payout result; budget reservation is retained for safety",
                                paymentError
                            );
                            throw paymentError;
                        }

                        // Funds have left the wallet. Complete locally before notification so a
                        // transient relay failure cannot make the same negotiation payable again.
                        neg.markCompleted();
                        notifyPayout
                            .call("NOSTR-Ads: Payout for " + bidEvent.getAdId() + " completed!")
                            .catchException(ex -> {
                                logger.log(Level.SEVERE, "Payout completed but notification failed", ex);
                            });

                        if (collectFee) {
                            logger.info("Collecting fee of " + feeMsats + " msats");
                            AtomicBoolean feePaymentSubmitted = new AtomicBoolean(false);
                            feeCollector
                                .getService()
                                .compose(serv -> {
                                    LnUrlPay payService = (LnUrlPay) serv;
                                    try {
                                        return payService
                                            .fetchInvoice(feeMsats, "Delegate fee for nostr-ads", null)
                                            .then(payResp -> {
                                                InvoiceValidator.validateLnurlInvoice(
                                                    payResp.getPr(),
                                                    payService,
                                                    feeMsats,
                                                    invoiceNetwork
                                                );
                                                return payResp;
                                            });
                                    } catch (Exception e) {
                                        throw new RuntimeException("Failed to fetch fee invoice", e);
                                    }
                                })
                                .compose(payResp -> {
                                    String feeInvoice = payResp.getPr();
                                    feePaymentSubmitted.set(true);
                                    return wallet.payInvoice(
                                        feeInvoice,
                                        feeMsats,
                                        maxRoutingFeeMsats,
                                        Instant.now().plusSeconds(60),
                                        invoiceNetwork
                                    );
                                })
                                .then(payment -> {
                                    refundUnusedRoutingReserve(bidId, payment);
                                    return payment;
                                })
                                .catchException(ex -> {
                                    if (!feePaymentSubmitted.get() || isDefinitiveWalletFailure(ex)) {
                                        tracker.refund(bidId, "budget", Math.addExact(feeMsats, maxRoutingFeeMsats));
                                    }
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
        if (isClosed()) throw new IllegalStateException("Delegate service is closed");
        cleanupExpiredBids();
        if (!hasTrackingCapacity(bidEvent)) {
            throw new IllegalStateException("Delegate bid tracking capacity reached");
        }
        if (
            negotiationListeners.containsKey(bidEvent.getId()) ||
            pendingBids.putIfAbsent(bidEvent.getId(), bidEvent.getPubkey().asHex()) != null
        ) {
            throw new IllegalStateException("Bid already being handled: " + bidEvent.getId());
        }

        logger.info("Handling bid: " + bidEvent.getId());
        AsyncTask<Void> handling = bidEvent
            .getDecryptedDelegatePayload(getSigner())
            .then(payload -> {
                SecureNWCWallet wallet = null;
                boolean transferred = false;
                try {
                    if (isClosed()) throw new IllegalStateException("Delegate service is closed");
                    String nwc = NGEUtils.safeString(Objects.requireNonNull(payload.get("nwc")));
                    wallet = new SecureNWCWallet(new NWCUri(nwc));
                    Listener listener = new Listener(wallet, tracker);
                    synchronized (DelegateService.this) {
                        cleanupExpiredBids();
                        if (isClosed()) {
                            throw new IllegalStateException("Delegate service closed while bid was being initialized");
                        }
                        if (isCancelled(bidEvent)) {
                            throw new IllegalStateException("Bid was cancelled while being initialized");
                        }
                        if (!isCurrentRevision(bidEvent)) {
                            throw new IllegalStateException("Bid was superseded while being initialized");
                        }
                        BoundBid previous = negotiationListeners.putIfAbsent(
                            bidEvent.getId(),
                            new BoundBid(bidEvent, listener)
                        );
                        if (previous != null) {
                            throw new IllegalStateException("Bid already being handled: " + bidEvent.getId());
                        }
                        try {
                            rememberAcceptedBid(bidEvent);
                        } catch (Exception persistenceError) {
                            negotiationListeners.remove(bidEvent.getId());
                            throw persistenceError;
                        }
                        transferred = true;
                    }
                    pendingBids.remove(bidEvent.getId());
                    return null;
                } catch (Exception e) {
                    pendingBids.remove(bidEvent.getId());
                    throw new RuntimeException("Failed to handle bid: " + bidEvent, e);
                } finally {
                    if (wallet != null && !transferred) wallet.close();
                }
            });
        handling.catchException(ex -> pendingBids.remove(bidEvent.getId()));
        return handling;
    }

    @Override
    protected void onAdCancelledById(@Nonnull String id, @Nonnull NostrPublicKey cancellationAuthor) {
        super.onAdCancelledById(id, cancellationAuthor);
        BoundBid bid = negotiationListeners.get(id);
        if (bid != null && bid.bidEvent().getPubkey().equals(cancellationAuthor) && isCancelled(bid.bidEvent())) {
            negotiationListeners.remove(id);
            seenOffersByBid.remove(id);
            forgetAcceptedBid(id);
            bid.close();
            untrackCancellationTargets(bid.bidEvent());
        }
    }

    @Override
    protected void onAdCancelledByCoordinates(@Nonnull String addr, @Nonnull NostrPublicKey cancellationAuthor) {
        super.onAdCancelledByCoordinates(addr, cancellationAuthor);
        negotiationListeners
            .entrySet()
            .removeIf(b -> {
                AdBidEvent bidEvent = b.getValue().bidEvent();
                if (
                    bidEvent.getPubkey().equals(cancellationAuthor) &&
                    bidEvent.getCoordinates() != null &&
                    bidEvent.getCoordinates().coords().equals(addr) &&
                    isCancelled(bidEvent)
                ) {
                    logger.info("Bid event cancelled by coordinates: " + addr);
                    b.getValue().close();
                    seenOffersByBid.remove(bidEvent.getId());
                    forgetAcceptedBid(bidEvent.getId());
                    untrackCancellationTargets(bidEvent);
                    return true;
                }
                return false;
            });
    }

    private long calculateFee(long payoutMsats) {
        if (feeCollector == null) return 0;
        return Math.min(maxFeeMsats, Math.max(minFeeMsats, (long) (payoutMsats * percentFee)));
    }

    private synchronized void cleanupExpiredBids() {
        negotiationListeners
            .entrySet()
            .removeIf(entry -> {
                if (entry.getValue().bidEvent().isExpired()) {
                    entry.getValue().close();
                    seenOffersByBid.remove(entry.getKey());
                    forgetAcceptedBid(entry.getKey());
                    untrackCancellationTargets(entry.getValue().bidEvent());
                    return true;
                }
                return false;
            });
    }

    private boolean hasTrackingCapacity(AdBidEvent candidate) {
        if (negotiationListeners.size() + pendingBids.size() >= MAX_TRACKED_BIDS) return false;
        long advertiserBids = negotiationListeners
            .values()
            .stream()
            .filter(bound -> bound.bidEvent().getPubkey().equals(candidate.getPubkey()))
            .count();
        long pendingForAdvertiser = pendingBids.values().stream().filter(candidate.getPubkey().asHex()::equals).count();
        return advertiserBids + pendingForAdvertiser < MAX_TRACKED_BIDS_PER_ADVERTISER;
    }

    private synchronized boolean reserveInFlightOffer(String bidId) {
        int inFlight = inFlightOffersByBid.getOrDefault(bidId, 0);
        if (inFlight >= MAX_IN_FLIGHT_OFFERS_PER_BID) return false;
        inFlightOffersByBid.put(bidId, inFlight + 1);
        return true;
    }

    private synchronized boolean isActiveBid(BoundBid expected) {
        AdBidEvent bid = expected.bidEvent();
        return (
            !isClosed() && negotiationListeners.get(bid.getId()) == expected && isCurrentRevision(bid) && !isCancelled(bid)
        );
    }

    private synchronized void releaseInFlightOffer(String bidId) {
        int inFlight = inFlightOffersByBid.getOrDefault(bidId, 0);
        if (inFlight <= 1) inFlightOffersByBid.remove(bidId); else inFlightOffersByBid.put(bidId, inFlight - 1);
    }

    private boolean rememberValidOffer(String bidId, String offerId) {
        Set<String> offers = seenOffersByBid.computeIfAbsent(bidId, ignored -> new LinkedHashSet<>());
        synchronized (offers) {
            if (!offers.add(offerId)) return false;
            if (offers.size() > MAX_SEEN_OFFERS_PER_BID) {
                var iterator = offers.iterator();
                iterator.next();
                iterator.remove();
            }
            return true;
        }
    }

    private synchronized void rememberAcceptedBid(SignedNostrEvent bid) throws Exception {
        String coordinates = bidCoordinate(bid);
        persistedBids
            .entrySet()
            .removeIf(entry -> !entry.getKey().equals(bid.getId()) && bidCoordinate(entry.getValue()).equals(coordinates));
        persistedBids.put(bid.getId(), bid);
        persistAcceptedBids();
    }

    private String bidCoordinate(SignedNostrEvent bid) {
        return bid.getCoordinates() == null ? bid.getPubkey().asHex() + ":" + bid.getId() : bid.getCoordinates().coords();
    }

    private synchronized void rememberCurrentRevision(SignedNostrEvent candidate) {
        String coordinates = bidCoordinate(candidate);
        SignedNostrEvent current = currentBidRevisions.get(coordinates);
        if (current == null || compareBidRevisions(candidate, current) > 0) {
            currentBidRevisions.put(coordinates, candidate);
        }
    }

    private synchronized boolean claimBidRevision(SignedNostrEvent candidate) {
        String coordinates = bidCoordinate(candidate);
        SignedNostrEvent current = currentBidRevisions.get(coordinates);
        if (current != null) {
            if (current.getId().equals(candidate.getId())) return true;
            if (compareBidRevisions(candidate, current) <= 0) return false;
        }

        currentBidRevisions.put(coordinates, candidate);
        if (current == null) return true;

        BoundBid previous = negotiationListeners.remove(current.getId());
        pendingBids.remove(current.getId());
        seenOffersByBid.remove(current.getId());
        if (previous != null) {
            previous.close();
            untrackCancellationTargets(previous.bidEvent());
        }

        // If the previous authoritative revision was durable, persist the newer signed
        // revision even when later application validation rejects it. Otherwise a restart
        // could incorrectly resurrect the older replaceable event.
        if (persistedBids.remove(current.getId()) != null) {
            persistedBids.put(candidate.getId(), candidate);
            try {
                persistAcceptedBids();
            } catch (Exception error) {
                logger.log(Level.SEVERE, "Unable to persist authoritative bid revision; stopping fail-closed", error);
                close();
                throw new IllegalStateException("Unable to persist authoritative bid revision", error);
            }
        }
        return true;
    }

    private synchronized boolean isCurrentRevision(SignedNostrEvent candidate) {
        SignedNostrEvent current = currentBidRevisions.get(bidCoordinate(candidate));
        return current != null && current.getId().equals(candidate.getId());
    }

    private int compareBidRevisions(SignedNostrEvent left, SignedNostrEvent right) {
        int byTimestamp = left.getCreatedAt().compareTo(right.getCreatedAt());
        return byTimestamp != 0 ? byTimestamp : right.getId().compareTo(left.getId());
    }

    private synchronized void forgetAcceptedBid(String bidId) {
        if (persistedBids.remove(bidId) == null) return;
        try {
            persistAcceptedBids();
        } catch (Exception error) {
            logger.log(Level.SEVERE, "Unable to persist accepted-bid removal; stopping fail-closed", error);
            close();
        }
    }

    private void persistAcceptedBids() throws Exception {
        if (bidStateStore == null) return;
        Map<String, Object> saved = new LinkedHashMap<>();
        persistedBids.forEach((id, event) -> saved.put(id, event.toMap()));
        bidStateStore
            .writeFully("nostrads/accepted-bids", NGEPlatform.get().toJSON(saved).getBytes(StandardCharsets.UTF_8))
            .await();
    }
}
