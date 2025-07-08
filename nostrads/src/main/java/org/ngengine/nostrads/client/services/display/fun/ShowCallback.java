package org.ngengine.nostrads.client.services.display.fun;

import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.negotiation.AdOfferEvent;
import org.ngengine.platform.AsyncTask;

public interface ShowCallback {
    public AsyncTask<Boolean> apply(  AdBidEvent bidEvent, AdOfferEvent offer );
}
