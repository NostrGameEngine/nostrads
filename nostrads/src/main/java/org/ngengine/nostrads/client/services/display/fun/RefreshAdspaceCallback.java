package org.ngengine.nostrads.client.services.display.fun;

import org.ngengine.nostrads.client.negotiation.NegotiationHandler;
import org.ngengine.nostrads.protocol.negotiation.AdOfferEvent;

public interface RefreshAdspaceCallback {
    public void accept(NegotiationHandler neg, AdOfferEvent offer, String reason);
}
