/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * conditions in the project LICENSE file are met.
 */
package org.ngengine.nostrads.client.negotiation;

import java.util.Map;
import org.ngengine.lnurl.LnUrl;
import org.ngengine.lnurl.LnUrlPay;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

/**
 * Retains the exact LNURL metadata used to bind a payment invoice to its service response.
 */
public final class LnUrlInvoiceRequest {

    private final LnUrlPay service;
    private final String metadata;

    private static final class ParsedPayService extends LnUrlPay {

        ParsedPayService(Map<String, Object> response) {
            super(response);
        }
    }

    private LnUrlInvoiceRequest(LnUrlPay service, String metadata) {
        this.service = service;
        this.metadata = metadata;
    }

    /**
     * Fetches the service and retains its metadata before any JSON reconstruction.
     */
    public static AsyncTask<LnUrlInvoiceRequest> fetch(LnUrl lnurl) {
        return NGEPlatform
            .get()
            .httpGet(lnurl.toURI().toString(), LnUrl.DEFAULT_TIMEOUT, null)
            .then(LnUrlInvoiceRequest::fromResponse);
    }

    @SuppressWarnings("unchecked")
    static LnUrlInvoiceRequest fromResponse(String response) {
        Map<String, Object> data = NGEPlatform.get().fromJSON(response, Map.class);
        if (!(data.get("metadata") instanceof String)) {
            throw new IllegalArgumentException("LNURL service metadata must be a JSON string");
        }
        // lnurl4j reconstructs metadata in toMap(); whitespace and escapes must
        // remain unchanged when checking the invoice's description hash.
        return new LnUrlInvoiceRequest(new ParsedPayService(data), (String) data.get("metadata"));
    }

    public LnUrlPay service() {
        return service;
    }

    public String metadata() {
        return metadata;
    }
}
