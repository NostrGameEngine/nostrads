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

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip44.Nip44;
import org.ngengine.nostr4j.nip46.BunkerUrl;
import org.ngengine.nostr4j.nip46.Nip46AppMetadata;
import org.ngengine.nostr4j.nip49.Nip49;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.signer.NostrNIP07Signer;
import org.ngengine.nostr4j.signer.NostrNIP46Signer;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.types.PublicKeyCallback;
import org.ngengine.nostrads.types.SessionNoticeCallback;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.teavm.jso.JSExport;

/**
 * Holds an active console signer and exports the credentials needed to restore it.
 */
public class SignerSession implements NostrSigner {

    private static final Map<String, SignerSession> sessions = new HashMap<>();
    private final String auth;
    private NostrSigner delegate;
    private NostrPrivateKey ownedKey;
    private boolean closed;
    private boolean started;
    private boolean ready;
    private String method;
    private BunkerUrl remoteBunker;
    private String connectSecret;
    private List<String> connectRelays;

    @JSExport
    public SignerSession() {
        NostrAdsModule.initPlatform();
        auth = "session:" + NostrPrivateKey.generate().asHex();
        sessions.put(auth, this);
    }

    static NostrSigner resolve(String auth) {
        SignerSession session = sessions.get(auth);
        if (session == null) throw new IllegalStateException("The signer session has ended. Log in again.");
        session.requireSigner();
        return session;
    }

    @JSExport
    public String getAuth() {
        return auth;
    }

    @JSExport
    public String exportState() {
        requireSigner();
        Map<String, Object> state = new HashMap<>();
        state.put("method", method);
        if (ownedKey != null) state.put("key", ownedKey.asHex());
        if (delegate instanceof NostrNIP46Signer) {
            if (remoteBunker == null) throw new IllegalStateException("Remote signer connection is unavailable.");
            state.put("bunker", remoteBunker.toString());
        }
        return NGEPlatform.get().toJSON(state);
    }

    @JSExport
    public void restore(String saved, PublicKeyCallback callback, SessionNoticeCallback notice) {
        try {
            Map<?, ?> state = NGEPlatform.get().fromJSON(saved, Map.class);
            String savedMethod = (String) state.get("method");
            PublicKeyCallback restored = (pubkey, error) -> {
                method = savedMethod;
                callback.accept(pubkey, error);
            };
            if ("extension".equals(savedMethod)) {
                login("extension", "", "", new String[0], restored, notice);
            } else if ("nsec".equals(savedMethod) || "ncryptsec".equals(savedMethod)) {
                login("nsec", (String) state.get("key"), "", new String[0], restored, notice);
            } else if ("bunker".equals(savedMethod) || "connect".equals(savedMethod)) {
                if (started || closed) throw new IllegalStateException();
                ownedKey = NostrPrivateKey.fromHex((String) state.get("key"));
                login("bunker", (String) state.get("bunker"), "", new String[0], restored, notice);
            } else {
                throw new IllegalArgumentException();
            }
        } catch (Exception error) {
            fail(callback, "Could not restore your account. Log in again.");
        }
    }

    @JSExport
    public void login(
        String method,
        String value,
        String password,
        String[] relays,
        PublicKeyCallback callback,
        SessionNoticeCallback notice
    ) {
        if (started || closed) {
            callback.accept(null, "This login attempt has ended. Please try again.");
            return;
        }
        started = true;
        this.method = method;
        try {
            if ("ncryptsec".equals(method)) {
                Nip49
                    .decrypt(value, password, 256 * 1024 * 1024)
                    .then(key -> {
                        if (closed) {
                            key.destroy();
                        } else {
                            ownedKey = key;
                            delegate = new NostrKeyPairSigner(new NostrKeyPair(key));
                            finish(callback);
                        }
                        return null;
                    })
                    .catchException(error ->
                        fail(
                            callback,
                            "Could not unlock this key. Check the key and password (maximum scrypt memory: 256 MiB)."
                        )
                    );
                return;
            }
            if ("extension".equals(method)) {
                delegate = new NostrNIP07Signer();
            } else if ("nsec".equals(method)) {
                if (value.matches("[0-9a-fA-F]{64}")) {
                    ownedKey = NostrPrivateKey.fromHex(value);
                } else {
                    if (!value.startsWith("nsec1")) throw new IllegalArgumentException();
                    ownedKey = NostrPrivateKey.fromBech32(value);
                }
                delegate = new NostrKeyPairSigner(new NostrKeyPair(ownedKey));
            } else if ("bunker".equals(method) || "connect".equals(method)) {
                Nip46AppMetadata metadata = new Nip46AppMetadata().setName("NostrAds Console");
                NostrNIP46Signer remote = new NostrNIP46Signer(
                    metadata,
                    ownedKey == null ? new NostrKeyPair() : new NostrKeyPair(ownedKey)
                ) {
                    @Override
                    public void onSubEvent(NostrSubscription sub, SignedNostrEvent event, boolean stored) {
                        rememberConnection(event);
                        super.onSubEvent(sub, event, stored);
                    }
                };
                delegate = remote;
                ownedKey = remote.getTransportKeyPair().getPrivateKey();
                remote.setRequestsTimeout(Duration.ofMinutes(3));
                remote.setChallengeHandler(
                    (type, url) -> {
                        if (!closed) notice.accept(type, url);
                        return error -> {};
                    },
                    Duration.ofMinutes(3)
                );
                AsyncTask<NostrNIP46Signer> connection;
                if ("bunker".equals(method)) {
                    if (!value.startsWith("bunker://")) throw new IllegalArgumentException();
                    BunkerUrl bunker = BunkerUrl.parse(value);
                    if (bunker.relays.isEmpty()) throw new IllegalArgumentException();
                    for (String relay : bunker.relays) {
                        if (!relay.startsWith("wss://")) throw new IllegalArgumentException();
                    }
                    // Connection secrets are single-use; the transport key retains the authorization.
                    remoteBunker = new BunkerUrl(bunker.pubkey, bunker.relays);
                    connection = remote.connect(bunker);
                } else {
                    connectRelays = Arrays.asList(relays);
                    connection =
                        remote.listen(
                            Arrays.asList(relays),
                            url -> {
                                connectSecret = url.secret;
                                if (!closed) notice.accept("nostrconnect", url.toString());
                            },
                            Duration.ofMinutes(3)
                        );
                }
                connection
                    .then(signer -> {
                        connectSecret = null;
                        if (!closed) finish(callback);
                        return null;
                    })
                    .catchException(error ->
                        fail(
                            callback,
                            "Remote signer did not connect. Check the connection link and approve the request in your signer."
                        )
                    );
                return;
            } else {
                throw new IllegalArgumentException();
            }
            finish(callback);
        } catch (Exception error) {
            fail(callback, "Could not log in. Check the key, signer or browser extension and try again.");
        }
    }

    private void rememberConnection(SignedNostrEvent event) {
        if (closed || connectSecret == null || remoteBunker != null || event.getKind() != 24133) return;
        try {
            if (!ownedKey.getPublicKey().asHex().equals(event.getFirstTagFirstValue("p")) || !event.verify()) return;
            Map<?, ?> response = NGEPlatform
                .get()
                .fromJSON(
                    Nip44.decryptSync(event.getContent(), Nip44.getConversationKeyBufferSync(ownedKey, event.getPubkey())),
                    Map.class
                );
            Object error = response.get("error");
            if (connectSecret.equals(response.get("result")) && (error == null || error.toString().isBlank())) {
                // Keep the authenticated connect-response author, which may differ from the user's pubkey.
                remoteBunker = new BunkerUrl(event.getPubkey(), connectRelays);
            }
        } catch (Exception ignored) {
            // The NIP-46 signer handles invalid responses and completes the login itself.
        }
    }

    private void finish(PublicKeyCallback callback) {
        delegate
            .getPublicKey()
            .then(key -> {
                if (!closed) {
                    ready = true;
                    callback.accept(key.asHex(), null);
                }
                return null;
            })
            .catchException(error -> fail(callback, "The signer could not provide your public key. Please try again."));
    }

    private void fail(PublicKeyCallback callback, String message) {
        if (closed) return;
        logout();
        callback.accept(null, message);
    }

    @JSExport
    public void logout() {
        if (closed) return;
        closed = true;
        ready = false;
        connectSecret = null;
        remoteBunker = null;
        connectRelays = null;
        sessions.remove(auth);
        NostrSigner old = delegate;
        delegate = null;
        NostrPrivateKey key = ownedKey;
        ownedKey = null;
        if (old != null) {
            old
                .close()
                .then(ignored -> {
                    if (key != null) key.destroy();
                    return null;
                })
                .catchException(error -> {
                    if (key != null) key.destroy();
                });
        } else if (key != null) key.destroy();
    }

    private NostrSigner requireSigner() {
        if (closed || !ready || delegate == null) throw new IllegalStateException("Log in to continue.");
        return delegate;
    }

    @Override
    public AsyncTask<NostrPublicKey> getPublicKey() {
        return requireSigner()
            .getPublicKey()
            .then(key -> {
                requireSigner();
                return key;
            });
    }

    @Override
    public AsyncTask<SignedNostrEvent> sign(UnsignedNostrEvent event) {
        return requireSigner()
            .sign(event)
            .then(signed -> {
                requireSigner();
                return signed;
            });
    }

    @Override
    public AsyncTask<String> encrypt(String message, NostrPublicKey key, EncryptAlgo algo) {
        return requireSigner()
            .encrypt(message, key, algo)
            .then(result -> {
                requireSigner();
                return result;
            });
    }

    @Override
    public AsyncTask<String> decrypt(String message, NostrPublicKey key, EncryptAlgo algo) {
        return requireSigner()
            .decrypt(message, key, algo)
            .then(result -> {
                requireSigner();
                return result;
            });
    }

    @Override
    public AsyncTask<NostrSigner> close() {
        logout();
        return org.ngengine.platform.NGEPlatform.get().wrapPromise((resolve, reject) -> resolve.accept(this));
    }
}
