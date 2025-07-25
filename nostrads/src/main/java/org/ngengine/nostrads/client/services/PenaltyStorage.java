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

package org.ngengine.nostrads.client.services;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostrads.client.negotiation.NegotiationHandler;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.VStore;

/**
 * PenaltyStorage is responsible for storing and retrieving POW penalties for Ad parties.
 * It uses a VStore to persist the penalties associated with each party's public key.
 */
public class PenaltyStorage {

    private static final Logger logger = Logger.getLogger(PenaltyStorage.class.getName());
    private final VStore store;

    public PenaltyStorage(VStore store) {
        this.store = store;
    }

    private String getPath(SignedNostrEvent event) {
        return "nostrads/powlist/" + event.getPubkey().asBech32() + ".dat";
    }

    public void set(NegotiationHandler neg) {
        int v = neg.getCounterpartyPenalty();
        this.store.write(getPath(neg.getBidEvent()))
            .then(os -> {
                try {
                    os.write(
                        new byte[] {
                            (byte) (v & 0xFF),
                            (byte) ((v >> 8) & 0xFF),
                            (byte) ((v >> 16) & 0xFF),
                            (byte) ((v >> 24) & 0xFF),
                        }
                    );
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Failed to store POW penalty", e);
                } finally {
                    try {
                        os.close();
                    } catch (IOException e) {
                        logger.log(Level.WARNING, "Failed to close output stream", e);
                    }
                }
                return null;
            });
    }

    public AsyncTask<Integer> get(AdBidEvent ev) {
        String path = getPath(ev);
        String pubkey = ev.getPubkey().asBech32();
        return store
            .exists(path)
            .catchException(ex -> {
                logger.log(Level.WARNING, "Failed to check if POW penalty exists for " + pubkey, ex);
            })
            .compose(exists -> {
                if (!exists) {
                    logger.fine("No POW penalty found for " + pubkey + ", returning default penalty of 0");
                    return NGEPlatform
                        .get()
                        .wrapPromise((res, rej) -> {
                            res.accept(0);
                        });
                }
                return store
                    .read(path)
                    .then(is -> {
                        byte[] data = new byte[4];
                        try {
                            int read = is.read(data);
                            if (read == 4) {
                                int penalty =
                                    (
                                        (data[0] & 0xFF) |
                                        ((data[1] & 0xFF) << 8) |
                                        ((data[2] & 0xFF) << 16) |
                                        ((data[3] & 0xFF) << 24)
                                    );
                                logger.fine("Read POW penalty for " + pubkey + ": " + penalty);
                                return penalty;
                            } else {
                                logger.warning(
                                    "Failed to read POW penalty for " + pubkey + ", expected 4 bytes but got " + read
                                );
                                return 0; // default penalty
                            }
                        } catch (IOException e) {
                            logger.log(Level.WARNING, "Failed to read POW penalty for " + pubkey, e);
                            return 0; // default penalty
                        } finally {
                            try {
                                is.close();
                            } catch (IOException e) {
                                logger.log(Level.WARNING, "Failed to close input stream", e);
                            }
                        }
                    });
            });
    }
}
