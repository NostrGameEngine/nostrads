/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2026, Nostr Game Engine
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * conditions in the project LICENSE file are met.
 */
package org.ngengine.nostrads.client.services.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.pool.fetchpolicy.NostrPoolFetchPolicy;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostrads.client.advertiser.AdvertiserClient;
import org.ngengine.nostrads.client.negotiation.NegotiationHandler;
import org.ngengine.nostrads.client.negotiation.OffererNegotiationHandler;
import org.ngengine.nostrads.client.services.PenaltyStorage;
import org.ngengine.nostrads.client.services.display.fun.ShowCallback;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.negotiation.AdAcceptOfferEvent;
import org.ngengine.nostrads.protocol.negotiation.AdBailEvent;
import org.ngengine.nostrads.protocol.negotiation.AdOfferEvent;
import org.ngengine.nostrads.protocol.types.*;
import org.ngengine.platform.AsyncTask;

public class TestDisplayConfirmation {

    @Test(timeout = 10000)
    public void confirmsOnlySuccessfulRendering() throws Exception {
        check((bid, offer) -> AsyncTask.completed(true), true);
    }

    @Test(timeout = 10000)
    public void rejectedRenderingDoesNotRequestPayment() throws Exception {
        check((bid, offer) -> AsyncTask.completed(false), false);
    }

    @Test(timeout = 10000)
    public void missingRenderingResultDoesNotRequestPayment() throws Exception {
        check((bid, offer) -> AsyncTask.completed(null), false);
    }

    @Test(timeout = 10000)
    public void asynchronousRenderingFailureDoesNotRequestPayment() throws Exception {
        check((bid, offer) -> AsyncTask.failed(new IllegalStateException("Image failed to load")), false);
    }

    @Test(timeout = 10000)
    public void synchronousRenderingFailureDoesNotRequestPayment() throws Exception {
        check(
            (bid, offer) -> {
                throw new IllegalStateException("Renderer failed");
            },
            false
        );
    }

    private void check(ShowCallback render, boolean expectedSuccess) throws Exception {
        try (
            NostrKeyPair advertiserKeys = new NostrKeyPair();
            NostrKeyPair delegateKeys = new NostrKeyPair();
            NostrKeyPair offererKeys = new NostrKeyPair()
        ) {
            NostrKeyPairSigner advertiser = new NostrKeyPairSigner(advertiserKeys);
            NostrKeyPairSigner delegate = new NostrKeyPairSigner(delegateKeys);
            NostrKeyPairSigner offerer = new NostrKeyPairSigner(offererKeys);
            AdTaxonomy taxonomy = new AdTaxonomy();
            BidPool pool = new BidPool();
            try {
                AdvertiserClient publisher = new AdvertiserClient(pool, advertiser, taxonomy);
                pool.bid =
                    publisher
                        .newBid(
                            "display-confirmation",
                            "Rendering regression",
                            null,
                            null,
                            null,
                            null,
                            null,
                            AdMimeType.TEXT_PLAIN,
                            "Test advertisement",
                            AdSize.RECTANGLE_256x256,
                            "https://example.com",
                            null,
                            AdActionType.ACTION,
                            1000,
                            Duration.ofMinutes(5),
                            delegate.getPublicKey().await(),
                            null,
                            Instant.now().plusSeconds(300),
                            3,
                            Duration.ofMinutes(5)
                        )
                        .await();
                try (CapturingClient client = new CapturingClient(pool, offerer, taxonomy)) {
                    NostrPublicKey app = offerer.getPublicKey().await();
                    Adspace space = new Adspace(
                        app,
                        app,
                        AdAspectRatio.RATIO_1_1,
                        AdPriceSlot.BTC1_000,
                        List.of(AdMimeType.TEXT_PLAIN)
                    );
                    client.registerAdspace(space);
                    AsyncTask<AdBidEvent> loading = client.loadNextAd(
                        space,
                        256,
                        256,
                        bid -> AsyncTask.completed(true),
                        render,
                        null
                    );
                    assertTrue("Display listener was not installed", client.opened.await(5, TimeUnit.SECONDS));
                    AdOfferEvent offer = new AdOfferEvent.OfferBuilder(app).build(offerer, pool.bid).await();
                    AdAcceptOfferEvent accepted = new AdAcceptOfferEvent.AdAcceptOfferBuilder()
                        .build(delegate, offer, 0)
                        .await();
                    CountDownLatch finished = new CountDownLatch(1);
                    AtomicInteger notified = new AtomicInteger();
                    try (RecordingNegotiation negotiation = new RecordingNegotiation(app, pool, offerer, pool.bid, finished)) {
                        negotiation.open(offer);
                        client.listener.showAd(
                            negotiation,
                            accepted,
                            message -> {
                                notified.incrementAndGet();
                                finished.countDown();
                            }
                        );
                        assertTrue("Rendering result was not handled", finished.await(5, TimeUnit.SECONDS));
                        assertEquals(expectedSuccess ? 1 : 0, notified.get());
                        assertEquals(expectedSuccess ? 0 : 1, negotiation.bails.get());
                    }
                    loading.cancel();
                }
            } finally {
                pool.clean();
            }
        }
    }

    private static class BidPool extends NostrPool {

        AdBidEvent bid;

        @Override
        public AsyncTask<List<SignedNostrEvent>> fetch(Collection<NostrFilter> filters, NostrPoolFetchPolicy policy) {
            return AsyncTask.completed(List.of(bid));
        }
    }

    private static class CapturingClient extends AdsDisplayClient {

        final CountDownLatch opened = new CountDownLatch(1);
        volatile OffererNegotiationHandler.OfferListener listener;

        CapturingClient(NostrPool pool, NostrKeyPairSigner signer, AdTaxonomy taxonomy) {
            super(
                pool,
                signer,
                taxonomy,
                new PenaltyStorage(null) {
                    @Override
                    public AsyncTask<Integer> get(AdBidEvent event) {
                        return AsyncTask.completed(0);
                    }
                },
                (neg, offer, reason) -> {}
            );
        }

        @Override
        public AsyncTask<NegotiationHandler> openNegotiation(
            NostrPublicKey app,
            AdBidEvent bid,
            OffererNegotiationHandler.OfferListener listener
        ) {
            this.listener = listener;
            opened.countDown();
            return AsyncTask.failed(new IllegalStateException("Network transport is outside this rendering test"));
        }
    }

    private static class RecordingNegotiation extends OffererNegotiationHandler implements AutoCloseable {

        final AtomicInteger bails = new AtomicInteger();
        final CountDownLatch finished;

        RecordingNegotiation(
            NostrPublicKey app,
            NostrPool pool,
            NostrKeyPairSigner signer,
            AdBidEvent bid,
            CountDownLatch finished
        ) {
            super(app, pool, signer, bid, 0);
            this.finished = finished;
        }

        @Override
        public AsyncTask<Void> bail(AdBailEvent.Reason reason) {
            assertEquals(AdBailEvent.Reason.ACTION_INCOMPLETE, reason);
            bails.incrementAndGet();
            finished.countDown();
            return AsyncTask.completed(null);
        }
    }
}
