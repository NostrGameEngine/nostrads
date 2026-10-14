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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.ngengine.nostrads.client.services.delegate.Tracker;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.VStore;

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

    @Test(timeout = 10000)
    public void failedWritesRollBackAndReleaseTheStateLock() {
        AtomicBoolean failWrite = new AtomicBoolean();
        VStore store = new VStore(null) {
            @Override
            public AsyncTask<Boolean> exists(String path) {
                return AsyncTask.completed(false);
            }

            @Override
            public AsyncTask<Void> writeFully(String path, byte[] data) {
                return failWrite.get()
                    ? AsyncTask.failed(new IllegalStateException("Write rejected"))
                    : AsyncTask.completed(null);
            }
        };
        try (Tracker tracker = new Tracker(store)) {
            assertTrue(tracker.tryConsume("bid", "budget", 3600, 1000, 700));
            failWrite.set(true);
            assertThrows(IllegalStateException.class, () -> tracker.tryConsume("bid", "budget", 3600, 1000, 100));
            assertEquals(700, tracker.getValue("bid", "budget"));
            assertThrows(IllegalStateException.class, () -> tracker.refund("bid", "budget", 200));
            assertEquals(700, tracker.getValue("bid", "budget"));
            failWrite.set(false);
            tracker.refund("bid", "budget", 200);
            assertTrue(tracker.tryConsume("bid", "budget", 3600, 1000, 500));
            assertEquals(1000, tracker.getValue("bid", "budget"));
        }
    }

    @Test
    public void virtualThreadReservationsDoNotPinTheOnlyCarrier() throws Exception {
        Path output = Files.createTempFile("tracker-virtual-thread-", ".log");
        Process process = null;
        try {
            String java = new File(System.getProperty("java.home"), "bin/java").getPath();
            String classpath = System.getProperty("nostrads.test.runtimeClasspath", System.getProperty("java.class.path"));
            process =
                new ProcessBuilder(
                    java,
                    "-Djdk.virtualThreadScheduler.parallelism=1",
                    "-Djdk.virtualThreadScheduler.maxPoolSize=1",
                    "-cp",
                    classpath,
                    VirtualThreadProbe.class.getName()
                )
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            assertTrue("Virtual-thread reservation process timed out", process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(Files.readString(output), 0, process.exitValue());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(output);
        }
    }

    public static final class VirtualThreadProbe {

        public static void main(String[] args) throws Exception {
            Tracker tracker = tracker();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread reservation = Thread
                .ofVirtual()
                .start(() -> {
                    try {
                        assertTrue(tracker.tryConsume("virtual", "budget", 3600, 1000, 700));
                        assertFalse(tracker.tryConsume("virtual", "budget", 3600, 1000, 301));
                        tracker.refund("virtual", "budget", 200);
                        assertTrue(tracker.tryConsume("virtual", "budget", 3600, 1000, 400));
                        assertEquals(900, tracker.getValue("virtual", "budget"));
                    } catch (Throwable error) {
                        failure.set(error);
                    }
                });
            reservation.join(10000);
            assertFalse("Reservation pinned the virtual-thread carrier", reservation.isAlive());
            if (failure.get() != null) throw new AssertionError("Virtual reservation failed", failure.get());
            AtomicInteger accepted = new AtomicInteger();
            List<Thread> competitors = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                competitors.add(
                    Thread
                        .ofVirtual()
                        .start(() -> {
                            try {
                                if (tracker.tryConsume("contended", "budget", 3600, 500, 100)) accepted.incrementAndGet();
                            } catch (Throwable error) {
                                failure.set(error);
                            }
                        })
                );
            }
            for (Thread competitor : competitors) {
                competitor.join(10000);
                assertFalse("Contended reservation pinned the carrier", competitor.isAlive());
            }
            if (failure.get() != null) throw new AssertionError("Contended reservation failed", failure.get());
            assertEquals(5, accepted.get());
            assertEquals(500, tracker.getValue("contended", "budget"));
            tracker.close();
        }
    }

    private static Tracker tracker() {
        return new Tracker(NGEPlatform.get().getDataStore("unit-tests-tracker-" + System.nanoTime(), "tracker"));
    }
}
