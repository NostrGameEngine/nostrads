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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.NostrEvent.TagValue;
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
import org.ngengine.platform.NGEPlatform;

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
    private final List<Runnable> closers = new ArrayList<>();
    private final List<NegotiationHandler> activeNegotiations;
    private final Duration negotiationAcceptanceTimeout = Duration.ofSeconds(5);

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

        AsyncExecutor updater = NGEPlatform.get().newAsyncExecutor(this.getClass());

        registerCloser(() -> {
            updater.close();
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
        });

        NostrSubscription cancellationSub = getPool()
            .subscribe(new NostrFilter().withKind(5).withTag("k", String.valueOf(AdBidEvent.KIND)));
        cancellationSub.addEventListener((sub, ev, eose) -> {
            List<TagValue> cancelledIds = ev.getTag("e");
            if (cancelledIds != null) {
                for (TagValue cancelledId : cancelledIds) {
                    String id = cancelledId.get(0);
                    onAdCancelledById(id);
                }
            }

            List<TagValue> cancelledAddrs = ev.getTag("a");
            if (cancelledAddrs != null) {
                for (TagValue cancelledAddr : cancelledAddrs) {
                    String addr = cancelledAddr.get(0);
                    onAdCancelledByCoordinates(addr);
                }
            }
        });
        registerCloser(() -> {
            cancellationSub.close();
        });

        cancellationSub
            .open()
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
                    String offerId = event.getFirstTag("d").get(0);
                    if (offerId == null) return;

                    NostrPublicKey author = event.getPubkey();
                    for (NegotiationHandler negotiation : activeNegotiations) {
                        AdOfferEvent offer = negotiation.getOffer();
                        AdBidEvent bid = negotiation.getBidEvent();

                        // if delegate side: the counterparty is the author of the offer
                        // if offer side: the counterparty is the delegate
                        NostrPublicKey counterparty = pubkey.equals(bid.getDelegate()) ? offer.getPubkey() : bid.getDelegate();

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
                sub
                    .open()
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

        this.executor = updater;
        this.loop();
    }

    /**
     * Registers a negotiation handler to the active negotiations list.
     * Used to track resources and manage negotiation timeouts and cleanup.
     * @param negotiation
     */
    protected void registerNegotiation(NegotiationHandler negotiation) {
        this.activeNegotiations.add(negotiation);
    }

    protected void onAdCancelledById(@Nonnull String id) {
        for (NegotiationHandler negotiation : activeNegotiations) {
            if (negotiation.getBidEvent().getId().equals(id)) {
                if (!negotiation.isClosed() && !negotiation.isCompleted()) {
                    logger.info("Negotiation cancelled by id: " + id);
                    negotiation.bail(Reason.CANCELLED);
                }
            }
        }
    }

    protected void onAdCancelledByCoordinates(@Nonnull String coordinates) {
        for (NegotiationHandler negotiation : activeNegotiations) {
            if (negotiation.getBidEvent().getCoordinates().coords().equals(coordinates)) {
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
        closed = true;
        for (Runnable closer : closers) {
            try {
                closer.run();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing AdClient", e);
            }
        }
        closers.clear();
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
        if (closers == null) {
            throw new IllegalStateException("AdClient is closing");
        }
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
        closers.add(wrapper);
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
