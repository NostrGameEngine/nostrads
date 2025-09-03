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

package org.ngengine.nostrads.protocol;

import java.time.Instant;
import java.util.Map;
import java.util.logging.Logger;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.platform.NGEPlatform;

public abstract class AdEvent extends SignedNostrEvent {

    private static final Logger logger = Logger.getLogger(AdEvent.class.getName());
    protected Map<String, Object> data;

    protected AdEvent(Map<String, Object> map, Map<String, Object> data) {
        super(map);
        if (data != null) {
            this.data = data;
        }
    }

    protected Object getData(String key, boolean required) {
        if (data == null) {
            data = NGEPlatform.get().fromJSON(getContent(), Map.class);
        }
        Object v = data.get(key);
        if (required && v == null) {
            throw new IllegalArgumentException("Required data key '" + key + "' is missing in the event data");
        }
        return v;
    }

    protected String getTagData(String key, boolean required) {
        TagValue data = getFirstTag(key);
        if (data == null && required) {
            throw new IllegalArgumentException("Required tag '" + key + "' is missing in the event tags");
        }
        return data == null ? null : data.get(0);
    }

    public void checkValid() throws Exception {
        if (getExpiration() != null && getExpiration().isBefore(Instant.now())) {
            throw new Exception("Event has expired: " + getExpiration());
        }
    }

    public boolean isValid() {
        try {
            checkValid();
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    @Override
    public AdEvent clone() {
        AdEvent event = (AdEvent) super.clone();
        if(data!=null){
            event.data = Map.copyOf(data);
        }
        return event;    
    }
}
