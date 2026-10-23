/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * conditions in the project LICENSE file are met.
 */
package org.ngengine.nostrads.client.negotiation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.ngengine.bolt11.Bolt11;
import org.ngengine.bolt11.Bolt11Invoice;
import org.ngengine.bolt11.Bolt11NetworkType;
import org.ngengine.bolt11.Bolt11Tag;
import org.ngengine.bolt11.Bolt11TagName;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public class TestLnUrlInvoiceRequest {

    private static LnUrlInvoiceRequest parse(String metadata) {
        return LnUrlInvoiceRequest.fromResponse(
            NGEPlatform
                .get()
                .toJSON(
                    Map.of(
                        "tag",
                        "payRequest",
                        "minSendable",
                        1,
                        "maxSendable",
                        1_000_000,
                        "callback",
                        "https://example.com/callback",
                        "metadata",
                        metadata
                    )
                )
        );
    }

    @Test
    public void acceptsInvoiceBoundToOriginalWhitespaceAndEscapes() throws Exception {
        String metadata = "[[\"text/plain\", \"Pay to caf\\u00e9\"], [\"text/identifier\", \"unit@example.com\"]]";
        LnUrlInvoiceRequest request = parse(metadata);
        assertEquals(metadata, request.metadata());
        String reconstructed = (String) request.service().toMap().get("metadata");
        assertNotEquals(metadata, reconstructed);
        String invoice = invoice(metadata);
        InvoiceValidator.validateLnurlInvoice(invoice, request.metadata(), 1_000, Bolt11NetworkType.MAINNET);
        assertThrows(
            IllegalArgumentException.class,
            () -> InvoiceValidator.validateLnurlInvoice(invoice, reconstructed, 1_000, Bolt11NetworkType.MAINNET)
        );
    }

    @Test
    public void retainsMetadataEntriesUnknownToTheParsedService() throws Exception {
        String metadata = "[[\"text/plain\",\"Payment\"],[\"future/example\",{\"label\":\"月\"},42]]";
        LnUrlInvoiceRequest request = parse(metadata);
        assertEquals(metadata, request.metadata());
        assertEquals(1, request.service().getMetadata().size());
        InvoiceValidator.validateLnurlInvoice(invoice(metadata), request.metadata(), 1_000, Bolt11NetworkType.MAINNET);
    }

    @Test
    public void rejectsMissingMetadataAndErrorResponses() {
        assertThrows(
            IllegalArgumentException.class,
            () -> LnUrlInvoiceRequest.fromResponse("{\"status\":\"ERROR\",\"reason\":\"Unavailable\"}")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> LnUrlInvoiceRequest.fromResponse("{\"tag\":\"payRequest\",\"metadata\":[]}")
        );
    }

    @Test
    public void rejectsOtherLnurlServicesAndMalformedMetadata() {
        assertThrows(RuntimeException.class, () -> parse("not JSON"));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                LnUrlInvoiceRequest.fromResponse(
                    NGEPlatform.get().toJSON(Map.of("tag", "withdrawRequest", "metadata", "[[\"text/plain\",\"Payment\"]]"))
                )
        );
    }

    private static String invoice(String metadata) throws Exception {
        String hash = NGEUtils.bytesToHex(
            MessageDigest.getInstance("SHA-256").digest(metadata.getBytes(StandardCharsets.UTF_8))
        );
        Bolt11Invoice invoice = new Bolt11Invoice();
        invoice.setNetwork(Bolt11NetworkType.MAINNET);
        invoice.setTimestamp(Instant.now());
        invoice.setMillisatoshis(BigInteger.valueOf(1_000));
        invoice.setTags(
            List.of(
                Bolt11Tag.of(Bolt11TagName.PAYMENT_HASH, "11".repeat(32)),
                Bolt11Tag.of(Bolt11TagName.PAYMENT_SECRET, "22".repeat(32)),
                Bolt11Tag.of(Bolt11TagName.DESCRIPTION_HASH, hash),
                Bolt11Tag.of(Bolt11TagName.EXPIRY, 3600)
            )
        );
        return Bolt11.sign(invoice, NostrPrivateKey.generate().asHex()).getPaymentRequest();
    }
}
