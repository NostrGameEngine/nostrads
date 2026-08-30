/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * conditions in the project LICENSE file are met.
 */
package org.ngengine.nostrads.security;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.event.SignedNostrEvent.ReceivedSignedNostrEvent;
import org.ngengine.nostr4j.proto.NostrMessage;

/**
 * Rejects unauthentic relay events before Nostr4j's event tracker can record their IDs.
 */
public class VerifiedNostrPool extends NostrPool {

    private static final Logger logger = Logger.getLogger(VerifiedNostrPool.class.getName());
    private static final int MAX_CONTENT_CHARS = 256 * 1024;
    private static final int MAX_TAGS = 100;
    private static final int MAX_TAG_CELLS = 16;
    private static final int MAX_TAG_CELL_CHARS = 16 * 1024;

    @Override
    protected boolean onRelayMessage(NostrRelay relay, NostrMessage message) {
        if (message instanceof ReceivedSignedNostrEvent) {
            ReceivedSignedNostrEvent event = (ReceivedSignedNostrEvent) message;
            try {
                if (event.getContent() == null || event.getContent().length() > MAX_CONTENT_CHARS) {
                    logger.warning("Rejected oversized relay event content");
                    return true;
                }
                if (event.getTagRows().size() > MAX_TAGS) {
                    logger.warning("Rejected relay event with too many tags");
                    return true;
                }
                for (java.util.List<String> tag : event.getTagRows()) {
                    if (tag.size() > MAX_TAG_CELLS) return true;
                    for (String value : tag) {
                        if (value == null || value.length() > MAX_TAG_CELL_CHARS) return true;
                    }
                }
                if (!event.verify()) {
                    logger.warning("Rejected relay event with invalid signature or id");
                    return true;
                }
            } catch (Exception verificationError) {
                logger.log(Level.WARNING, "Rejected relay event that could not be verified", verificationError);
                return true;
            }
        }
        return super.onRelayMessage(relay, message);
    }
}
