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

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.protocol.AdProfileMetadata;
import org.ngengine.nostrads.security.VerifiedNostrPool;
import org.ngengine.nostrads.types.ConsoleDataCallback;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.teavm.TeaVMJsConverter;
import org.ngengine.wallets.nip47.NWCUri;
import org.ngengine.wallets.nip47.NWCWallet;
import org.teavm.jso.JSExport;

public class ConsoleNetwork {

    private final JavaContext context = new JavaContext();
    private final List<NostrPool> pools = new ArrayList<>();
    private boolean closed;

    @JSExport
    public ConsoleNetwork() {
        NostrAdsModule.initPlatform();
    }

    private synchronized NostrPool newPool() {
        if (closed) throw new IllegalStateException("Console network closed");
        NostrPool pool = new VerifiedNostrPool();
        pools.add(pool);
        return pool;
    }

    private synchronized void release(NostrPool pool) {
        if (pool == null) return;
        pools.remove(pool);
        pool.close();
    }

    @JSExport
    public synchronized void close() {
        closed = true;
        for (NostrPool pool : new ArrayList<>(pools)) pool.close();
        pools.clear();
    }

    @JSExport
    public void discoverDelegates(String[] relays, ConsoleDataCallback callback) {
        context.run(() -> {
            NostrPool pool = null;
            try {
                pool = newPool();
                for (String relay : relays) pool.ensureRelay(relay);
                List<SignedNostrEvent> events = pool
                    .fetch(AdProfileMetadata.delegateFilter().limit(100), 100, true, Duration.ofSeconds(8))
                    .await();
                Map<String, SignedNostrEvent> profiles = new LinkedHashMap<>();
                for (SignedNostrEvent event : events) {
                    if (!AdProfileMetadata.hasRole(event, AdProfileMetadata.DELEGATE)) continue;
                    String key = event.getPubkey().asHex();
                    SignedNostrEvent previous = profiles.get(key);
                    if (previous == null || event.getCreatedAt().isAfter(previous.getCreatedAt()) ||
                        (event.getCreatedAt().equals(previous.getCreatedAt()) && event.getId().compareTo(previous.getId()) < 0)) {
                        profiles.put(key, event);
                    }
                }
                List<Map<String, Object>> result = new ArrayList<>();
                for (SignedNostrEvent event : profiles.values()) result.add(event.toMap());
                callback.accept(TeaVMJsConverter.toJSObject(Map.of("profiles", result)), null);
            } catch (Exception error) {
                callback.accept(null, "Could not search for delegates");
            } finally {
                release(pool);
            }
        });
    }

    @JSExport
    public void checkWalletBudget(String connection, ConsoleDataCallback callback) {
        context.run(() -> {
            NostrPool pool = null;
            try {
                NWCUri uri = new NWCUri(connection);
                if (uri.getRelays().size() > 5) throw new IllegalArgumentException("Too many wallet relays");
                pool = newPool();
                for (String relay : uri.getRelays()) {
                    URI endpoint = URI.create(relay);
                    if (!"wss".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null) {
                        throw new IllegalArgumentException("Invalid wallet relay");
                    }
                    pool.ensureRelay(relay);
                }
                List<SignedNostrEvent> info = pool
                    .fetch(new NostrFilter().withKind(NWCWallet.INFO_KIND).withAuthor(uri.getPubkey()).limit(1), 1, Duration.ofSeconds(8))
                    .await();
                if (info.isEmpty() || !Arrays.asList(info.get(0).getContent().trim().split("\\s+")).contains("get_budget")) {
                    callback.accept(TeaVMJsConverter.toJSObject(Map.of("status", "unsupported")), null);
                    return;
                }
                String modes = info.get(0).getFirstTagFirstValue("encryption");
                List<String> encryption = modes == null ? List.of("nip04") : Arrays.asList(modes.trim().split("\\s+"));
                String mode = encryption.contains("nip44_v2") ? "nip44_v2" : "nip04";
                if (!encryption.contains(mode)) throw new IllegalArgumentException("Unsupported wallet encryption");
                NostrSigner.EncryptAlgo algorithm = "nip44_v2".equals(mode) ? NostrSigner.EncryptAlgo.NIP44 : NostrSigner.EncryptAlgo.NIP04;
                NostrSigner signer = uri.getSigner();
                String requestBody = NGEPlatform.get().toJSON(Map.of("method", "get_budget", "params", Map.of()));
                UnsignedNostrEvent request = new UnsignedNostrEvent()
                    .withKind(NWCWallet.REQUEST_KIND)
                    .withTag("p", uri.getPubkey().asHex())
                    .withExpiration(Instant.now().plusSeconds(20))
                    .withContent(signer.encrypt(requestBody, uri.getPubkey(), algorithm).await());
                if (modes != null) request.withTag("encryption", mode);
                SignedNostrEvent signed = signer.sign(request).await();
                AsyncTask<List<SignedNostrEvent>> reply = pool.fetch(
                    new NostrFilter().withKind(NWCWallet.RESPONSE_KIND).withAuthor(uri.getPubkey())
                        .withTag("p", signer.getPublicKey().await().asHex()).withTag("e", signed.getId()).limit(1),
                    1, Duration.ofSeconds(10)
                );
                pool.publish(signed);
                List<SignedNostrEvent> responses = reply.await();
                if (responses.isEmpty()) throw new IllegalStateException("Wallet timeout");
                String plaintext = signer.decrypt(responses.get(0).getContent(), uri.getPubkey(), algorithm).await();
                Map<String, Object> response = NGEPlatform.get().fromJSON(plaintext, Map.class);
                if (!"get_budget".equals(response.get("result_type")) || response.get("error") != null) {
                    throw new IllegalStateException("Budget unavailable");
                }
                Map<String, Object> result = (Map<String, Object>) response.get("result");
                if (result == null) throw new IllegalStateException("Invalid wallet result");
                Map<String, Object> budget = new HashMap<>();
                if (result.isEmpty()) {
                    budget.put("status", "unlimited");
                } else {
                    long total = amount(result.get("total_budget"));
                    long used = amount(result.get("used_budget"));
                    String period = String.valueOf(result.get("renewal_period"));
                    if (!List.of("daily", "weekly", "monthly", "yearly", "never").contains(period)) {
                        throw new IllegalStateException("Invalid budget period");
                    }
                    if (!"never".equals(period)) budget.put("renewsAt", amount(result.get("renews_at")));
                    budget.put("status", "limited");
                    budget.put("totalMsats", total);
                    budget.put("usedMsats", used);
                    budget.put("period", period);
                }
                callback.accept(TeaVMJsConverter.toJSObject(budget), null);
            } catch (Exception error) {
                callback.accept(null, "Could not verify this wallet's spending limit");
            } finally {
                release(pool);
            }
        });
    }

    private static long amount(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid budget amount");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number < 0 || number > 9007199254740991D || Math.rint(number) != number) {
            throw new IllegalArgumentException("Invalid budget amount");
        }
        return (long) number;
    }
}
