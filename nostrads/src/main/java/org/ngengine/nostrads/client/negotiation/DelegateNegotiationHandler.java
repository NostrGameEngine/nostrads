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
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.lnurl.LnUrl;
import org.ngengine.lnurl.LnUrlPay;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.negotiation.AdAcceptOfferEvent;
import org.ngengine.nostrads.protocol.negotiation.AdBailEvent;
import org.ngengine.nostrads.protocol.negotiation.AdNegotiationEvent;
import org.ngengine.nostrads.protocol.negotiation.AdOfferEvent;
import org.ngengine.nostrads.protocol.negotiation.AdPaymentRequestEvent;
import org.ngengine.nostrads.protocol.negotiation.AdPayoutEvent;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

/**
 * Handle negotiation from the side of a delegate in the Ad network.
 */
public class DelegateNegotiationHandler extends NegotiationHandler {

    private static final Logger logger = Logger.getLogger(DelegateNegotiationHandler.class.getName());
    private final LnUrl lnurl;
    private final AtomicBoolean paid = new AtomicBoolean(false);

    /**
     * Callback to notify a payout to the offerer
     */
    public static interface NotifyPayout {
        AsyncTask<Void> call(String message, String preimage);
    }

    public static interface AdvListener extends NegotiationHandler.Listener {
        void onPaymentRequest(NegotiationHandler neg, AdPaymentRequestEvent event, String invoice, NotifyPayout notifyPayout);
    }

    public DelegateNegotiationHandler(
        @Nonnull LnUrl lnurl,
        @Nonnull NostrPool pool,
        @Nonnull NostrSigner signer,
        @Nonnull AdBidEvent bid,
        int maxDiff
    ) {
        super(pool, signer, bid, maxDiff);
        this.lnurl = lnurl;
    }

    /**
     * Handle delegate events
     */
    @Override
    protected void onEvent(AdNegotiationEvent event) {
        if (isClosed()) return;
        try {
            // the offerer is requesting us to pay
            if (event instanceof AdPaymentRequestEvent) {
                // avoid double payments
                if (paid.getAndSet(true)) {
                    logger.warning("Received a payment request event after already paid, ignoring: " + event.getId());
                    return; // we already paid, ignore this event
                }
                logger.fine("Received payment request event: " + event.getId() + " for bidding: " + getBidEvent().getId());
                AdPaymentRequestEvent paymentRequestEvent = (AdPaymentRequestEvent) event;
                logger.fine("Initiating payout to "+lnurl);
                lnurl
                    .getService()
                    .then(r -> {
                        try {
                            logger.fine(
                                "Got LnUrl service: " +
                                r +
                                ", fetching invoice for payment request: " +
                                paymentRequestEvent.getId()
                            );
                            LnUrlPay payRequest = (LnUrlPay) r;
                            // fetch an invoice
                            payRequest
                                .fetchInvoice(getBidEvent().getBidMsats(), "Payment for " + this.getBidEvent().getId(), null)
                                .then(res -> {
                                    String invoice = res.getPr();
                                    logger.fine(
                                        "Fetched invoice for payment request: " + paymentRequestEvent.getId() + ": " + invoice
                                    );
                                    AtomicBoolean done = new AtomicBoolean(false); // we make sure to notify only once even if we have many listeners
                                    for (Listener listener : getListeners()) {
                                        if (listener instanceof AdvListener) {
                                            ((AdvListener) listener).onPaymentRequest(
                                                    this,
                                                    paymentRequestEvent,
                                                    invoice,
                                                    (message, preimage) -> {
                                                        if (!done.getAndSet(true)) {
                                                            return notifyPayout(message, preimage);
                                                        }
                                                        return NGEPlatform
                                                            .get()
                                                            .wrapPromise((res2, rej2) -> {
                                                                res2.accept(null);
                                                            });
                                                    }
                                                );
                                        }
                                    }
                                    return null;
                                })
                                .catchException(e -> {
                                    logger.log(Level.WARNING, "Failed to fetch invoice for payment request", e);
                                    // we can't get an invoice, so we bail out
                                    bail(AdBailEvent.Reason.FAILED_PAYMENT);
                                });
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to process LnUrl service: " + e.getMessage(), e);
                        }
                        return null;
                    })
                    .catchException(e -> {
                        logger.log(Level.WARNING, "Failed to get LnUrl service", e);
                        // we can't get the lnurlp service, so we bail out
                        bail(AdBailEvent.Reason.FAILED_PAYMENT);
                    });
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error processing event", e);
        }
    }

    /**
     * Accept an offer from the counterparty and open the negotiation with it.
     * @param offer
     * @return an AsyncTask that will complete when the accept event is published
     */
    public AsyncTask<Void> acceptOffer(AdOfferEvent offer) {
        return getSigner()
            .getPublicKey()
            .compose(pubkey -> {
                // ensure the counterparty is valid
                if (pubkey.equals(offer.getPubkey())) {
                    throw new IllegalArgumentException("You cannot accept your own offer");
                }
                if (!pubkey.equals(getBidEvent().getDelegate())) {
                    throw new IllegalArgumentException("You can only accept offers for a bidding you are a delegate for");
                }

                // open negotiation with offer
                open(offer);
                // create accept event
                AdAcceptOfferEvent.AdAcceptOfferBuilder builder = new AdAcceptOfferEvent.AdAcceptOfferBuilder();
                builder.requestDifficulty(getCounterpartyPenalty());
                builder.withExpiration(Instant.now().plus(getBidEvent().getHoldTime()));

                return builder.build(getSigner(), offer, getLocalPenalty());
            })
            .then(sevent -> {
                logger.fine("Sending accept offer event for bid: " + getBidEvent().getId() + ": " + sevent);
                // publish the accept event and return
                getPool()
                    .publish(sevent)
                    .then(ack -> {
                        return sevent;
                    });

                return null;
            });
    }

    /**
     * Notify the offerer of a payout.
     *
     * @param message a message to include in the payout event
     * @param preimage the preimage of the payment request, used to prove the payment
     * @return an AsyncTask that will complete when the payout event is published
     */
    protected AsyncTask<Void> notifyPayout(String message, String preimage) {
        AdPayoutEvent.PayoutBuilder builder = new AdPayoutEvent.PayoutBuilder();

        builder.withExpiration(Instant.now().plus(getBidEvent().getHoldTime()));
        builder.withMessage(message);
        builder.withPreimage(preimage);

        return builder
            .build(getSigner(), this.getOffer())
            .compose(ev -> {
                logger.fine("Sending notify payout event for bid: " + getBidEvent().getId() + ": " + ev);

                return getPool()
                    .publish(ev)
                    .then(ack -> {
                        return null;
                    });
            });
    }
}
