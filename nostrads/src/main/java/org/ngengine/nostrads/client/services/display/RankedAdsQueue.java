package org.ngengine.nostrads.client.services.display;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.pool.fetchpolicy.NostrPoolFetchPolicy;
import org.ngengine.nostr4j.pool.fetchpolicy.NostrWaitForEventFetchPolicy;
import org.ngengine.nostrads.client.services.PenaltyStorage;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

import jakarta.annotation.Nonnull;

public class RankedAdsQueue{
    private static final Logger logger=Logger.getLogger(AdsDisplayClient.class.getName());
    private static final int numBidsToLoad=10;
    private static final Duration updateInterval=Duration.ofSeconds(60);
    private static final double minBaseScore=0.2; // Set your minimum rank threshold here

    private final List<GlobalRankedAd> rankedBids;
    private final PenaltyStorage penaltyStorage;
    private final NostrPool pool;
    private final AdTaxonomy taxonomy;
    private final Map<String,GlobalRankedAd> bidsCache;
    private final Adspace adspace;
    final AtomicInteger refs=new AtomicInteger(1);

    private Instant newestBidTime=null;
    private Instant oldestBidTime=null;
    private Instant lastUpdateTime=null;

    public RankedAdsQueue(
        @Nonnull AdTaxonomy taxonomy,       
        @Nonnull NostrPool pool,
        @Nonnull PenaltyStorage penaltyStorage,
        @Nonnull Map<String,GlobalRankedAd> bidsCache,
        @Nonnull Adspace adspace
    ){
        this.taxonomy=taxonomy;
        this.pool=pool;
        this.penaltyStorage=penaltyStorage;
        this.rankedBids=new LinkedList<>();
        this.bidsCache=bidsCache;
        this.adspace=adspace;
    }

    private boolean isTargetingThisSpace(AdBidEvent bidding) {

        List<NostrPublicKey> tgs=bidding.getTargetedOfferers();
        // workaround https://github.com/hoytech/strfry/issues/150 with client-side
        // filtering
        if(tgs!=null&&!tgs.contains(adspace.getUserKey())){
            logger.finer("Skipping bid not targeted to this offerer: "+bidding.getId());
            return false; // skip bids not targeted to this offerer
        }
        tgs=bidding.getTargetedApps();
        if(tgs!=null&&!tgs.contains(adspace.getAppKey())){
            logger.finer("Skipping bid not targeted to this app: "+bidding.getId());
            return false; // skip bids not targeted to this app
        }

        return true;
    }

    private void update() {
        try{
            if(lastUpdateTime!=null&&Instant.now().isBefore(lastUpdateTime.plus(updateInterval))){
                return; // skip update if the last update was less than the update interval ago
            }
            lastUpdateTime=Instant.now();

            Instant now=Instant.now();

            List<GlobalRankedAd> mergedBids=new ArrayList<>();

            // merge bids with the existing ranked bids and remove expired in the same pass
            for(GlobalRankedAd r:rankedBids){
                AdBidEvent bid=r.get();
                if(bid.getExpiration()!=null&&bid.getExpiration().isBefore(now)){
                    logger.finer("Removing expired bid: "+bid.getId()+" with expiration: "+bid.getExpiration());
                    continue; // skip expired bids
                }
                mergedBids.add(r);
            }

            // load a bunch of new bids

            if(newestBidTime==null){
                newestBidTime=Instant.now();
            }
            if(oldestBidTime==null){
                oldestBidTime=Instant.now();
            }

            NostrFilter filter = adspace.toFilter();

            int rounds=0;
            int goodRanks=0;
            float goodBaseScore=1.0f;

            // Load bids in rounds, first newer+older, then only older, until we have enough good ranks or we
            // reach the maximum number of rounds
            while(rounds<3&&goodRanks<numBidsToLoad*2){
                logger.finer("Loading bids (round "+rounds+") using filter: "+filter);
                logger.finer("Newest bid time: "+newestBidTime);
                logger.finer("Oldest bid time: "+oldestBidTime);
                logger.finer("Currently newly loaded bids: "+rankedBids.size());

                AsyncTask<List<GlobalRankedAd>> newOlderBids=fetchBids(List.of(filter.clone().until(oldestBidTime.plusMillis(2100))),null);

                List<List<GlobalRankedAd>> newBids;
                if(rounds==0){
                    AsyncTask<List<GlobalRankedAd>> newNewerBids=fetchBids(List.of(filter.clone().since(newestBidTime.minusMillis(2100))),null);
                    newBids=NGEPlatform.get().awaitAll(List.of(newNewerBids,newOlderBids)).await();
                    logger.finer("Loaded "+newBids.get(0).size()+" newer bids and "+newBids.get(1).size()+" older bids");
                }else{ // rounds > 0 only load from the past (it is unlikely that new bids are added in  this small time frame)
                    newBids=NGEPlatform.get().awaitAll(List.of(newOlderBids)).await();
                    logger.finer("Loaded "+newBids.get(0).size()+" older bids");
                }

                // merge and find olderst and newest bid times
                for(int i=0;i<2;i++){
                    if(newBids.size()<=i) continue;
                    for(GlobalRankedAd r:newBids.get(i)){
                        AdBidEvent bid=r.get();

                        try{
                            // check if bid is targeting this specific space
                            if(!isTargetingThisSpace(bid)){
                                logger.finer("Skipping bid: "+bid.getId()+" not targeting this space: "+adspace);
                                continue; // skip bids not targeting this space
                            }

                            // load penalty for the bid
                            try{
                                Number n=penaltyStorage.get(bid).await();
                                r.setPenalty(n.intValue());
                            }catch(Exception e){
                                logger.log(Level.WARNING,"Error loading penalty for bid: "+bid.getId(),e);
                                r.setPenalty(0); // if we fail to load the penalty, we assume no penalty
                            }

                            // compute rank quality
                            if((float)r.getBaseScore()>=goodBaseScore){
                                logger.finest("Adding bid: "+bid.getId()+" with score: "+r.getBaseScore());
                                goodRanks++;
                            }
                            if(newestBidTime==null||bid.getCreatedAt().isAfter(newestBidTime)){
                                newestBidTime=bid.getCreatedAt();
                                logger.finest("New newest bid time: "+newestBidTime);
                            }
                            if(oldestBidTime==null||bid.getCreatedAt().isBefore(oldestBidTime)){
                                oldestBidTime=bid.getCreatedAt();
                                logger.finest("New oldest bid time: "+oldestBidTime);
                            }
                            if(!mergedBids.stream().anyMatch(ro -> ro.get().getAdId().equals(bid.getAdId()))){
                                mergedBids.add(r);
                                logger.finest("Added bid: "+bid.getId()+" to merged bids, total: "+mergedBids.size());
                            }
                        }catch(Exception e){
                            logger.log(Level.WARNING,"Error processing bid: "+bid.getId(),e);
                        }
                    }
                }

                // increase round counter
                rounds++;
            }

            // sort
            logger.finer("Sort bids by score, total loaded: "+mergedBids.size());
            mergedBids.sort((a, b) -> {
                double scoreA=a.getBaseScore();
                double scoreB=b.getBaseScore();
                return  scoreB > scoreA ? 1 : (scoreB < scoreA ? -1 : 0); // sort by score higher score first           
            });


            // remove to fit the number of bids to load
            if(mergedBids.size()>numBidsToLoad){
                logger.finer("Trimming bids to fit the number of bids to load: "+numBidsToLoad);
                mergedBids=mergedBids.subList(0,numBidsToLoad);
            }

            logger.finer("Total bids loaded: "+mergedBids.size());
            synchronized(rankedBids){
                this.rankedBids.clear();
                this.rankedBids.addAll(mergedBids);
            }
        }catch(Exception e){
            logger.log(Level.WARNING,"Error updating ads queue",e);
        }
    }

    public GlobalRankedAd get(
        int width, 
        int height,
        Predicate<AdBidEvent> filter
    ) {
          
        update();

        // get best bid
        if(rankedBids.isEmpty()){
            logger.finer("No bids available for adspace: "+adspace);    
            return null;
        }


        synchronized(rankedBids){
            // sort
            logger.finer("Sort bids by score, total loaded: "+rankedBids.size());
            rankedBids.sort((a, b) -> {
                double scoreA=a.getContextualScore(adspace,width,height);
                double scoreB=b.getContextualScore(adspace,width,height);
                return scoreB > scoreA ? 1 : (scoreB < scoreA ? -1 : 0); // sort by score higher score first          
            });
            logger.finer("payload Available bids per rank: "+rankedBids.size());
          
            for(GlobalRankedAd rbid:rankedBids){
                AdBidEvent bid=rbid.get();
                try{
                    if(rbid.getBaseScore()<0){
                        logger.finer("Skipping bid: "+bid.getId()+" due unmatchable properties");
                        continue;
                    }

                    if(filter!=null&&!filter.test(bid)){
                        logger.finer("Skipping bid: "+bid.getId()+" due to filter");
                        continue; // skip bids that do not match the filter
                    }
                }catch(Exception e){
                    logger.log(Level.WARNING,"Error applying filter to bid: "+bid.getId(),e);
                    rbid.derank(true); // derank the bid if the filter throws an exception
                    continue; // skip bids that throw an exception in the filter
                }
                rbid.derank(false);
                logger.finer("Best bid: "+bid+"\n with score: "+rbid.getContextualScore(adspace,width,height));
                return rbid;
            }            
        }

        return null; // no bid found that matches the filter
           
    }
 

     
    /**
     * Fetch all bids given a list of filters and a fetch policy.
     * @param filters the list of filters to use for fetching bids
     * @param fetchPolicy the fetch policy to use for fetching bids, can be null to use the default
     * @return an AsyncTask that will complete with a list of AdBidEvent
     * @throws IllegalStateException if the AdClient is closing
     *
     */
    public AsyncTask<List<GlobalRankedAd>> fetchBids(List<NostrFilter> filters, NostrPoolFetchPolicy fetchPolicy) {

        // we will use the maximum limit from the filter to autogenerate a fetch policy if one is not provided
        int maxLimit=0;
        for(NostrFilter filter:filters){
            if(filter.getLimit()!=null&&filter.getLimit()>maxLimit){
                maxLimit=filter.getLimit();
            }
        }

        AsyncTask<List<GlobalRankedAd>> nn=pool.fetch(filters,fetchPolicy!=null?fetchPolicy:NostrWaitForEventFetchPolicy.get(e -> true,maxLimit>0?maxLimit:numBidsToLoad,true) // we are going to early stop as soon as we have up to maxLimit events or we receive an eose for every relay
        ).then(events -> {
            // turn all the events into bids
            List<GlobalRankedAd> bids=new ArrayList<>();
            for(SignedNostrEvent event:events){
                try{
                    AdBidEvent bid =new AdBidEvent(taxonomy,event);
                    if(!bid.isValid()){ 
                        logger.fine("Invalid bidding event: "+bid.getId());
                        continue; // skip invalid bids
                    }
                    String id=bid.getId();
                    GlobalRankedAd rbid;
                    synchronized (bidsCache) {
                        rbid=bidsCache.computeIfAbsent(id,k -> new GlobalRankedAd(bid));
                    }
                    if(bids!=null){
                        bids.add(rbid);
                    }
                }catch(Exception e){
                    logger.log(Level.WARNING,"Error processing event: "+event.getId(),e);
                }
            }
            return bids;
        }) ;
        return nn;
    }
 
}
