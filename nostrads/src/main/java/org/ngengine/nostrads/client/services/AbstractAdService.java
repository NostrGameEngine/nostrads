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

package org.ngengine.nostrads.client.services;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.NostrEvent.TagValue;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.client.negotiation.NegotiationHandler;
import org.ngengine.nostrads.client.services.delegate.DelegateService;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.negotiation.AdBailEvent;
import org.ngengine.nostrads.protocol.negotiation.AdBailEvent.Reason;
import org.ngengine.nostrads.protocol.negotiation.AdNegotiationEvent;
import org.ngengine.nostrads.protocol.negotiation.AdOfferEvent;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.VStore;

/**
 * AbstractAdService is the base class for ad services that handle negotiations in the Ad network.
 */
public abstract class AbstractAdService implements Closeable {

    private static final Logger logger = Logger.getLogger(AbstractAdService.class.getName());
    private final NostrSigner signer;
    private int maxDiff = 32;
    private final NostrPool pool;
    private final AdTaxonomy taxonomy;
    protected final AsyncExecutor executor;
    private volatile boolean closed = false;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final List<Runnable> closers = new ArrayList<>();
    private final List<NegotiationHandler> activeNegotiations;
    private final Map<NegotiationHandler, NostrPublicKey> activeCounterparties = new ConcurrentHashMap<>();
    private final Duration negotiationAcceptanceTimeout = Duration.ofSeconds(5);
    protected static final int MAX_CANCELLATION_TOMBSTONES = 20_000;
    private static final Duration CANCELLATION_RETENTION = Duration.ofDays(31);
    private final Map<String, Instant> cancelledIds = new LinkedHashMap<>();
    private final Map<String, Instant> cancelledCoordinates = new LinkedHashMap<>();
    private final Map<String, Integer> trackedCancellationTargets = new LinkedHashMap<>();
    private final Set<String> trackedCancellationEvents = new HashSet<>();
    private final Map<String, Instant> unmatchedTombstones = new LinkedHashMap<>();
    private final Map<String, String> unmatchedTombstoneAuthors = new HashMap<>();
    private final Map<String, Integer> unmatchedTombstonesPerAuthor = new HashMap<>();
    private VStore cancellationStore;
    private static final int MAX_CANCELLATION_TAGS = 100;
    private static final int MAX_CANCELLATION_TARGETS = 10_000;
    private static final int MAX_UNMATCHED_TOMBSTONES = 2_048;
    private static final int MAX_UNMATCHED_TOMBSTONES_PER_AUTHOR = 16;
    private static final Duration UNMATCHED_TOMBSTONE_RETENTION = Duration.ofMinutes(5);
    private static final int MAX_ACTIVE_NEGOTIATIONS = 128;
    private static final int MAX_ACTIVE_NEGOTIATIONS_PER_BID = 8;
    private static final int MAX_ACTIVE_NEGOTIATIONS_PER_COUNTERPARTY = 2;

    /**
     * Constructor for AbstractAdService.
     * @param pool the NostrPool to use for network operations
     * @param signer the NostrSigner to use for signing events
     * @param taxonomy the AdTaxonomy to use for categorizing ads (null to instantiate a default taxonomy)
     */
    protected AbstractAdService(@Nonnull NostrPool pool, @Nonnull NostrSigner signer, @Nullable AdTaxonomy taxonomy) {
        if (taxonomy == null) {
            taxonomy = new AdTaxonomy();
        }
        this.signer = signer;
        this.pool = pool;
        this.taxonomy = taxonomy;
        this.activeNegotiations = new CopyOnWriteArrayList<>();
        this.executor = NGEPlatform.get().newAsyncExecutor(this.getClass());
    }

    /** Starts subscriptions only after the concrete service has initialized all of its fields. */
    protected final void startService() {
        if (!started.compareAndSet(false, true)) return;

        registerCloser(() -> {
            executor.close();
            for (NegotiationHandler negotiation : activeNegotiations) {
                try {
                    if (!negotiation.isCompleted()) {
                        negotiation
                            .bail(
                                (this instanceof DelegateService)
                                    ? AdBailEvent.Reason.CANCELLED
                                    : AdBailEvent.Reason.ACTION_INCOMPLETE
                            )
                            .then(r -> {
                                negotiation.close();
                                return null;
                            });
                    } else {
                        negotiation.close();
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error closing negotiation: " + negotiation.getBidEvent().getId(), e);
                }
            }
            activeNegotiations.clear();
            activeCounterparties.clear();
        });

        NostrSubscription cancellationSub = getPool()
            .subscribe(new NostrFilter().withKind(5).since(Instant.now().minus(Duration.ofMinutes(5))));
        cancellationSub.addEventListener((sub, ev, eose) -> processCancellationEvent(ev));
        registerCloser(() -> {
            cancellationSub.close();
        });

        AsyncTask
            .any(cancellationSub.open())
            .catchException(ex -> {
                logger.log(Level.SEVERE, "Error opening subscription for bids", ex);
                this.close();
            });

        signer
            .getPublicKey()
            .then(pubkey -> {
                if (closed) return null;

                // filter only events related to this negotiation and from the right counterparty
                NostrSubscription sub = pool.subscribe(
                    new NostrFilter().withKind(AdNegotiationEvent.KIND).withTag("p", pubkey.asHex())
                );

                sub.addEventListener((s, event, stored) -> {
                    try {
                        if (!event.verify()) return;
                    } catch (Exception verificationError) {
                        logger.log(Level.WARNING, "Rejected negotiation event with invalid signature", verificationError);
                        return;
                    }
                    TagValue offerTag = event.getFirstTag("d");
                    if (offerTag == null || offerTag.size() == 0 || offerTag.get(0) == null) return;
                    String offerId = offerTag.get(0);

                    NostrPublicKey author = event.getPubkey();
                    for (NegotiationHandler negotiation : activeNegotiations) {
                        AdOfferEvent offer = negotiation.getOffer();
                        AdBidEvent bid = negotiation.getBidEvent();
                        if (offer == null) continue;

                        NostrPublicKey counterparty = activeCounterparties.get(negotiation);
                        if (counterparty == null) continue;

                        if (offer.getId().equals(offerId) && counterparty.equals(author)) {
                            // if the event is already handled, skip it
                            if (negotiation.isClosed() || negotiation.isCompleted()) {
                                return;
                            }

                            negotiation.onEvent(event);
                            break;
                        }
                    }
                });
                registerCloser(() -> {
                    sub.close();
                });
                AsyncTask
                    .any(sub.open())
                    .catchException(ex -> {
                        logger.log(Level.SEVERE, "Error opening subscription for negotiations", ex);
                        this.close();
                    });
                return null;
            })
            .catchException(ex -> {
                logger.log(Level.SEVERE, "Error getting public key for negotiation subscription", ex);
                this.close();
            });

        this.loop();
    }

    private static String tombstoneKey(NostrPublicKey author, String target) {
        return author.asHex() + ":" + target;
    }

    protected final synchronized boolean trackCancellationTargets(@Nonnull SignedNostrEvent event) {
        String author = event.getPubkey().asHex();
        String eventKey = author + ":" + event.getId();
        if (trackedCancellationEvents.contains(eventKey)) return true;
        List<String> targets = new ArrayList<>();
        targets.add(eventKey);
        if (event.getCoordinates() != null) {
            targets.add(author + ":" + event.getCoordinates().coords());
        }
        long missing = targets.stream().filter(target -> !trackedCancellationTargets.containsKey(target)).count();
        if (trackedCancellationTargets.size() + missing > MAX_CANCELLATION_TARGETS) return false;
        trackedCancellationEvents.add(eventKey);
        for (String target : targets) {
            trackedCancellationTargets.put(target, trackedCancellationTargets.getOrDefault(target, 0) + 1);
        }
        boolean promoted = promoteUnmatchedTombstone("e:" + eventKey, eventKey, cancelledIds);
        if (event.getCoordinates() != null) {
            String coordinateKey = author + ":" + event.getCoordinates().coords();
            promoted |= promoteUnmatchedTombstone("a:" + coordinateKey, coordinateKey, cancelledCoordinates);
        }
        if (promoted) persistTombstones();
        return true;
    }

    private boolean promoteUnmatchedTombstone(String unmatchedKey, String targetKey, Map<String, Instant> destination) {
        Instant createdAt = unmatchedTombstones.remove(unmatchedKey);
        if (createdAt == null) return false;
        decrementUnmatchedAuthor(unmatchedTombstoneAuthors.remove(unmatchedKey));
        rememberTombstone(destination, targetKey, createdAt);
        return true;
    }

    private void decrementUnmatchedAuthor(String author) {
        if (author == null) return;
        int remaining = unmatchedTombstonesPerAuthor.getOrDefault(author, 0) - 1;
        if (remaining <= 0) unmatchedTombstonesPerAuthor.remove(author); else unmatchedTombstonesPerAuthor.put(
            author,
            remaining
        );
    }

    private synchronized void rememberUnmatchedTombstone(String type, String key, NostrPublicKey author, Instant createdAt) {
        pruneUnmatchedTombstones();
        String unmatchedKey = type + key;
        if (unmatchedTombstones.containsKey(unmatchedKey)) {
            unmatchedTombstones.merge(unmatchedKey, createdAt, (left, right) -> left.isAfter(right) ? left : right);
            return;
        }
        String authorHex = author.asHex();
        if (unmatchedTombstonesPerAuthor.getOrDefault(authorHex, 0) >= MAX_UNMATCHED_TOMBSTONES_PER_AUTHOR) return;
        while (unmatchedTombstones.size() >= MAX_UNMATCHED_TOMBSTONES) {
            Iterator<String> oldest = unmatchedTombstones.keySet().iterator();
            if (!oldest.hasNext()) break;
            String removed = oldest.next();
            oldest.remove();
            decrementUnmatchedAuthor(unmatchedTombstoneAuthors.remove(removed));
        }
        unmatchedTombstones.put(unmatchedKey, createdAt);
        unmatchedTombstoneAuthors.put(unmatchedKey, authorHex);
        unmatchedTombstonesPerAuthor.put(authorHex, unmatchedTombstonesPerAuthor.getOrDefault(authorHex, 0) + 1);
    }

    private synchronized void pruneUnmatchedTombstones() {
        Instant cutoff = Instant.now().minus(UNMATCHED_TOMBSTONE_RETENTION);
        Iterator<Map.Entry<String, Instant>> entries = unmatchedTombstones.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, Instant> entry = entries.next();
            if (entry.getValue().isBefore(cutoff)) {
                entries.remove();
                decrementUnmatchedAuthor(unmatchedTombstoneAuthors.remove(entry.getKey()));
            }
        }
    }

    protected final synchronized void untrackCancellationTargets(@Nonnull SignedNostrEvent event) {
        String author = event.getPubkey().asHex();
        String idKey = author + ":" + event.getId();
        if (!trackedCancellationEvents.remove(idKey)) return;
        decrementTarget(idKey);
        if (event.getCoordinates() != null) {
            String coordinateKey = author + ":" + event.getCoordinates().coords();
            decrementTarget(coordinateKey);
        }
    }

    private void decrementTarget(String key) {
        Integer references = trackedCancellationTargets.get(key);
        if (references == null) return;
        if (references <= 1) trackedCancellationTargets.remove(key); else trackedCancellationTargets.put(key, references - 1);
    }

    private synchronized void pruneTombstones() {
        Instant cutoff = Instant.now().minus(CANCELLATION_RETENTION);
        cancelledIds.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        cancelledCoordinates.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }

    private synchronized void rememberTombstone(Map<String, Instant> tombstones, String key, Instant createdAt) {
        tombstones.merge(key, createdAt, (left, right) -> left.isAfter(right) ? left : right);
        while (tombstones.size() > MAX_CANCELLATION_TOMBSTONES) {
            Iterator<String> oldest = tombstones.keySet().iterator();
            if (!oldest.hasNext()) break;
            oldest.next();
            oldest.remove();
        }
    }

    protected final synchronized void configureCancellationStore(@Nonnull VStore store) throws Exception {
        cancellationStore = store;
        if (!store.exists("nostrads/cancellation-tombstones").await()) return;
        byte[] encoded = store.readFully("nostrads/cancellation-tombstones").await();
        Map<String, Object> saved = NGEPlatform.get().fromJSON(new String(encoded, StandardCharsets.UTF_8), Map.class);
        loadTombstoneMap(saved.get("ids"), cancelledIds);
        loadTombstoneMap(saved.get("coordinates"), cancelledCoordinates);
        pruneTombstones();
    }

    private void loadTombstoneMap(Object value, Map<String, Instant> destination) {
        if (!(value instanceof Map<?, ?>)) return;
        Map<?, ?> raw = (Map<?, ?>) value;
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Number)) continue;
            String key = (String) entry.getKey();
            Number timestamp = (Number) entry.getValue();
            if (destination.size() >= MAX_CANCELLATION_TOMBSTONES) break;
            destination.put(key, Instant.ofEpochSecond(timestamp.longValue()));
        }
    }

    private synchronized void persistTombstones() {
        if (cancellationStore == null) return;
        try {
            Map<String, Long> ids = new LinkedHashMap<>();
            cancelledIds.forEach((key, value) -> ids.put(key, value.getEpochSecond()));
            Map<String, Long> coordinates = new LinkedHashMap<>();
            cancelledCoordinates.forEach((key, value) -> coordinates.put(key, value.getEpochSecond()));
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("ids", ids);
            saved.put("coordinates", coordinates);
            cancellationStore
                .writeFully(
                    "nostrads/cancellation-tombstones",
                    NGEPlatform.get().toJSON(saved).getBytes(StandardCharsets.UTF_8)
                )
                .await();
        } catch (Exception error) {
            logger.log(Level.SEVERE, "Unable to persist cancellation tombstones; stopping service fail-closed", error);
            close();
        }
    }

    protected final void processCancellationEvent(@Nonnull SignedNostrEvent event) {
        if (event.getKind() != 5 || event.getPubkey() == null || event.getCreatedAt() == null) return;
        try {
            if (!event.verify()) {
                logger.warning("Rejected cancellation event with invalid signature: " + event.getId());
                return;
            }
        } catch (Exception verificationError) {
            logger.log(Level.WARNING, "Rejected cancellation event with invalid signature", verificationError);
            return;
        }
        List<TagValue> ids = event.getTag("e");
        List<TagValue> coordinates = event.getTag("a");
        int tagCount = (ids == null ? 0 : ids.size()) + (coordinates == null ? 0 : coordinates.size());
        if (tagCount == 0 || tagCount > MAX_CANCELLATION_TAGS) {
            logger.warning("Rejected cancellation event with invalid target count");
            return;
        }
        pruneTombstones();
        boolean changed = false;
        if (ids != null) {
            for (TagValue tag : ids) {
                if (tag.size() == 0 || tag.get(0) == null) continue;
                String id = tag.get(0);
                if (!id.matches("[0-9a-fA-F]{64}")) continue;
                String key = tombstoneKey(event.getPubkey(), id);
                synchronized (this) {
                    if (!trackedCancellationTargets.containsKey(key)) {
                        rememberUnmatchedTombstone("e:", key, event.getPubkey(), event.getCreatedAt());
                        continue;
                    }
                }
                rememberTombstone(cancelledIds, key, event.getCreatedAt());
                changed = true;
                onAdCancelledById(id, event.getPubkey());
            }
        }
        if (coordinates != null) {
            for (TagValue tag : coordinates) {
                if (tag.size() == 0 || tag.get(0) == null) continue;
                String addr = tag.get(0);
                String expectedPrefix = AdBidEvent.KIND + ":" + event.getPubkey().asHex() + ":";
                if (addr.length() > 512 || !addr.startsWith(expectedPrefix)) continue;
                String key = tombstoneKey(event.getPubkey(), addr);
                synchronized (this) {
                    if (!trackedCancellationTargets.containsKey(key)) {
                        rememberUnmatchedTombstone("a:", key, event.getPubkey(), event.getCreatedAt());
                        continue;
                    }
                }
                rememberTombstone(cancelledCoordinates, key, event.getCreatedAt());
                changed = true;
                onAdCancelledByCoordinates(addr, event.getPubkey());
            }
        }
        if (changed) persistTombstones();
    }

    protected final void processCancellationEvents(@Nonnull Collection<SignedNostrEvent> events) {
        for (SignedNostrEvent event : events) processCancellationEvent(event);
    }

    protected final synchronized boolean isCancelled(@Nonnull SignedNostrEvent event) {
        String author = event.getPubkey().asHex();
        Instant idCancellation = cancelledIds.get(author + ":" + event.getId());
        if (idCancellation != null && !idCancellation.isBefore(event.getCreatedAt())) return true;
        if (event.getCoordinates() == null) return false;
        Instant coordinateCancellation = cancelledCoordinates.get(author + ":" + event.getCoordinates().coords());
        return coordinateCancellation != null && !coordinateCancellation.isBefore(event.getCreatedAt());
    }

    /**
     * Registers a negotiation handler to the active negotiations list.
     * Used to track resources and manage negotiation timeouts and cleanup.
     * @param negotiation
     */
    protected synchronized void registerNegotiation(NegotiationHandler negotiation) {
        NostrPublicKey counterparty = negotiation.getOffer() == null
            ? negotiation.getBidEvent().getDelegate()
            : negotiation.getOffer().getPubkey();
        registerNegotiation(negotiation, counterparty);
    }

    /**
     * Registers a negotiation before its acceptance event is acknowledged. The explicit
     * counterparty avoids a race where a fast reply can arrive before the offer has been
     * installed on the handler.
     */
    protected synchronized void registerNegotiation(NegotiationHandler negotiation, @Nonnull NostrPublicKey counterparty) {
        if (activeNegotiations.size() >= MAX_ACTIVE_NEGOTIATIONS) {
            throw new IllegalStateException("Active negotiation capacity reached");
        }
        long forBid = activeNegotiations
            .stream()
            .filter(active -> active.getBidEvent().getId().equals(negotiation.getBidEvent().getId()))
            .count();
        if (forBid >= MAX_ACTIVE_NEGOTIATIONS_PER_BID) {
            throw new IllegalStateException("Active negotiation capacity reached for bid");
        }
        long forCounterparty = activeNegotiations
            .stream()
            .filter(active -> counterparty.equals(activeCounterparties.get(active)))
            .count();
        if (forCounterparty >= MAX_ACTIVE_NEGOTIATIONS_PER_COUNTERPARTY) {
            throw new IllegalStateException("Active negotiation capacity reached for counterparty");
        }
        if (!(this instanceof DelegateService) && !trackCancellationTargets(negotiation.getBidEvent())) {
            throw new IllegalStateException("Cancellation-target tracking capacity reached");
        }
        this.activeNegotiations.add(negotiation);
        this.activeCounterparties.put(negotiation, counterparty);
    }

    /** Removes and closes a negotiation immediately after an admission or publish failure. */
    protected synchronized void unregisterNegotiation(NegotiationHandler negotiation) {
        activeNegotiations.remove(negotiation);
        activeCounterparties.remove(negotiation);
        if (!(this instanceof DelegateService)) untrackCancellationTargets(negotiation.getBidEvent());
        negotiation.close();
    }

    protected void onAdCancelledById(@Nonnull String id, @Nonnull NostrPublicKey cancellationAuthor) {
        for (NegotiationHandler negotiation : activeNegotiations) {
            if (
                negotiation.getBidEvent().getId().equals(id) && negotiation.getBidEvent().getPubkey().equals(cancellationAuthor)
            ) {
                if (!negotiation.isClosed() && !negotiation.isCompleted()) {
                    logger.info("Negotiation cancelled by id: " + id);
                    negotiation.bail(Reason.CANCELLED);
                }
            }
        }
    }

    protected void onAdCancelledByCoordinates(@Nonnull String coordinates, @Nonnull NostrPublicKey cancellationAuthor) {
        for (NegotiationHandler negotiation : activeNegotiations) {
            if (
                negotiation.getBidEvent().getCoordinates() != null &&
                negotiation.getBidEvent().getCoordinates().coords().equals(coordinates) &&
                negotiation.getBidEvent().getPubkey().equals(cancellationAuthor)
            ) {
                if (!negotiation.isClosed() && !negotiation.isCompleted()) {
                    logger.info("Negotiation cancelled by coordinates: " + coordinates);
                    negotiation.bail(Reason.CANCELLED);
                }
            }
        }
    }

    /**
     * Close the service and clean up resources.
     */
    public final void close() {
        List<Runnable> snapshot;
        synchronized (this) {
            if (closed) return;
            closed = true;
            snapshot = new ArrayList<>(closers);
            closers.clear();
        }
        for (Runnable closer : snapshot) {
            try {
                closer.run();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing AdClient", e);
            }
        }
    }

    /**
     * Set maximum difficulty for POW events before the client will refuse to process them.
     * Default is 32.
     * @param maxDiff
     */
    public void setMaxDiff(int maxDiff) {
        this.maxDiff = maxDiff;
    }

    // loop for cleanup and timeouts
    private void loop() {
        executor.runLater(
            () -> {
                if (closed) return null;
                for (NegotiationHandler negotiation : activeNegotiations) {
                    try {
                        if (negotiation.isCompleted()) {
                            negotiation.close();
                        } else if (
                            // check for expired hold time
                            negotiation.getCreatedAt().plus(negotiation.getBidEvent().getHoldTime()).isBefore(Instant.now()) ||
                            (
                                !negotiation.isAccepted() &&
                                negotiation.getCreatedAt().plus(negotiationAcceptanceTimeout).isBefore(Instant.now())
                            )
                        ) {
                            logger.fine("Negotiation timeouted: " + negotiation.getBidEvent().getId());
                            // bail the negotiation for timeout
                            negotiation.bail(AdBailEvent.Reason.EXPIRED).await();
                        }
                        if (negotiation.isClosed()) {
                            activeNegotiations.remove(negotiation);
                            activeCounterparties.remove(negotiation);
                            if (!(this instanceof DelegateService)) untrackCancellationTargets(negotiation.getBidEvent());
                            continue;
                        }
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error updating negotiation: " + negotiation.getBidEvent().getId(), e);
                    }
                }
                loop();
                return null;
            },
            1,
            TimeUnit.SECONDS
        );
    }

    /**
     * Get the taxonomy instance used by this service.
     * @return
     */
    protected AdTaxonomy getTaxonomy() {
        return taxonomy;
    }

    /**
     * Get the NostrSigner instance used by this service.
     * @return
     */
    protected NostrSigner getSigner() {
        return signer;
    }

    /**
     * Get the NostrPool instance used by this service.
     * @return
     */
    protected NostrPool getPool() {
        return pool;
    }

    /**
     * Register a closer to be executed when the AdClient is closed.
     * This is used to clean up resources and close connections.
     * @param closer
     */
    protected void registerCloser(Runnable closer) {
        AtomicBoolean closed = new AtomicBoolean(false);
        Runnable wrapper = () -> {
            if (closed.compareAndSet(false, true)) {
                try {
                    closer.run();
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error running closer", e);
                }
            }
        };
        NGEPlatform.get().registerFinalizer(this, wrapper);
        synchronized (this) {
            if (!this.closed) {
                closers.add(wrapper);
                return;
            }
        }
        wrapper.run();
    }

    /**
     * Get the maximum difficulty for POW events before the client will refuse to process them.
     * @return
     */
    protected int getMaxDiff() {
        return maxDiff;
    }

    protected boolean isClosed() {
        return closed;
    }
}
