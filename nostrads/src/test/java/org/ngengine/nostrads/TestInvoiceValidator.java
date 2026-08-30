/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * conditions in the project LICENSE file are met.
 */
package org.ngengine.nostrads;

import static org.junit.Assert.assertThrows;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import org.junit.Test;
import org.ngengine.bolt11.Bolt11;
import org.ngengine.bolt11.Bolt11Invoice;
import org.ngengine.bolt11.Bolt11NetworkType;
import org.ngengine.bolt11.Bolt11Tag;
import org.ngengine.bolt11.Bolt11TagName;
import org.ngengine.lnurl.LnUrlPay;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostrads.client.negotiation.InvoiceValidator;
import org.ngengine.platform.NGEUtils;

public class TestInvoiceValidator {

    private static final class TestPayService extends LnUrlPay {

        TestPayService() {
            super(
                10_000_000,
                1,
                URI.create("https://example.com/lnurl"),
                64,
                List.of(new Metadata("text/plain", "NostrAds test payout")),
                null
            );
        }
    }

    @Test
    public void validatesExactLnurlInvoice() throws Exception {
        TestPayService payService = new TestPayService();
        String invoice = invoice(payService, 21_000, Bolt11NetworkType.MAINNET, Instant.now(), 3600, null);
        InvoiceValidator.validateLnurlInvoice(invoice, payService, 21_000, Bolt11NetworkType.MAINNET);
    }

    @Test
    public void rejectsWrongAmountNetworkHashAndExpiry() throws Exception {
        TestPayService payService = new TestPayService();
        String valid = invoice(payService, 21_000, Bolt11NetworkType.MAINNET, Instant.now(), 3600, null);
        assertThrows(
            IllegalArgumentException.class,
            () -> InvoiceValidator.validateLnurlInvoice(valid, payService, 22_000, Bolt11NetworkType.MAINNET)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> InvoiceValidator.validateLnurlInvoice(valid, payService, 21_000, Bolt11NetworkType.TESTNET)
        );

        String wrongHash = invoice(payService, 21_000, Bolt11NetworkType.MAINNET, Instant.now(), 3600, "00".repeat(32));
        assertThrows(
            IllegalArgumentException.class,
            () -> InvoiceValidator.validateLnurlInvoice(wrongHash, payService, 21_000, Bolt11NetworkType.MAINNET)
        );

        String expired = invoice(payService, 21_000, Bolt11NetworkType.MAINNET, Instant.now().minusSeconds(120), 1, null);
        assertThrows(
            IllegalArgumentException.class,
            () -> InvoiceValidator.validateLnurlInvoice(expired, payService, 21_000, Bolt11NetworkType.MAINNET)
        );
    }

    @Test
    public void rejectsAmountlessInvoice() throws Exception {
        TestPayService payService = new TestPayService();
        String invoice = invoice(payService, null, Bolt11NetworkType.MAINNET, Instant.now(), 3600, null);
        assertThrows(
            IllegalArgumentException.class,
            () -> InvoiceValidator.validateLnurlInvoice(invoice, payService, 21_000, Bolt11NetworkType.MAINNET)
        );
    }

    @Test
    public void verifiesNwcPreimageAgainstInvoiceHash() throws Exception {
        TestPayService payService = new TestPayService();
        byte[] preimage = new byte[32];
        for (int i = 0; i < preimage.length; i++) preimage[i] = (byte) i;
        String paymentHash = NGEUtils.bytesToHex(MessageDigest.getInstance("SHA-256").digest(preimage));
        String invoice = invoice(payService, 21_000, Bolt11NetworkType.MAINNET, Instant.now(), 3600, null, paymentHash);
        String preimageHex = NGEUtils.bytesToHex(preimage);

        InvoiceValidator.validatePaymentPreimage(invoice, preimageHex, Bolt11NetworkType.MAINNET);
        assertThrows(
            SecurityException.class,
            () -> InvoiceValidator.validatePaymentPreimage(invoice, "ff".repeat(32), Bolt11NetworkType.MAINNET)
        );
    }

    private static String invoice(
        LnUrlPay payService,
        Integer amountMsats,
        Bolt11NetworkType network,
        Instant timestamp,
        long expirySeconds,
        String descriptionHashOverride
    ) throws Exception {
        return invoice(payService, amountMsats, network, timestamp, expirySeconds, descriptionHashOverride, "11".repeat(32));
    }

    private static String invoice(
        LnUrlPay payService,
        Integer amountMsats,
        Bolt11NetworkType network,
        Instant timestamp,
        long expirySeconds,
        String descriptionHashOverride,
        String paymentHash
    ) throws Exception {
        String metadata = (String) payService.toMap().get("metadata");
        String descriptionHash = descriptionHashOverride != null
            ? descriptionHashOverride
            : NGEUtils.bytesToHex(MessageDigest.getInstance("SHA-256").digest(metadata.getBytes(StandardCharsets.UTF_8)));

        Bolt11Invoice invoice = new Bolt11Invoice();
        invoice.setNetwork(network);
        invoice.setTimestamp(timestamp);
        if (amountMsats != null) invoice.setMillisatoshis(BigInteger.valueOf(amountMsats));
        invoice.setTags(
            List.of(
                Bolt11Tag.of(Bolt11TagName.PAYMENT_HASH, paymentHash),
                Bolt11Tag.of(Bolt11TagName.PAYMENT_SECRET, "22".repeat(32)),
                Bolt11Tag.of(Bolt11TagName.DESCRIPTION_HASH, descriptionHash),
                Bolt11Tag.of(Bolt11TagName.EXPIRY, expirySeconds)
            )
        );
        return Bolt11.sign(invoice, NostrPrivateKey.generate().asHex()).getPaymentRequest();
    }
}
