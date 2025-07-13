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

import java.lang.ref.Cleaner;
import java.lang.ref.SoftReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.ngengine.platform.VStore;

public class DailyBudgetTracker {

    private VStore store;
    private final SoftHashMap<String, BudgetEntry> budgetCache = new SoftHashMap<>();

    public DailyBudgetTracker(VStore store) {
        this.store = store;
    }

    public synchronized boolean checkBudget(String bidId, long amountMsats) {
        BudgetEntry entry = budgetCache.get(bidId);
        if (entry == null) {
            entry = new BudgetEntry();
            budgetCache.put(bidId, entry);
        }

        long budgetLeft = entry.dailyBudgetMsats - entry.spent - entry.pendingOffers.size() * amountMsats;
        if (budgetLeft < amountMsats) {
            // logger.warning("Not enough budget left to accept offer for "+bidId+" "+amountMsats+" msats, budget left: "+budgetLeft+" msats");
            return false;
        }

        return true;
    }

    private static class BudgetEntry {

        long dailyBudgetMsats;
        long spent;
        Instant expiration;
        List<String> pendingOffers = new ArrayList<>();
    }
}

class SoftHashMap<K, V> {

    private static final Cleaner cleaner = Cleaner.create();
    private final ConcurrentHashMap<K, SoftReference<V>> map = new ConcurrentHashMap<>();

    public V get(K key) {
        SoftReference<V> ref = map.get(key);
        if (ref != null) {
            V value = ref.get();
            if (value == null) {
                // Value was garbage collected, remove the key
                map.remove(key);
            }
            return value;
        }
        return null;
    }

    public void put(K key, V value) {
        SoftReference<V> ref = new SoftReference<>(value);

        // Register cleanup action that runs when value is collected
        cleaner.register(
            value,
            () -> {
                // Remove the key from the map when the value is garbage collected
                map.remove(key);
            }
        );

        map.put(key, ref);
    }

    public V remove(K key) {
        SoftReference<V> ref = map.remove(key);
        return ref != null ? ref.get() : null;
    }

    public boolean containsKey(K key) {
        SoftReference<V> ref = map.get(key);
        if (ref != null) {
            V value = ref.get();
            if (value == null) {
                map.remove(key);
                return false;
            }
            return true;
        }
        return false;
    }
}
