package org.ngengine.nostrads.client.services.display.fun;

import org.ngengine.nostrads.client.negotiation.NegotiationHandler;
import org.ngengine.nostrads.protocol.negotiation.AdOfferEvent;

public interface CompletedCallback {
        public void accept(NegotiationHandler neg, AdOfferEvent offer, boolean success, String message);

}
