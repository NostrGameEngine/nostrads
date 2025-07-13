package org.ngengine.nostrads.types;

import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

@JSFunctor
@FunctionalInterface
public interface PublicKeyCallback extends JSObject {
    void accept(String pubkey, String error);
}