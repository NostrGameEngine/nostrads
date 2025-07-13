package org.ngengine.nostrads.types;

import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

@JSFunctor
@FunctionalInterface
public interface RejectCallback extends JSObject{
    void accept(Throwable error);
}