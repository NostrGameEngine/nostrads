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

package org.ngengine.nostrads.client.services.delegate;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.VStore;

public class Tracker implements Closeable {

    private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(Tracker.class.getName());
    private static final int MAX_TRACKED_KEYS = 4096;
    private static final int MAX_COUNTERS_PER_KEY = 8;
    private static final int MAX_PERSISTED_BYTES = 4 * 1024 * 1024;
    private final VStore store;
    private final Map<String, Map<String, TrackedCounter>> tracked = new HashMap<>();
    private final AsyncExecutor cleanupExecutor;
    private final Runnable closer;

    public Tracker(VStore store) {
        this.store = store;
        String path = "nostrads/tracker";
        Map<String, Map<String, Object>> data = null;
        try {
            if (store.exists(path).await()) {
                byte[] json = store.readFully(path).await();
                if (json.length > MAX_PERSISTED_BYTES) {
                    throw new IllegalStateException("Persisted payout tracker exceeds maximum size");
                }
                data = NGEPlatform.get().fromJSON(new String(json, StandardCharsets.UTF_8), Map.class);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load persisted payout tracker", e);
        }
        if (data != null) {
            for (Map.Entry<String, Map<String, Object>> entry : data.entrySet()) {
                if (tracked.size() >= MAX_TRACKED_KEYS) throw new IllegalStateException("Too many persisted tracker keys");
                String key = entry.getKey();
                validateName(key, "tracker key");
                Map<String, Object> counters = entry.getValue();
                if (counters == null || counters.size() > MAX_COUNTERS_PER_KEY) {
                    throw new IllegalStateException("Invalid persisted tracker counters");
                }
                Map<String, TrackedCounter> counterMap = new HashMap<>();
                for (Map.Entry<String, Object> c : counters.entrySet()) {
                    validateName(c.getKey(), "counter name");
                    if (!(c.getValue() instanceof Map<?, ?>)) throw new IllegalStateException("Invalid persisted counter");
                    counterMap.put(c.getKey(), TrackedCounter.fromMap((Map<String, Object>) c.getValue()));
                }
                tracked.put(key, counterMap);
            }
        }
        this.cleanupExecutor = NGEPlatform.get().newAsyncExecutor();
        cleanupLoop();
        this.closer = NGEPlatform.get().registerFinalizer(this, () -> cleanupExecutor.close());
    }

    public synchronized boolean tryConsume(String key, String counter, long resetIntervalSeconds, long maxValue, long amount) {
        validateName(key, "tracker key");
        validateName(counter, "counter name");
        if (resetIntervalSeconds <= 0 || maxValue < 0 || amount <= 0) {
            throw new IllegalArgumentException("Invalid tracker interval, maximum, or amount");
        }
        if (!tracked.containsKey(key) && tracked.size() >= MAX_TRACKED_KEYS) {
            throw new IllegalStateException("Payout tracker capacity reached");
        }
        Map<String, TrackedCounter> counters = tracked.computeIfAbsent(key, k -> new HashMap<>());
        if (!counters.containsKey(counter) && counters.size() >= MAX_COUNTERS_PER_KEY) {
            throw new IllegalStateException("Payout counter capacity reached");
        }
        TrackedCounter tc = counters.computeIfAbsent(counter, k -> new TrackedCounter(0, 0, resetIntervalSeconds, maxValue));
        tc.resetIntervalSeconds = resetIntervalSeconds;
        tc.maxValue = maxValue;
        Instant now = Instant.now();
        if (tc.lastReset == 0 || now.getEpochSecond() - tc.lastReset >= tc.resetIntervalSeconds) {
            tc.value = 0;
            tc.lastReset = now.getEpochSecond();
        }
        if (tc.value > maxValue || amount > maxValue - tc.value) {
            return false;
        }
        long previous = tc.value;
        tc.value += amount;
        try {
            commit();
        } catch (RuntimeException e) {
            tc.value = previous;
            throw e;
        }
        return true;
    }

    public synchronized void refund(String key, String counter, long amount) {
        if (amount <= 0) return;
        Map<String, TrackedCounter> counters = tracked.get(key);
        if (counters == null) return;
        TrackedCounter tc = counters.get(counter);
        if (tc == null) return;
        Instant now = Instant.now();
        if (tc.lastReset == 0 || now.getEpochSecond() - tc.lastReset >= tc.resetIntervalSeconds) {
            return;
        }
        long previous = tc.value;
        tc.value = Math.max(0, tc.value - amount);
        try {
            commit();
        } catch (RuntimeException e) {
            tc.value = previous;
            throw e;
        }
    }

    public synchronized long getValue(String key, String counter) {
        Map<String, TrackedCounter> counters = tracked.get(key);
        if (counters == null) return 0;
        TrackedCounter tc = counters.get(counter);
        if (tc == null) return 0;
        Instant now = Instant.now();
        if (tc.lastReset == 0 || now.getEpochSecond() - tc.lastReset >= tc.resetIntervalSeconds) {
            return 0;
        }
        return tc.value;
    }

    private void cleanupLoop() {
        this.cleanupExecutor.runLater(
                () -> {
                    synchronized (this) {
                        Instant now = Instant.now();
                        AtomicBoolean changed = new AtomicBoolean(false);
                        tracked
                            .entrySet()
                            .removeIf(trackedEntry -> {
                                Map<String, TrackedCounter> counters = trackedEntry.getValue();
                                counters
                                    .entrySet()
                                    .removeIf(entry -> {
                                        TrackedCounter tc = entry.getValue();
                                        if (
                                            tc.lastReset != 0 &&
                                            now.getEpochSecond() - tc.lastReset >= tc.resetIntervalSeconds * 3
                                        ) {
                                            changed.set(true);
                                            return true;
                                        }
                                        return false;
                                    });
                                if (counters.isEmpty()) {
                                    changed.set(true);
                                    return true;
                                }
                                return false;
                            });
                        if (changed.get()) {
                            commit();
                        }
                    }
                    cleanupLoop();
                    return null;
                },
                5000,
                TimeUnit.MILLISECONDS
            );
    }

    private void commit() {
        synchronized (this) {
            try {
                Map<String, Map<String, Object>> serializable = new HashMap<>();
                for (Map.Entry<String, Map<String, TrackedCounter>> entry : tracked.entrySet()) {
                    Map<String, Object> counters = new HashMap<>();
                    for (Map.Entry<String, TrackedCounter> c : entry.getValue().entrySet()) {
                        counters.put(c.getKey(), c.getValue().toMap());
                    }
                    serializable.put(entry.getKey(), counters);
                }
                String json = NGEPlatform.get().toJSON(serializable);
                store.writeFully("nostrads/tracker", json.getBytes(StandardCharsets.UTF_8)).await();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to persist payout tracker", e);
            }
        }
    }

    private static void validateName(String value, String label) {
        if (value == null || value.length() == 0 || value.length() > 128 || !value.matches("[A-Za-z0-9:_-]+")) {
            throw new IllegalArgumentException("Invalid " + label);
        }
    }

    @Override
    public void close() {
        closer.run();
    }

    private static class TrackedCounter {

        long value;
        long lastReset;
        long resetIntervalSeconds;
        long maxValue;

        TrackedCounter(long value, long lastReset, long resetIntervalSeconds, long maxValue) {
            this.value = value;
            this.lastReset = lastReset;
            this.resetIntervalSeconds = resetIntervalSeconds;
            this.maxValue = maxValue;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("value", value);
            m.put("lastReset", lastReset);
            m.put("resetIntervalSeconds", resetIntervalSeconds);
            m.put("maxValue", maxValue);
            return m;
        }

        static TrackedCounter fromMap(Map<String, Object> m) {
            return new TrackedCounter(
                ((Number) m.getOrDefault("value", 0)).longValue(),
                ((Number) m.getOrDefault("lastReset", 0)).longValue(),
                ((Number) m.getOrDefault("resetIntervalSeconds", 0)).longValue(),
                ((Number) m.getOrDefault("maxValue", 0)).longValue()
            );
        }
    }
}
