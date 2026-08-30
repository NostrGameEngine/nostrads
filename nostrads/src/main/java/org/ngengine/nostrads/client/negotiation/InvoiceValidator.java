/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * conditions in the project LICENSE file are met.
 */
package org.ngengine.nostrads.client.negotiation;

import jakarta.annotation.Nonnull;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import org.ngengine.bolt11.Bolt11;
import org.ngengine.bolt11.Bolt11Invoice;
import org.ngengine.bolt11.Bolt11NetworkType;
import org.ngengine.bolt11.Bolt11Tag;
import org.ngengine.bolt11.Bolt11TagName;
import org.ngengine.lnurl.LnUrlPay;

/** Validates the security-sensitive fields of an LNURL-provided BOLT11 invoice before a wallet sees it. */
public final class InvoiceValidator {

    private static final long DEFAULT_BOLT11_EXPIRY_SECONDS = 3600;

    private InvoiceValidator() {}

    public static void validateLnurlInvoice(
        @Nonnull String paymentRequest,
        @Nonnull LnUrlPay payService,
        long expectedAmountMsats,
        @Nonnull Bolt11NetworkType expectedNetwork
    ) {
        try {
            validateLnurlInvoiceChecked(paymentRequest, payService, expectedAmountMsats, expectedNetwork);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid BOLT11 invoice", e);
        }
    }

    public static void validatePaymentPreimage(
        @Nonnull String paymentRequest,
        @Nonnull String preimageHex,
        @Nonnull Bolt11NetworkType expectedNetwork
    ) {
        try {
            if (!preimageHex.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("NWC returned an invalid payment preimage");
            }
            byte[] preimage = new byte[32];
            for (int i = 0; i < preimage.length; i++) {
                preimage[i] = (byte) Integer.parseInt(preimageHex.substring(i * 2, i * 2 + 2), 16);
            }
            Bolt11Tag paymentHash = Bolt11
                .decode(paymentRequest, expectedNetwork)
                .getTags()
                .stream()
                .filter(tag -> tag.tagName() == Bolt11TagName.PAYMENT_HASH)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("BOLT11 invoice is missing its payment hash"));
            byte[] actualHash = MessageDigest.getInstance("SHA-256").digest(preimage);
            if (!MessageDigest.isEqual(actualHash, paymentHash.getValueAsBytes())) {
                throw new SecurityException("NWC payment preimage does not match the invoice payment hash");
            }
        } catch (IllegalArgumentException | SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to verify NWC payment preimage", e);
        }
    }

    private static void validateLnurlInvoiceChecked(
        @Nonnull String paymentRequest,
        @Nonnull LnUrlPay payService,
        long expectedAmountMsats,
        @Nonnull Bolt11NetworkType expectedNetwork
    ) throws Exception {
        if (expectedAmountMsats <= 0) throw new IllegalArgumentException("Invoice amount must be positive");

        Bolt11Invoice invoice = Bolt11.decode(paymentRequest, expectedNetwork);
        if (!Boolean.TRUE.equals(invoice.getComplete())) {
            throw new IllegalArgumentException("Incomplete BOLT11 invoice");
        }
        BigInteger expected = BigInteger.valueOf(expectedAmountMsats);
        if (invoice.getMillisatoshis() == null || !expected.equals(invoice.getMillisatoshis())) {
            throw new IllegalArgumentException("BOLT11 invoice amount does not match the authorized payout");
        }

        Instant expiresAt = invoice.getTimeExpireDate();
        if (expiresAt == null) {
            expiresAt = invoice.getTimestamp().plusSeconds(DEFAULT_BOLT11_EXPIRY_SECONDS);
        }
        if (!expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("BOLT11 invoice has expired");
        }

        Bolt11Tag descriptionHash = invoice
            .getTags()
            .stream()
            .filter(tag -> tag.tagName() == Bolt11TagName.DESCRIPTION_HASH)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("LNURL invoice is missing its description hash"));
        String metadata = (String) payService.toMap().get("metadata");
        if (metadata == null) throw new IllegalArgumentException("LNURL service metadata is missing");
        byte[] expectedDescriptionHash = MessageDigest.getInstance("SHA-256").digest(metadata.getBytes(StandardCharsets.UTF_8));
        if (!Arrays.equals(expectedDescriptionHash, descriptionHash.getValueAsBytes())) {
            throw new IllegalArgumentException("LNURL invoice description hash does not match service metadata");
        }
    }
}
