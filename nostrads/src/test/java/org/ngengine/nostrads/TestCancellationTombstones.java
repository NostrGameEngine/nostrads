/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * conditions in the project LICENSE file are met.
 */
package org.ngengine.nostrads;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostrads.client.services.AbstractAdService;

public class TestCancellationTombstones {

    @Test
    public void onlyAuthorDeletionCancelsBid() throws Exception {
        NostrKeyPairSigner author = NostrKeyPairSigner.generate();
        NostrKeyPairSigner attacker = NostrKeyPairSigner.generate();
        Instant createdAt = Instant.now().minusSeconds(2);
        SignedNostrEvent bid = bid(author, createdAt);
        SignedNostrEvent attackerDeletion = deletion(attacker, bid, createdAt.plusSeconds(1));
        SignedNostrEvent authorDeletion = deletion(author, bid, createdAt.plusSeconds(1));
        Map<String, Object> tamperedMap = new HashMap<>(authorDeletion.toMap());
        tamperedMap.put("content", "tampered after signing");
        SignedNostrEvent tamperedDeletion = new SignedNostrEvent(tamperedMap);

        try (TestService service = new TestService(author)) {
            assertTrue(service.track(bid));
            assertTrue(new NostrFilter().withKind(5).matches(authorDeletion));
            service.remember(tamperedDeletion);
            assertFalse(service.cancelled(bid));
            service.remember(attackerDeletion);
            assertFalse(service.cancelled(bid));
            service.remember(authorDeletion);
            assertTrue(service.cancelled(bid));
        }
    }

    @Test
    public void oldCoordinateDeletionDoesNotCancelNewReplacement() throws Exception {
        NostrKeyPairSigner author = NostrKeyPairSigner.generate();
        Instant createdAt = Instant.now();
        SignedNostrEvent bid = bid(author, createdAt);
        SignedNostrEvent oldDeletion = author
            .sign(
                new UnsignedNostrEvent()
                    .withKind(5)
                    .withContent("old deletion")
                    .createdAt(createdAt.minusSeconds(1))
                    .withTag("a", bid.getCoordinates().coords())
            )
            .await();

        try (TestService service = new TestService(author)) {
            assertTrue(service.track(bid));
            service.remember(oldDeletion);
            assertFalse(service.cancelled(bid));
        }
    }

    private static SignedNostrEvent bid(NostrKeyPairSigner signer, Instant createdAt) throws Exception {
        return signer
            .sign(new UnsignedNostrEvent().withKind(30100).withContent("{}").createdAt(createdAt).withTag("d", "test-bid"))
            .await();
    }

    private static SignedNostrEvent deletion(NostrKeyPairSigner signer, SignedNostrEvent bid, Instant createdAt)
        throws Exception {
        return signer
            .sign(new UnsignedNostrEvent().withKind(5).withContent("delete").createdAt(createdAt).withTag("e", bid.getId()))
            .await();
    }

    private static final class TestService extends AbstractAdService {

        TestService(NostrKeyPairSigner signer) {
            this(new NostrPool(), signer);
        }

        private TestService(NostrPool pool, NostrKeyPairSigner signer) {
            super(pool, signer, null);
        }

        void remember(SignedNostrEvent event) {
            processCancellationEvent(event);
        }

        boolean cancelled(SignedNostrEvent event) {
            return isCancelled(event);
        }

        boolean track(SignedNostrEvent event) {
            return trackCancellationTargets(event);
        }
    }
}
