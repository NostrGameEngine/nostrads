/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * conditions in the project LICENSE file are met.
 */
package org.ngengine.nostrads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.ngengine.nostrads.client.services.delegate.Tracker;
import org.ngengine.platform.NGEPlatform;

public class TestTracker {

    @Test
    public void consumesActualAmountsAndRefunds() {
        Tracker tracker = tracker();
        try {
            assertTrue(tracker.tryConsume("bid", "budget", 3600, 1000, 700));
            assertFalse(tracker.tryConsume("bid", "budget", 3600, 1000, 301));
            tracker.refund("bid", "budget", 200);
            assertTrue(tracker.tryConsume("bid", "budget", 3600, 1000, 400));
            assertEquals(900, tracker.getValue("bid", "budget"));
        } finally {
            tracker.close();
        }
    }

    @Test
    public void concurrentReservationsCannotExceedLimit() throws Exception {
        Tracker tracker = tracker();
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger accepted = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                Thread thread = new Thread(() -> {
                    try {
                        start.await();
                        if (tracker.tryConsume("concurrent", "budget", 3600, 1000, 100)) accepted.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                threads.add(thread);
                thread.start();
            }
            start.countDown();
            for (Thread thread : threads) thread.join();
            assertEquals(10, accepted.get());
            assertEquals(1000, tracker.getValue("concurrent", "budget"));
        } finally {
            tracker.close();
        }
    }

    private static Tracker tracker() {
        return new Tracker(NGEPlatform.get().getDataStore("unit-tests-tracker-" + System.nanoTime(), "tracker"));
    }
}
