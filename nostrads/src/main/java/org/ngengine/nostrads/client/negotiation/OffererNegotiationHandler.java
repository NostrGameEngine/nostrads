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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.negotiation.AdAcceptOfferEvent;
import org.ngengine.nostrads.protocol.negotiation.AdNegotiationEvent;
import org.ngengine.nostrads.protocol.negotiation.AdOfferEvent;
import org.ngengine.nostrads.protocol.negotiation.AdPaymentRequestEvent;
import org.ngengine.nostrads.protocol.negotiation.AdPayoutEvent;
import org.ngengine.platform.AsyncTask;

/**
 * Handle negotiation from the side of an offerer in the Ad network.
 */
public class OffererNegotiationHandler extends NegotiationHandler {

    private static final Logger logger = Logger.getLogger(OffererNegotiationHandler.class.getName());
    private final NostrPublicKey appKey;

    public static interface OfferListener extends NegotiationHandler.Listener {
        void verifyPayout(NegotiationHandler neg, AdPayoutEvent event);
        void onRequestingPayment(NegotiationHandler neg);
        void showAd(NegotiationHandler neg, AdAcceptOfferEvent acp, Consumer<String> notifyShown);
    }

    public OffererNegotiationHandler(
        @Nonnull NostrPublicKey appKey,
        @Nonnull NostrPool pool,
        @Nonnull NostrSigner signer,
        @Nonnull AdBidEvent bidding,
        int maxDiff
    ) {
        super(pool, signer, bidding, maxDiff);
        this.appKey = appKey;
    }

    @Override
    protected void onEvent(AdNegotiationEvent event) {
        if (isClosed()) return;
        super.onEvent(event);
        if (event instanceof AdAcceptOfferEvent) {
            // show ad and request payment
            AdAcceptOfferEvent acceptEvent = (AdAcceptOfferEvent) event;
            logger.fine("Received accept offer event: " + acceptEvent.getId() + " for bidding: " + getBidEvent().getId());
            AtomicBoolean done = new AtomicBoolean(false);
            for (Listener listener : getListeners()) {
                if (listener instanceof OfferListener) {
                    ((OfferListener) listener).showAd(
                            this,
                            acceptEvent,
                            msg -> {
                                if (!done.getAndSet(true)) {
                                    logger.fine(
                                        "Ad was shown " +
                                        msg +
                                        " for bidding: " +
                                        getBidEvent().getId() +
                                        " ... requesting payment"
                                    );
                                    for (Listener l : getListeners()) {
                                        if (l instanceof OfferListener) {
                                            ((OfferListener) l).onRequestingPayment(this);
                                        }
                                    }
                                    requestPayment(msg);
                                }
                            }
                        );
                }
            }
        } else if (event instanceof AdPayoutEvent) {
            //  notify listeners
            AdPayoutEvent payoutEvent = (AdPayoutEvent) event;
            logger.fine("Received payout event: " + payoutEvent.getId() + " for bidding: " + getBidEvent().getId());
            for (Listener listener : getListeners()) {
                if (listener instanceof OfferListener) {
                    ((OfferListener) listener).verifyPayout(this, payoutEvent);
                }
            }
        }
    }

    /**
     * Make an offer for the bid associated with this negotiation handler.
     * @return an AsyncTask that resolves to the AdOfferEvent created for the offer
     */
    public AsyncTask<Void> makeOffer() {
        logger.fine("Making an offer for bidding: " + getBidEvent().getId());
        return getSigner()
            .getPublicKey()
            .compose(pubkey -> {
                if (pubkey.equals(getBidEvent().getPubkey())) {
                    throw new IllegalArgumentException("You cannot offer to yourself");
                }
                AdOfferEvent.OfferBuilder builder = new AdOfferEvent.OfferBuilder(appKey);
                // request a pow difficulty from the counterparty if applicable
                builder.requestDifficulty(getCounterpartyPenalty());
                builder.withExpiration(Instant.now().plus(getBidEvent().getHoldTime()));
                return builder.build(getSigner(), getBidEvent());
            })
            .compose(sevent -> {
                logger.fine("Sending offer event for bid: " + getBidEvent().getId() + ": " + sevent);

                // initialize with this offer
                return open(sevent)
                    .compose(sub -> {
                        if (isClosed()) return null;
                        // publish the offer event and return it
                        return getPool()
                            .publish(sevent);
                           
                    }).then(r->{
                        return null;
                    });
                });
    }

    /**
     * Request a payment for the bidding associated with this negotiation handler.
     * @param message a message to include in the payment request event
     * @return an AsyncTask that will complete when the payment request event is published
     */
    protected AsyncTask<Void> requestPayment(String message) {
        logger.fine("Requesting payment for bidding: " + getBidEvent().getId() + " with message: " + message);
        AdPaymentRequestEvent.PaymentRequestBuilder builder = new AdPaymentRequestEvent.PaymentRequestBuilder();

        builder.withExpiration(Instant.now().plus(getBidEvent().getHoldTime()));

        builder.withMessage(message);

        return builder
            .build(getSigner(), getOffer(), getLocalPenalty())
            .compose(sevent -> {
                logger.fine("Sending payment request event for bid: " + getBidEvent().getId() + ": " + sevent);
                return getPool()
                    .publish(sevent)
                    .then(ack -> {
                        return null;
                    });
            });
    }
}
