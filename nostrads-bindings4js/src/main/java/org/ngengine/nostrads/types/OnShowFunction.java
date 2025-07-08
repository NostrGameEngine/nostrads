package org.ngengine.nostrads.types;

import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

@JSFunctor
@FunctionalInterface

public interface OnShowFunction extends JSObject{
    public void accept(String id, JSObject bid, AdShowCallback confirm, AdShowCallback cancel);
}