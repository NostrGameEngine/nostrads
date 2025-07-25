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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Logger;
import org.ngengine.lnurl.LnAddress;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.nip01.Nip01;
import org.ngengine.nostr4j.nip01.Nip01UserMetadata;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostrads.client.negotiation.DelegateNegotiationHandler;
import org.ngengine.nostrads.client.services.PenaltyStorage;
import org.ngengine.nostrads.client.services.delegate.DelegateService;
import org.ngengine.nostrads.client.services.delegate.Tracker;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.negotiation.AdOfferEvent;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.VStore;

public class DelegateServer {

    private Logger logger = Logger.getLogger(DelegateServer.class.getName());
    private static final Map<String, Object> config = new HashMap<>();

    private static final List<String> DEFAULT_RELAYS = List.of(
        "wss://relay.ngengine.org",
        "wss://relay2.ngengine.org",
        "wss://relay.damus.io",
        "wss://relay.primal.net",
        "wss://relay.nostr.band"
    );
    private static final String TEST_RELAY = "wss://nostr.rblb.it";

    @SuppressWarnings("unchecked")
    private static void load() throws Exception {
        String name = (String) config.getOrDefault("name", "Nostr Ads Delegate");
        String id = (String) config.get("id");

        VStore penaltyStore = NGEPlatform.get().getDataStore("nostrads-delegate-" + name, "penalty");
        VStore trackerStore = NGEPlatform.get().getDataStore("nostrads-delegate-" + name, "tracker");
        String key = (String) config.getOrDefault("key", null);
        List<String> relays = (List<String>) config.getOrDefault("relays", DEFAULT_RELAYS);
        List<String> biddersBlacklist = (List<String>) config.computeIfAbsent("biddersBlacklist", r -> null);
        List<String> biddersWhitelist = (List<String>) config.computeIfAbsent("biddersWhitelist", r -> null);
        List<String> offerersBlacklist = (List<String>) config.computeIfAbsent("offerersBlacklist", r -> null);
        List<String> offerersWhitelist = (List<String>) config.computeIfAbsent("offerersWhitelist", r -> null);

        if (key == null) {
            VStore keyStore = NGEPlatform.get().getDataStore("nostrads-delegate-" + name, "key");
            try {
                if (keyStore.exists("key").await()) {
                    byte[] keyBytes = keyStore.readFully("key").await();
                    key = new String(keyBytes, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                System.err.println("Failed to load key from store: " + e.getMessage());
            }
            if (key == null || key.isEmpty()) {
                key = NostrPrivateKey.generate().asHex();
                try {
                    keyStore.writeFully("key", key.getBytes(StandardCharsets.UTF_8)).await();
                } catch (Exception e) {
                    System.err.println("Failed to save key to store: " + e.getMessage());
                }
            }
        }

        NostrKeyPair keyPair = new NostrKeyPair(NostrPrivateKey.fromHex(key));

        Function<AdBidEvent, AsyncTask<Boolean>> filterBids = bid -> {
            if (biddersBlacklist != null && biddersBlacklist.contains(bid.getPubkey().asHex())) {
                return NGEPlatform
                    .get()
                    .wrapPromise((res, rej) -> {
                        res.accept(false);
                    });
            }
            if (biddersWhitelist != null && !biddersWhitelist.contains(bid.getPubkey().asHex())) {
                return NGEPlatform
                    .get()
                    .wrapPromise((res, rej) -> {
                        res.accept(false);
                    });
            }
            return NGEPlatform
                .get()
                .wrapPromise((res, rej) -> {
                    res.accept(true);
                });
        };

        BiFunction<DelegateNegotiationHandler, AdOfferEvent, AsyncTask<Boolean>> filterOffers = (negotiation, offer) -> {
            if (offerersBlacklist != null && offerersBlacklist.contains(offer.getPubkey().asHex())) {
                return NGEPlatform
                    .get()
                    .wrapPromise((res, rej) -> {
                        res.accept(false);
                    });
            }
            if (offerersWhitelist != null && !offerersWhitelist.contains(offer.getPubkey().asHex())) {
                return NGEPlatform
                    .get()
                    .wrapPromise((res, rej) -> {
                        res.accept(false);
                    });
            }
            return NGEPlatform
                .get()
                .wrapPromise((res, rej) -> {
                    res.accept(true);
                });
        };

        NostrPool pool = new NostrPool();
        for (String relay : relays) {
            pool.connectRelay(new NostrRelay(relay));
        }

        System.out.println("Connected to relays: " + pool.getRelays().size());

        AdTaxonomy taxonomy = new AdTaxonomy();

        DelegateService service = new DelegateService(
            pool,
            new NostrKeyPairSigner(keyPair),
            taxonomy,
            filterOffers,
            filterBids,
            new PenaltyStorage(penaltyStore),
            new Tracker(trackerStore)
        );
        String collectorLnAddress = (String) config.getOrDefault("feeCollectorLnAddress", null);
        service.setFee(
            (long) config.getOrDefault("minFeeMsats", 0L),
            (double) config.getOrDefault("percentFee", 0.01),
            (long) config.getOrDefault("maxFeeMsats", 10000L),
            collectorLnAddress == null ? null : new LnAddress(collectorLnAddress)
        );

        Nip01UserMetadata userMetadata = null;
        try {
            System.out.println("Fetching user metadata for " + keyPair.getPublicKey().asBech32());
            userMetadata = Nip01.fetch(pool, keyPair.getPublicKey()).await();
            if (userMetadata == null) throw new IllegalStateException("Not set");
        } catch (Exception e) {
            System.err.println("Failed to get user metadata: " + e.getMessage());
        }

        if (userMetadata == null) {
            userMetadata = new Nip01UserMetadata();
            userMetadata.setName(id);
            userMetadata.setDisplayName(name);
            userMetadata.setAbout("Nostrads delegate service");
            userMetadata.setWebsite("https://ads.ngengine.org");
        }

        if (collectorLnAddress != null) {
            userMetadata.metadata.put(
                "nostrads:fees",
                String.format(
                    "%d:%f:%d",
                    config.getOrDefault("minFeeMsats", 0L),
                    config.getOrDefault("percentFee", 0),
                    config.getOrDefault("maxFeeMsats", 10000L)
                )
            );
        } else {
            userMetadata.metadata.remove("nostrads:fees");
        }

        try {
            Nip01.update(pool, new NostrKeyPairSigner(keyPair), userMetadata).await();
        } catch (Exception e) {
            System.err.println("Failed to update user metadata: " + e.getMessage());
        }

        System.out.println("Starting delegate service...");
        System.out.println("");
        System.out.println("Pubkey: " + keyPair.getPublicKey().asHex());
        System.out.println(
            "Fee: " +
            config.getOrDefault("percentFee", 0) +
            "% (min " +
            config.getOrDefault("minFeeMsats", 0L) +
            " msats, max " +
            config.getOrDefault("maxFeeMsats", 10000L) +
            " msats)"
        );
        if (collectorLnAddress != null) {
            System.out.println("Fee collector: " + collectorLnAddress);
        } else {
            System.out.println("Fees are disabled, no fee collector set.");
        }
        System.out.println("");
        System.out.println("Connecting to relays: " + String.join(", ", relays));
        service.listen(Instant.now().minus(360, ChronoUnit.DAYS)).await();
    }

    private static String envName(String delegateId) {
        return delegateId.toUpperCase().replace(" ", "_");
    }

    private static String[] processEnvironmentVariables(String args[], String delegateId) {
        String envPrefix = "NOSTRADS_DELEGATE_" + envName(delegateId) + "_";

        // Create a list to hold the environment-based arguments
        List<String> envArgs = new ArrayList<>();

        // Get all environment variables
        Map<String, String> env = System.getenv();

        for (Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (key.startsWith(envPrefix)) {
                // Extract the argument name (everything after the prefix)
                String argName = key.substring(envPrefix.length());
                boolean isCsv = false;

                if (argName.endsWith("_CSV")) {
                    isCsv = true;
                    argName = argName.substring(0, argName.length() - 4); // Remove the _CSV suffix
                }

                // Convert to lowercase and add dashes
                String arg = "--" + argName.toLowerCase().replace("_", "-");

                if (isCsv) {
                    String[] values = value.split(",");
                    for (String v : values) {
                        envArgs.add(arg);
                        envArgs.add(v.trim());
                    }
                } else {
                    envArgs.add(value);
                }
            }
        }

        String mergedArgs[] = new String[args.length + envArgs.size()];
        System.arraycopy(args, 0, mergedArgs, 0, args.length);
        System.arraycopy(envArgs.toArray(new String[0]), 0, mergedArgs, args.length, envArgs.size());
        return mergedArgs;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Nostr Ads Delegate Server");
        System.out.println("Starting with args: " + String.join(" ", args));
        String id = null;

        for (int i = 0; i < args.length; i++) {
            String type = args[i];
            if (type.equals("--id")) {
                config.put("id", args[++i]);
                break;
            }
        }

        if (id == null) {
            for (int i = 0; i < args.length; i++) {
                String type = args[i];
                if (type.equals("--name")) {
                    config.put("id", args[++i].replaceAll(" ", "-").toLowerCase());
                    break;
                }
            }
        }

        if (id == null) {
            id = "nostrads-delegate";
        }

        args = processEnvironmentVariables(args, id);

        boolean isTestMode = System.getenv().get("NOSTRADS_DELEGATE" + envName(id) + "_TEST_MODE") != null;
        isTestMode = isTestMode || java.util.Arrays.asList(args).contains("--test");
        if (isTestMode) {
            System.out.println("Running in test mode.");

            ArrayList<String> devArgs = new ArrayList<>();
            devArgs.add("--name");
            devArgs.add("Nostr Ads Test Delegate");

            devArgs.add("--fee");
            devArgs.add("2000:0.02:10000:delegate@lntest.rblb.it");

            devArgs.add("--relay");
            devArgs.add(TEST_RELAY);

            devArgs.addAll(List.of(args));
            args = devArgs.toArray(new String[0]);
        }

        for (int i = 0; i < args.length; i++) {
            String type = args[i];
            try {
                switch (type) {
                    case "--name":
                        {
                            String name = args[++i];
                            config.put("name", name);
                            break;
                        }
                    case "--relay":
                        {
                            List<String> relays = (List<String>) config.computeIfAbsent("relays", r -> new ArrayList<String>());
                            relays.add(args[++i]);
                            break;
                        }
                    case "--key":
                        {
                            config.put("key", args[++i]);
                            break;
                        }
                    case "--disallowBidder":
                        {
                            List<String> biddersBlacklist = (List<String>) config.computeIfAbsent(
                                "biddersBlacklist",
                                r -> new ArrayList<String>()
                            );
                            biddersBlacklist.add(args[++i]);
                            break;
                        }
                    case "--allowBidder":
                        {
                            List<String> biddersWhitelist = (List<String>) config.computeIfAbsent(
                                "biddersWhitelist",
                                r -> new ArrayList<String>()
                            );
                            biddersWhitelist.add(args[++i]);
                            break;
                        }
                    case "--disallowOfferer":
                        {
                            List<String> offerersBlacklist = (List<String>) config.computeIfAbsent(
                                "offerersBlacklist",
                                r -> new ArrayList<String>()
                            );
                            offerersBlacklist.add(args[++i]);
                            break;
                        }
                    case "--allowOfferer":
                        {
                            List<String> offerersWhitelist = (List<String>) config.computeIfAbsent(
                                "offerersWhitelist",
                                r -> new ArrayList<String>()
                            );
                            offerersWhitelist.add(args[++i]);
                            break;
                        }
                    case "--fee":
                        {
                            // minfeeMsats:percentFee:maxFeeMsats:collectorLnAddress
                            String feeStr = args[++i];
                            String[] parts = feeStr.split(":", 4);
                            if (parts.length != 4) {
                                throw new IllegalArgumentException(
                                    "Invalid fee format, expected minFeeMsats:percentFee:maxFeeMsats:collectorLnAddress"
                                );
                            }
                            long minFeeMsats = Long.parseLong(parts[0]);
                            double percentFee = Double.parseDouble(parts[1]);
                            long maxFeeMsats = Long.parseLong(parts[2]);
                            String collectorLnAddress = parts[3];

                            if (collectorLnAddress != null && !collectorLnAddress.isEmpty()) {
                                config.put("feeCollectorLnAddress", collectorLnAddress);
                            } else {
                                config.remove("feeCollectorLnAddress");
                            }

                            config.put("minFeeMsats", minFeeMsats);
                            config.put("percentFee", percentFee);
                            config.put("maxFeeMsats", maxFeeMsats);
                            break;
                        }
                    case "--config":
                        {
                            String configFile = args[++i];
                            try {
                                try (
                                    BufferedInputStream fis = new BufferedInputStream(new FileInputStream(new File(configFile)))
                                ) {
                                    String data = new String(fis.readAllBytes(), StandardCharsets.UTF_8);
                                    Map<String, Object> loadedConfig = NGEPlatform.get().fromJSON(data, Map.class);
                                    config.putAll(loadedConfig);
                                }
                            } catch (Exception e) {
                                System.err.println("Failed to load config from " + configFile + ": " + e.getMessage());
                                System.exit(1);
                            }
                            break;
                        }
                    case "--help":
                        {
                            System.out.println("Usage: java -jar nostrads-delegate.jar [options]");
                            System.out.println("Options:");
                            System.out.println("  --relay <url>                Add a relay URL to connect to");
                            System.out.println("  --key <private_key>          Set the private key for the delegate");
                            System.out.println("  --disallowBidder <pubkey>    Disallow a bidder by their public key");
                            System.out.println("  --allowBidder <pubkey>       Allow a bidder by their public key");
                            System.out.println("  --disallowOfferer <pubkey>   Disallow an offerer by their public key");
                            System.out.println("  --allowOfferer <pubkey>      Allow an offerer by their public key");
                            System.out.println("  --name <name>                Set the name of the delegate server");
                            System.out.println(
                                "  --id                         Set the ID of the delegate server (default is name with spaces replaced by dashes)"
                            );
                            System.out.println(
                                "  --fee <minFeeMsats:percentFee:maxFeeMsats:collectorLnAddress> Set the fee structure. eg. 2000:0.05:10000:nostr4j@ln.rblb.it"
                            );
                            System.out.println("  --config <file>              Load configuration from a JSON file");
                            System.out.println("  --help                       Show this help message");
                            System.out.println("  --test                       Run in test mode (preconfigured for testing)");
                            System.exit(0);
                            break;
                        }
                    default:
                }
            } catch (Exception e) {
                System.err.println("Error processing argument " + args[i]);
                System.exit(1);
            }
        }
        load();
    }
}
