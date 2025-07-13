package org.ngengine.nostrads.client.services.delegate;

import org.ngengine.platform.VStore;
import java.lang.ref.Cleaner;
import java.lang.ref.SoftReference;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;

public class DailyBudgetTracker{
    private VStore store;
    private final SoftHashMap<String,BudgetEntry> budgetCache=new SoftHashMap<>();

    public DailyBudgetTracker(VStore store){
        this.store=store;
    }

    public synchronized boolean checkBudget(
        String bidId, 
        long amountMsats
    ) {
        BudgetEntry entry=budgetCache.get(bidId);
        if(entry==null){
            entry=new BudgetEntry();
            budgetCache.put(bidId,entry);
        }

        long budgetLeft=entry.dailyBudgetMsats-entry.spent-entry.pendingOffers.size()*amountMsats;
        if(budgetLeft<amountMsats){
            // logger.warning("Not enough budget left to accept offer for "+bidId+" "+amountMsats+" msats, budget left: "+budgetLeft+" msats");
            return false;
        }

        return true;
    }

    private static class BudgetEntry{
        long dailyBudgetMsats;
        long spent;
        Instant expiration;
        List<String> pendingOffers=new ArrayList<>();
    }
}

class SoftHashMap<K,V> {
    private static final Cleaner cleaner=Cleaner.create();
    private final ConcurrentHashMap<K,SoftReference<V>> map=new ConcurrentHashMap<>();

    public V get(K key) {
        SoftReference<V> ref=map.get(key);
        if(ref!=null){
            V value=ref.get();
            if(value==null){
                // Value was garbage collected, remove the key
                map.remove(key);
            }
            return value;
        }
        return null;
    }

    public void put(K key, V value) {
        SoftReference<V> ref=new SoftReference<>(value);

        // Register cleanup action that runs when value is collected
        cleaner.register(value,() -> {
            // Remove the key from the map when the value is garbage collected
            map.remove(key);
        });

        map.put(key,ref);
    }

    public V remove(K key) {
        SoftReference<V> ref=map.remove(key);
        return ref!=null?ref.get():null;
    }

    public boolean containsKey(K key) {
        SoftReference<V> ref=map.get(key);
        if(ref!=null){
            V value=ref.get();
            if(value==null){
                map.remove(key);
                return false;
            }
            return true;
        }
        return false;
    }


}