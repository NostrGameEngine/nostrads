package org.ngengine.nostrads.types;

import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

@JSFunctor
@FunctionalInterface

public interface UploadCallback extends JSObject{
    void accept(JSObject descriptor, String error);
}
