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

import java.io.Closeable;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip01.Nip01;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.signer.NostrNIP07Signer;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.nostrads.types.Nip01Callback;
import org.ngengine.nostrads.types.PublicKeyCallback;
import org.ngengine.platform.teavm.TeaVMJsConverter;
import org.teavm.jso.JSObject;

public abstract class NostrAds implements Closeable {

    private static final Logger logger = Logger.getLogger(NostrAds.class.getName());

    protected NostrPool pool;
    protected AdTaxonomy taxonomy;
    protected NostrSigner signer;
    protected boolean initialized = false;

    public void close() {
        synchronized (this) {
            pool.close();
        }
    }

    protected NostrAds() throws Exception {
 
    }

    protected void init(String[] relays, String auth) {
        try {
            synchronized(this){
                if(initialized) return;
                initialized = true;
                NostrAdsModule.initPlatform();

                pool = new NostrPool();
                for (int i = 0; i < relays.length; i++) {
                    pool.connectRelay(new NostrRelay(relays[i]));
                }

                signer = getSigner(auth);

                taxonomy = new AdTaxonomy();
            }
        } catch (Exception ex) {
            throw new RuntimeException("Error initializing NostrAds: " + ex.getMessage(), ex);
        }
    }

    protected NostrPublicKey pubkeyFromString(String pubkeyStr) {
        if (pubkeyStr.startsWith("npub")) {
            return NostrPublicKey.fromBech32(pubkeyStr);
        } else {
            return NostrPublicKey.fromHex(pubkeyStr);
        }
    }

    protected NostrSigner getSigner(String signerProps) throws Exception {
        if (signerProps.equals("nip07")) {
            NostrNIP07Signer signer = new NostrNIP07Signer();
            boolean v = signer.isAvailable().await();
            if (!v) {
                throw new Exception(
                    "NIP-07 signer is not available. Please ensure you have a Nostr wallet extension installed and enabled."
                );
            }
            return signer;
        } else {
            NostrPrivateKey adsKeyN = null;
            if (signerProps == null || signerProps.isEmpty()) {
                adsKeyN = NostrPrivateKey.generate();
            } else {
                adsKeyN =
                    signerProps.startsWith("nsec")
                        ? NostrPrivateKey.fromBech32(signerProps)
                        : NostrPrivateKey.fromHex(signerProps);
            }
            return new NostrKeyPairSigner(new NostrKeyPair(adsKeyN));
        }
    }

    protected void getPublicKey(PublicKeyCallback callback) throws Exception {
        synchronized (this) {
            signer
                .getPublicKey()
                .then(pkey -> {
                    callback.accept(pkey.asHex(), null);
                    return null;
                })
                .catchException(err -> {
                    logger.log(Level.SEVERE, "Error getting public key " + err);
                    callback.accept(null, err.toString());
                });
        }
    }

    protected void getNip01Meta(String pubkey, Nip01Callback callback) {
        synchronized (this) {
            NostrPublicKey key = pubkeyFromString(pubkey);
            Nip01
                .fetch(pool, key)
                .then(meta -> {
                    JSObject metaObj = TeaVMJsConverter.toJSObject(meta.metadata);
                    callback.accept(metaObj, null);
                    return null;
                })
                .catchException(err -> {
                    logger.log(Level.SEVERE, "Error fetching NIP-01 metadata", err);
                    callback.accept(null, err.getMessage());
                });
        }
    }
}
