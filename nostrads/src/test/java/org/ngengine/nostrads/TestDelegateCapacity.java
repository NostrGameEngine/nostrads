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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostrads.client.services.PenaltyStorage;
import org.ngengine.nostrads.client.services.delegate.DelegateService;
import org.ngengine.nostrads.client.services.delegate.Tracker;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.VStore;

public class TestDelegateCapacity {

    @Test
    @SuppressWarnings("unchecked")
    public void configurableAdvertiserLimitRetainsTheGlobalLimit() throws Exception {
        VStore store = new VStore(null) {
            @Override
            public AsyncTask<Boolean> exists(String path) {
                return AsyncTask.completed(false);
            }

            @Override
            public AsyncTask<Void> writeFully(String path, byte[] data) {
                return AsyncTask.completed(null);
            }
        };
        AdTaxonomy taxonomy = new AdTaxonomy();
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        AdBidEvent candidate = new AdBidEvent(taxonomy,
            signer.sign(new UnsignedNostrEvent().withKind(30100).withTag("d", "capacity-test").withContent("{}")).await());
        NostrPool pool = new NostrPool();
        try (Tracker tracker = new Tracker(store);
            DelegateService service = new DelegateService(pool, signer, taxonomy, null, null, new PenaltyStorage(store), tracker)) {
            Field pendingField = DelegateService.class.getDeclaredField("pendingBids");
            pendingField.setAccessible(true);
            Map<String, String> pending = (Map<String, String>) pendingField.get(service);
            Method capacity = DelegateService.class.getDeclaredMethod("hasTrackingCapacity", AdBidEvent.class);
            capacity.setAccessible(true);
            for (int i = 0; i < 8; i++) pending.put("bid-" + i, candidate.getPubkey().asHex());
            assertFalse((Boolean) capacity.invoke(service, candidate));
            service.setMaxTrackedBidsPerAdvertiser(32);
            assertTrue((Boolean) capacity.invoke(service, candidate));
            for (int i = 8; i < 32; i++) pending.put("bid-" + i, candidate.getPubkey().asHex());
            assertFalse((Boolean) capacity.invoke(service, candidate));
            service.setMaxTrackedBidsPerAdvertiser(64);
            assertTrue((Boolean) capacity.invoke(service, candidate));
            for (int i = 32; i < 64; i++) pending.put("bid-" + i, "another-advertiser");
            assertFalse((Boolean) capacity.invoke(service, candidate));
            assertThrows(IllegalArgumentException.class, () -> service.setMaxTrackedBidsPerAdvertiser(0));
            assertThrows(IllegalArgumentException.class, () -> service.setMaxTrackedBidsPerAdvertiser(65));
        } finally {
            pool.close();
        }
    }
}
