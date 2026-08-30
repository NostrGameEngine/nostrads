/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * conditions in the project LICENSE file are met.
 */
package org.ngengine.nostrads.security;

import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent.ReceivedSignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;

public class TestVerifiedNostrPool {

    @Test
    public void rejectsTamperingBeforeSuperclassEventTracking() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        SignedNostrEvent signed = signer
            .sign(new UnsignedNostrEvent().withKind(1).withContent("authentic").createdAt(Instant.now()))
            .await();
        Map<String, Object> tampered = new HashMap<>(signed.toMap());
        tampered.put("content", "tampered");
        ReceivedSignedNostrEvent received = new ReceivedSignedNostrEvent("subscription", tampered);

        ExposedPool pool = new ExposedPool();
        try {
            assertTrue(pool.receive(null, received));
        } finally {
            pool.close();
        }
    }

    @Test
    public void rejectsOversizedSignedEventBeforeExpensiveProcessing() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        SignedNostrEvent signed = signer
            .sign(new UnsignedNostrEvent().withKind(1).withContent("x".repeat(256 * 1024 + 1)).createdAt(Instant.now()))
            .await();
        ReceivedSignedNostrEvent received = new ReceivedSignedNostrEvent("subscription", signed.toMap());

        ExposedPool pool = new ExposedPool();
        try {
            assertTrue(pool.receive(null, received));
        } finally {
            pool.close();
        }
    }

    private static final class ExposedPool extends VerifiedNostrPool {

        boolean receive(NostrRelay relay, NostrMessage message) {
            return onRelayMessage(relay, message);
        }
    }
}
