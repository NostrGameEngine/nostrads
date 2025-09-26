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

package org.ngengine.nostrads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostrads.client.advertiser.AdvertiserClient;
import org.ngengine.nostrads.client.services.PenaltyStorage;
import org.ngengine.nostrads.client.services.delegate.DelegateService;
import org.ngengine.nostrads.client.services.delegate.Tracker;
import org.ngengine.nostrads.client.services.display.AdsDisplayClient;
import org.ngengine.nostrads.client.services.display.Adspace;
import org.ngengine.nostrads.client.services.display.RankedAd;
import org.ngengine.nostrads.client.services.display.RankedAdsQueue;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.AdBidFilter;
import org.ngengine.nostrads.protocol.types.AdActionType;
import org.ngengine.nostrads.protocol.types.AdAspectRatio;
import org.ngengine.nostrads.protocol.types.AdMimeType;
import org.ngengine.nostrads.protocol.types.AdPriceSlot;
import org.ngengine.nostrads.protocol.types.AdSize;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public class TestAds {

    private String APP_KEY = "npub13rugy09zg5pssxtjfvgkhjjzkx8swpvwr7e2gymnr2jp2ltveeqs88pjk4";

    private static final Logger logger = TestLogger.getRoot(Level.FINEST);
    NostrPool pool;

    // run before all tests
    public TestAds() {
        pool = new NostrPool();
        pool.connectRelay(new NostrRelay("wss://nostr.rblb.it"));
    }

    // run after all tests
    public void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    public void testAdBid() throws Exception {
        NostrKeyPair advertiserKeyPair = new NostrKeyPair(NostrPrivateKey.generate());
        NostrKeyPairSigner advertiserSigner = new NostrKeyPairSigner(advertiserKeyPair);

        AdTaxonomy taxonomy = new AdTaxonomy();

        AdvertiserClient client = new AdvertiserClient(pool, advertiserSigner, taxonomy);

        AdBidEvent bid = client
            .newBid(
                null,
                "Test Bid" + Math.random() + Instant.now().toEpochMilli(),
                null,
                List.of(taxonomy.getByPath("Technology & Computing/Virtual Reality")),
                null,
                null,
                null,
                AdMimeType.TEXT_PLAIN,
                "This is a test bid",
                AdSize.HORIZONTAL_480x60,
                "https://ngengine.org",
                "Click here!",
                AdActionType.VIEW,
                1000,
                Duration.ofSeconds(60 * 5),
                NostrPrivateKey.generate().getPublicKey(),
                null,
                Instant.now().plusSeconds(60 * 5),
                21,
                Duration.ofSeconds(1)
            )
            .await();

        // verify event
        Map<String, Object> data = bid.toMap();
        Map<String, Object> content = NGEPlatform.get().fromJSON((String) data.get("content"), Map.class);
        assertEquals("This is a test bid", content.get("payload"));
        assertEquals("Click here!", content.get("call_to_action"));
        assertEquals("https://ngengine.org", content.get("link"));
        assertTrue(((String) content.get("description")).startsWith("Test Bid"));
        assertEquals(300L, ((Number) content.get("hold_time")).longValue());
        assertEquals(1000L, ((Number) content.get("bid")).longValue());

        List<List<String>> tags = (List<List<String>>) data.get("tags");
        assertNotNull(tags);

        String dTagValue = null;
        String expirationTagValue = null;
        for (List<String> tag : tags) {
            if (tag.get(0).equals("d")) {
                dTagValue = tag.get(1);
            } else if (tag.get(0).equals("expiration")) {
                expirationTagValue = tag.get(1);
            }
        }
        assertNotNull(dTagValue);
        assertTrue(dTagValue.startsWith("nostr4j"));
        assertEquals("639", tags.get(1).get(1));
        assertEquals("text/plain", tags.get(2).get(1));
        assertEquals("480x60", tags.get(3).get(1));
        assertEquals("view", tags.get(4).get(1));
        assertEquals("8:1", tags.get(5).get(1));
        assertEquals("BTC1_000", tags.get(6).get(1));
        assertNotNull(expirationTagValue);
        assertNotNull(data.get("created_at"));
    }

    @Test
    public void testTargetedBids() throws Exception {
        AdTaxonomy taxonomy = new AdTaxonomy();
        NostrKeyPair advertiserKeyPair = new NostrKeyPair(NostrPrivateKey.generate());

        NostrPublicKey appKey = NostrPublicKey.fromBech32(APP_KEY);
        PenaltyStorage penaltyStorage = new PenaltyStorage(NGEUtils.getPlatform().getDataStore("unit-tests-Ad", "penalty"));
        {
            NostrKeyPairSigner advertiserSigner = new NostrKeyPairSigner(advertiserKeyPair);

            AdvertiserClient client = new AdvertiserClient(pool, advertiserSigner, taxonomy);

            AdBidEvent bid = client
                .newBid(
                    null,
                    "Test Bid" + Math.random() + Instant.now().toEpochMilli(),
                    null,
                    List.of(taxonomy.getByPath("Technology & Computing/Virtual Reality")),
                    null,
                    null,
                    List.of(appKey),
                    AdMimeType.TEXT_PLAIN,
                    "This is a test bid",
                    AdSize.HORIZONTAL_480x60,
                    "https://ngengine.org",
                    "Click here!",
                    AdActionType.VIEW,
                    1000,
                    Duration.ofSeconds(60 * 5),
                    NostrPrivateKey.generate().getPublicKey(),
                    null,
                    Instant.now().plusSeconds(60 * 5),
                    21,
                    Duration.ofSeconds(1)
                )
                .await();
            (client.publishBid(bid)).await();
        }

        {
            NostrKeyPair offererKeyPair = new NostrKeyPair(NostrPrivateKey.generate());
            NostrKeyPairSigner offererSigner = new NostrKeyPairSigner(offererKeyPair);

            Adspace adspace = new Adspace(
                appKey,
                appKey,
                AdAspectRatio.RATIO_8_1,
                AdPriceSlot.BTC1_000,
                List.of(AdMimeType.TEXT_PLAIN)
            );
            RankedAdsQueue queue = new RankedAdsQueue(taxonomy, pool, penaltyStorage, new HashMap<>(), adspace);
            // no bid
            {
                AdBidFilter filter = new AdBidFilter().onlyForApp(appKey).withSizes(AdSize.HORIZONTAL_720x90);
                filter.withAuthor(advertiserKeyPair.getPublicKey());

                List<RankedAd> bids = queue.fetchBids(List.of(filter), null).await();
                assertEquals(bids.size(), 0);
            }

            // find bid
            {
                AdBidFilter filter = new AdBidFilter().onlyForApp(appKey).withSizes(AdSize.HORIZONTAL_480x60);
                filter.withAuthor(advertiserKeyPair.getPublicKey());

                System.out.println("Fetching bid with filter: " + filter);
                List<RankedAd> bids = queue.fetchBids(List.of(filter), null).await();
                assertTrue(bids.size() > 0);
            }
        }
    }

    @Test
    public void testUntargetedBids() throws Exception {
        NostrPublicKey appKey = NostrPublicKey.fromBech32(APP_KEY);
        AdTaxonomy taxonomy = new AdTaxonomy();
        PenaltyStorage penaltyStorage = new PenaltyStorage(NGEUtils.getPlatform().getDataStore("unit-tests-Ad", "penalty"));
        NostrKeyPair advertiserKeyPair = new NostrKeyPair(NostrPrivateKey.generate());

        {
            NostrKeyPairSigner advertiserSigner = new NostrKeyPairSigner(advertiserKeyPair);
            AdvertiserClient advClient = new AdvertiserClient(pool, advertiserSigner, taxonomy);

            AdBidEvent bid = advClient
                .newBid(
                    null,
                    "Test Bid" + Math.random() + Instant.now().toEpochMilli(),
                    null,
                    List.of(taxonomy.getByPath("Technology & Computing/Virtual Reality")),
                    null,
                    null,
                    null,
                    AdMimeType.TEXT_PLAIN,
                    "This is a test bid",
                    AdSize.HORIZONTAL_480x60,
                    "https://ngengine.org",
                    "Click here!",
                    AdActionType.VIEW,
                    1000,
                    Duration.ofSeconds(60 * 5),
                    NostrPrivateKey.generate().getPublicKey(),
                    null,
                    Instant.now().plusSeconds(60 * 5),
                    21,
                    Duration.ofSeconds(1)
                )
                .await();
            System.out.println("Publishing bid: " + bid);

            (advClient.publishBid(bid)).await();
        }

        {
            NostrKeyPair offererKeyPair = new NostrKeyPair(NostrPrivateKey.generate());

            Adspace adspace = new Adspace(
                appKey,
                appKey,
                AdAspectRatio.RATIO_8_1,
                AdPriceSlot.BTC1_000,
                List.of(AdMimeType.TEXT_PLAIN)
            );
            RankedAdsQueue queue = new RankedAdsQueue(taxonomy, pool, penaltyStorage, new HashMap<>(), adspace);
            // find bid
            {
                AdBidFilter filter = new AdBidFilter().withSizes(AdSize.HORIZONTAL_480x60);
                filter.withAuthor(advertiserKeyPair.getPublicKey());

                System.out.println("Fetching bid with filter: " + filter);
                List<RankedAd> bids = queue.fetchBids(List.of(filter), null).await();

                assertTrue(bids.size() > 0);
            }
        }
    }

    @Test
    public void testCategoryBids() throws Exception {
        NostrPublicKey appKey = NostrPublicKey.fromBech32(APP_KEY);
        AdTaxonomy taxonomy = new AdTaxonomy();
        PenaltyStorage penaltyStorage = new PenaltyStorage(NGEUtils.getPlatform().getDataStore("unit-tests-Ad", "penalty"));
        NostrKeyPair advertiserKeyPair = new NostrKeyPair(NostrPrivateKey.generate());

        {
            NostrKeyPairSigner advertiserSigner = new NostrKeyPairSigner(advertiserKeyPair);
            AdvertiserClient advClient = new AdvertiserClient(pool, advertiserSigner, taxonomy);

            AdBidEvent bid = advClient
                .newBid(
                    null,
                    "Test Bid" + Math.random() + Instant.now().toEpochMilli(),
                    null,
                    List.of(taxonomy.getByPath("Technology & Computing/Virtual Reality")),
                    null,
                    null,
                    null,
                    AdMimeType.TEXT_PLAIN,
                    "This is a test bid",
                    AdSize.HORIZONTAL_480x60,
                    "https://ngengine.org",
                    "Click here!",
                    AdActionType.VIEW,
                    1000,
                    Duration.ofSeconds(60 * 5),
                    NostrPrivateKey.generate().getPublicKey(),
                    null,
                    Instant.now().plusSeconds(60 * 5),
                    21,
                    Duration.ofSeconds(1)
                )
                .await();

            System.out.println("Publishing bid: " + bid);
            (advClient.publishBid(bid)).await();
        }

        {
            NostrKeyPair offererKeyPair = new NostrKeyPair(NostrPrivateKey.generate());
            NostrKeyPairSigner offererSigner = new NostrKeyPairSigner(offererKeyPair);

            Adspace adspace = new Adspace(
                appKey,
                appKey,
                AdAspectRatio.RATIO_8_1,
                AdPriceSlot.BTC1_000,
                List.of(AdMimeType.TEXT_PLAIN)
            );
            RankedAdsQueue queue = new RankedAdsQueue(taxonomy, pool, penaltyStorage, new HashMap<>(), adspace);
            // find bid
            {
                AdBidFilter filter = new AdBidFilter()
                    .withCategories(taxonomy.getByPath("Technology & Computing/Virtual Reality"))
                    .withSizes(AdSize.HORIZONTAL_480x60);
                filter.withAuthor(advertiserKeyPair.getPublicKey());

                System.out.println("Fetching bid with filter: " + filter);
                List<RankedAd> bids = queue.fetchBids(List.of(filter), null).await();
                assertTrue(bids.size() > 0);
            }
            // find no bid
            {
                AdBidFilter filter = new AdBidFilter()
                    .withCategories(taxonomy.getByPath("Automotive"))
                    .withSizes(AdSize.HORIZONTAL_480x60);
                filter.withAuthor(advertiserKeyPair.getPublicKey());

                System.out.println("Fetching bid with filter: " + filter);
                List<RankedAd> bids = queue.fetchBids(List.of(filter), null).await();
                assertTrue(bids.size() == 0);
            }
        }
    }

    @Test
    public void testPriceSlot() throws Exception {
        AdTaxonomy taxonomy = new AdTaxonomy();

        NostrKeyPair advertiserKeyPair = new NostrKeyPair(NostrPrivateKey.generate());
        NostrKeyPairSigner advertiserSigner = new NostrKeyPairSigner(advertiserKeyPair);
        AdvertiserClient advClient = new AdvertiserClient(pool, advertiserSigner, taxonomy);

        AdBidEvent bid = advClient
            .newBid(
                null,
                "Test Bid" + Math.random() + Instant.now().toEpochMilli(),
                null,
                List.of(taxonomy.getByPath("Technology & Computing/Virtual Reality")),
                null,
                null,
                null,
                AdMimeType.TEXT_PLAIN,
                "This is a test bid",
                AdSize.HORIZONTAL_480x60,
                "https://ngengine.org",
                "Click here!",
                AdActionType.VIEW,
                2000,
                Duration.ofSeconds(60 * 5),
                NostrPrivateKey.generate().getPublicKey(),
                null,
                Instant.now().plusSeconds(60 * 5),
                21,
                Duration.ofSeconds(1)
            )
            .await();

        System.out.println("Publishing bid: " + bid);
        (advClient.publishBid(bid)).await();

        NostrKeyPair offererKeyPair = new NostrKeyPair(NostrPrivateKey.generate());
        NostrKeyPairSigner offererSigner = new NostrKeyPairSigner(offererKeyPair);
        PenaltyStorage penaltyStorage = new PenaltyStorage(NGEUtils.getPlatform().getDataStore("unit-tests-Ad", "penalty"));

        NostrPublicKey appKey = NostrPublicKey.fromBech32(APP_KEY);

        Adspace adspace = new Adspace(
            appKey,
            appKey,
            AdAspectRatio.RATIO_8_1,
            AdPriceSlot.BTC1_000,
            List.of(AdMimeType.TEXT_PLAIN)
        );
        RankedAdsQueue queue = new RankedAdsQueue(taxonomy, pool, penaltyStorage, new HashMap<>(), adspace);

        // should return no bid
        {
            AdBidFilter filter = new AdBidFilter().withSizes(AdSize.HORIZONTAL_480x60).withPriceSlot(AdPriceSlot.BTC10_000);
            filter.withAuthor(advertiserKeyPair.getPublicKey());

            System.out.println("Fetching bid with filter: " + filter);
            List<RankedAd> bids = queue.fetchBids(List.of(filter), null).await();
            assertTrue(bids.size() == 0);
        }

        // should return bid
        {
            AdBidFilter filter = new AdBidFilter().withSizes(AdSize.HORIZONTAL_480x60).withPriceSlot(AdPriceSlot.BTC2_000);
            filter.withAuthor(advertiserKeyPair.getPublicKey());

            System.out.println("Fetching bid with filter: " + filter);
            List<RankedAd> bids = queue.fetchBids(List.of(filter), null).await();
            assertTrue(bids.size() > 0);
        }

        // should return bid
        {
            AdBidFilter filter = new AdBidFilter().withSizes(AdSize.HORIZONTAL_480x60).withPriceSlot(AdPriceSlot.BTC1_000);
            filter.withAuthor(advertiserKeyPair.getPublicKey());

            System.out.println("Fetching bid with filter: " + filter);
            List<RankedAd> bids = queue.fetchBids(List.of(filter), null).await();
            assertTrue(bids.size() > 0);
        }
    }

    @Test
    public void testDisplayFlow() throws Exception {
        String nwc =
            "nostr+walletconnect://8e1e934ea0dd99cc2949805ed577abe76bb7d8c34d2d44a9e5f144a308b831f3?relay=wss://nostr.rblb.it&secret=520eaf4b4e7fcc0bb1498829955179e0693eeed59a2453807096e6bfd81cfb12";

        AdTaxonomy taxonomy = new AdTaxonomy();

        NostrKeyPair advertiserKeyPair = new NostrKeyPair(NostrPrivateKey.generate());
        NostrKeyPairSigner advertiserSigner = new NostrKeyPairSigner(advertiserKeyPair);

        NostrKeyPair delegateKeyPair = new NostrKeyPair(NostrPrivateKey.generate());
        NostrKeyPairSigner delegateSigner = new NostrKeyPairSigner(delegateKeyPair);

        NostrKeyPair offererKeyPair = new NostrKeyPair(NostrPrivateKey.generate());
        NostrKeyPairSigner offererSigner = new NostrKeyPairSigner(offererKeyPair);

        System.out.println("Advertiser key: " + advertiserKeyPair.getPublicKey());
        System.out.println("Delegate key: " + delegateKeyPair.getPublicKey());
        System.out.println("Offerer key: " + offererKeyPair.getPublicKey());

        AdvertiserClient advClient = new AdvertiserClient(pool, advertiserSigner, taxonomy);

        AdBidEvent bid = advClient
            .newBid(
                null,
                "Test Bid" + Math.random() + Instant.now().toEpochMilli(),
                null,
                List.of(taxonomy.getByPath("Technology & Computing/Virtual Reality")),
                null,
                null,
                null,
                AdMimeType.TEXT_PLAIN,
                "This is a test bid",
                AdSize.HORIZONTAL_480x60,
                "https://ngengine.org",
                "Click here!",
                AdActionType.VIEW,
                3000,
                Duration.ofSeconds(60 * 5),
                delegateKeyPair.getPublicKey(),
                Map.of("nwc", nwc, "dailyBudget", 4000),
                Instant.now().plusSeconds(60 * 5),
                21,
                Duration.ofSeconds(1)
            )
            .await();

        System.out.println("Publishing bid: " + bid);
        (advClient.publishBid(bid)).await();

        AdBidEvent bid2 = advClient
            .newBid(
                null,
                "Test Bid2" + Math.random() + Instant.now().toEpochMilli(),
                null,
                List.of(taxonomy.getByPath("Technology & Computing/Virtual Reality")),
                null,
                null,
                null,
                AdMimeType.TEXT_PLAIN,
                "This is a test bid",
                AdSize.HORIZONTAL_480x60,
                "https://ngengine.org",
                "Click here!",
                AdActionType.VIEW,
                1000,
                Duration.ofSeconds(60 * 5),
                delegateKeyPair.getPublicKey(),
                Map.of("nwc", nwc, "dailyBudget", 4000),
                Instant.now().plusSeconds(60 * 5),
                21,
                Duration.ofSeconds(1)
            )
            .await();

        System.out.println("Publishing bid2: " + bid);
        (advClient.publishBid(bid)).await();

        PenaltyStorage penaltyStorage = new PenaltyStorage(
            NGEUtils.getPlatform().getDataStore("unit-tests-Ad" + Math.random(), "penalty")
        );

        Tracker dailyBudgetTracker = new Tracker(NGEPlatform.get().getDataStore("unit-tests-Ad" + Math.random(), "tracker"));
        DelegateService delegate = new DelegateService(
            pool,
            delegateSigner,
            taxonomy,
            null,
            null,
            penaltyStorage,
            dailyBudgetTracker
        );

        delegate.listen(Instant.now().minusSeconds(160));
        System.out.println("Starting display client...");
        NGEPlatform
            .get()
            .wrapPromise((res2, rej2) -> {
                // simulate an ad space

                AdsDisplayClient offererClient = new AdsDisplayClient(
                    pool,
                    offererSigner,
                    taxonomy,
                    penaltyStorage,
                    (neg, offer, reason) -> {}
                );
                int width = 480;
                int height = 60;
                Adspace adspace = new Adspace(
                    NostrPublicKey.fromBech32(APP_KEY),
                    NostrPrivateKey.generate().getPublicKey(),
                    AdAspectRatio.fromDimensions(width, height),
                    AdPriceSlot.BTC2_000,
                    List.of(AdMimeType.TEXT_PLAIN)
                );
                offererClient.registerAdspace(adspace);

                offererClient.loadNextAd(
                    adspace,
                    width,
                    height,
                    ad ->
                        NGEPlatform
                            .get()
                            .wrapPromise((res, rej) -> {
                                res.accept(ad.getId().equals(bid.getId()));
                            }),
                    (f, b) -> {
                        return NGEPlatform
                            .get()
                            .wrapPromise((res, rej) -> {
                                System.out.println("Showing bid: " + b);
                                res.accept(true); // simulate showing the ad
                            });
                    },
                    (neg, offer, success, message) -> {
                        res2.accept(null);
                    }
                );
            })
            .catchException(ex -> {
                ex.printStackTrace();
            })
            .await();
    }
}
