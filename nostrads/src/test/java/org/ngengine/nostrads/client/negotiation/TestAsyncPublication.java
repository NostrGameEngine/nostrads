/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * conditions in the project LICENSE file are met.
 */
package org.ngengine.nostrads.client.negotiation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.ngengine.bolt11.Bolt11NetworkType;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostrads.client.advertiser.AdvertiserClient;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.negotiation.AdBailEvent;
import org.ngengine.nostrads.protocol.negotiation.AdOfferEvent;
import org.ngengine.nostrads.protocol.types.AdActionType;
import org.ngengine.nostrads.protocol.types.AdMimeType;
import org.ngengine.nostrads.protocol.types.AdSize;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

/** Offline coverage for every consumer of the asynchronous NostrPool.publish result. */
@RunWith(Parameterized.class)
public class TestAsyncPublication {

    @Parameterized.Parameters(name = "{0}")
    public static List<String> publications() {
        return Arrays.asList("bid", "cancel-bid", "cancel-id", "offer", "payment", "accept", "payout", "bail");
    }

    private final String operation;
    private final ControlledPool pool = new ControlledPool();
    private final List<NostrKeyPair> keyPairs = new ArrayList<>();
    private AdvertiserClient advertiser;
    private OffererNegotiationHandler offerer;
    private DelegateNegotiationHandler delegate;
    private AdBidEvent bid;
    private AdOfferEvent offer;

    public TestAsyncPublication(String operation) {
        this.operation = operation;
    }

    @Before
    public void createNegotiation() throws Exception {
        NostrKeyPairSigner advertiserSigner = newSigner();
        NostrKeyPairSigner delegateSigner = newSigner();
        NostrKeyPairSigner offererSigner = newSigner();
        advertiser = new AdvertiserClient(pool, advertiserSigner, new AdTaxonomy());
        bid =
            advertiser
                .newBid(
                    "async-publication",
                    "Publication regression",
                    null,
                    null,
                    null,
                    null,
                    null,
                    AdMimeType.TEXT_PLAIN,
                    "Test advertisement",
                    AdSize.HORIZONTAL_480x60,
                    "https://example.com",
                    null,
                    AdActionType.VIEW,
                    1000,
                    Duration.ofMinutes(5),
                    delegateSigner.getPublicKey().await(),
                    null,
                    Instant.now().plusSeconds(300),
                    3,
                    Duration.ofMinutes(5)
                )
                .await();
        offer = new AdOfferEvent.OfferBuilder(offererSigner.getPublicKey().await()).build(offererSigner, bid).await();
        offerer = new OffererNegotiationHandler(offererSigner.getPublicKey().await(), pool, offererSigner, bid, 0);
        offerer.open(offer);
        delegate = new DelegateNegotiationHandler(null, pool, delegateSigner, bid, 0, Bolt11NetworkType.MAINNET);
        delegate.open(offer);
    }

    @After
    public void closeNegotiation() {
        if (offerer != null) offerer.close();
        if (delegate != null) delegate.close();
        pool.publication.task.cancel();
        if (pool.relayResults != null) pool.relayResults.forEach(AsyncTask::cancel);
        pool.clean();
        keyPairs.forEach(NostrKeyPair::close);
    }

    private NostrKeyPairSigner newSigner() {
        NostrKeyPair keyPair = new NostrKeyPair();
        keyPairs.add(keyPair);
        return new NostrKeyPairSigner(keyPair);
    }

    @Test(timeout = 10000)
    public void waitsForOuterPublicationAndEveryAcknowledgement() throws Exception {
        Pending<NostrMessageAck> late = new Pending<>();
        List<AsyncTask<NostrMessageAck>> acknowledgements = Arrays.asList(completed(ack(true)), late.task);
        AsyncTask<?> result = publish();
        assertPending(result);
        pool.publication.resolve(acknowledgements);
        assertPending(result);
        late.resolve(ack(true));
        Object value = result.await();
        if (!isNegotiation()) assertEquals(acknowledgements, value);
        if (operation.equals("bail")) assertTrue(offerer.isClosed());
    }

    @Test(timeout = 10000)
    public void propagatesOuterPublicationFailure() throws Exception {
        IllegalStateException failure = new IllegalStateException("Publication policy rejected the event");
        AsyncTask<?> result = publish();
        pool.publication.reject(failure);
        assertSame(failure, rootCause(assertThrows(Exception.class, result::await)));
        assertFalse(offerer.isClosed());
    }

    @Test(timeout = 10000)
    public void preservesMixedRelayResultsAndAcceptsOneSuccess() throws Exception {
        Pending<NostrMessageAck> rejected = new Pending<>();
        rejected.reject(new IllegalStateException("Relay unavailable"));
        List<AsyncTask<NostrMessageAck>> acknowledgements = Arrays.asList(
            rejected.task,
            completed(ack(false)),
            completed(ack(true))
        );
        AsyncTask<?> result = publish();
        pool.publication.resolve(acknowledgements);
        Object value = result.await();
        if (!isNegotiation()) {
            assertEquals(acknowledgements, value);
            assertTrue(acknowledgements.get(0).isFailed());
            assertEquals(NostrMessageAck.Status.FAILURE, acknowledgements.get(1).await().getStatus());
        }
    }

    @Test(timeout = 10000)
    public void preservesNegotiationRejectionWithoutSuccessfulRelay() throws Exception {
        Pending<NostrMessageAck> rejected = new Pending<>();
        rejected.reject(new IllegalStateException("Relay unavailable"));
        List<AsyncTask<NostrMessageAck>> acknowledgements = Arrays.asList(rejected.task, completed(ack(false)));
        AsyncTask<?> result = publish();
        pool.publication.resolve(acknowledgements);
        if (isNegotiation()) {
            Throwable failure = rootCause(assertThrows(Exception.class, result::await));
            assertEquals("No relay acknowledged the negotiation event", failure.getMessage());
            assertFalse(offerer.isClosed());
        } else {
            assertEquals(acknowledgements, result.await());
        }
    }

    @Test(timeout = 10000)
    public void preservesEmptyRelayBehavior() throws Exception {
        AsyncTask<?> result = publish();
        pool.publication.resolve(Collections.emptyList());
        if (isNegotiation()) {
            assertThrows(Exception.class, result::await);
            assertFalse(offerer.isClosed());
        } else {
            assertEquals(Collections.emptyList(), result.await());
        }
    }

    @Test(timeout = 10000)
    public void realPoolPolicyRejectsWhenEveryRelayFails() throws Exception {
        Pending<NostrMessageAck> rejected = new Pending<>();
        rejected.reject(new IllegalStateException("Relay unavailable"));
        pool.relayResults = Arrays.asList(rejected.task, completed(ack(false)));
        AsyncTask<?> result = publish();
        Throwable failure = rootCause(assertThrows(Exception.class, result::await));
        assertEquals("Failed to achieve required acknowledgements", failure.getMessage());
        assertFalse(offerer.isClosed());
    }

    @Test(timeout = 10000)
    public void realPoolPolicySuccessStillWaitsForRemainingRelay() throws Exception {
        Pending<NostrMessageAck> late = new Pending<>();
        pool.relayResults = Arrays.asList(completed(ack(true)), late.task);
        AsyncTask<?> result = publish();
        assertPending(result);
        late.reject(new IllegalStateException("Other relay unavailable"));
        Object value = result.await();
        if (!isNegotiation()) assertEquals(pool.relayResults, value);
    }

    private AsyncTask<?> publish() throws Exception {
        AsyncTask<?> result;
        switch (operation) {
            case "bid":
                result = advertiser.publishBid(bid);
                break;
            case "cancel-bid":
                result = advertiser.cancelBid(bid, "test cancellation");
                break;
            case "cancel-id":
                result = advertiser.cancelBid(bid.getId(), "test cancellation");
                break;
            case "offer":
                result = offerer.makeOffer();
                break;
            case "payment":
                result = offerer.requestPayment("test request");
                break;
            case "accept":
                result = delegate.acceptOffer(offer);
                break;
            case "payout":
                result = delegate.notifyPayout("test notification");
                break;
            case "bail":
                result = offerer.bail(AdBailEvent.Reason.FAILED_PAYMENT);
                break;
            default:
                throw new AssertionError(operation);
        }
        assertTrue("Publication was never reached", pool.called.await(5, TimeUnit.SECONDS));
        return result;
    }

    private boolean isNegotiation() {
        return !(operation.equals("bid") || operation.startsWith("cancel-"));
    }

    private static void assertPending(AsyncTask<?> task) throws Exception {
        CountDownLatch settled = new CountDownLatch(1);
        task
            .then(value -> {
                settled.countDown();
                return null;
            })
            .catchException(error -> settled.countDown());
        assertFalse("Publication completed before all acknowledgements settled", settled.await(100, TimeUnit.MILLISECONDS));
        assertFalse(task.isDone());
    }

    private static Throwable rootCause(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error;
    }

    private static NostrMessageAck ack(boolean success) {
        NostrMessageAck acknowledgement = NostrMessage.ack(null, "test-event", Instant.now(), null, null);
        if (success) acknowledgement.callSuccessCallback("accepted"); else acknowledgement.callFailureCallback("rejected");
        return acknowledgement;
    }

    private static <T> AsyncTask<T> completed(T value) {
        return NGEPlatform.get().wrapPromise((resolve, reject) -> resolve.accept(value));
    }

    private static final class Pending<T> {

        private Consumer<T> resolve;
        private Consumer<Throwable> reject;
        private final CountDownLatch ready = new CountDownLatch(1);
        private final AsyncTask<T> task = NGEPlatform
            .get()
            .wrapPromise((resolve, reject) -> {
                this.resolve = resolve;
                this.reject = reject;
                ready.countDown();
            });

        void resolve(T value) throws Exception {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            resolve.accept(value);
        }

        void reject(Throwable error) throws Exception {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            reject.accept(error);
        }
    }

    private static final class ControlledPool extends NostrPool {

        private final Pending<List<AsyncTask<NostrMessageAck>>> publication = new Pending<>();
        private final CountDownLatch called = new CountDownLatch(1);
        private List<AsyncTask<NostrMessageAck>> relayResults;

        @Override
        public AsyncTask<List<AsyncTask<NostrMessageAck>>> publish(SignedNostrEvent event) {
            called.countDown();
            return relayResults == null ? publication.task : super.publish(event);
        }

        @Override
        protected List<AsyncTask<NostrMessageAck>> sendMessage(NostrMessage message) {
            return relayResults;
        }
    }
}
