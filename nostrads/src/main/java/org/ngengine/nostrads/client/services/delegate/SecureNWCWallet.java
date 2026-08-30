/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * conditions in the project LICENSE file are met.
 */
package org.ngengine.nostrads.client.services.delegate;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.ngengine.bolt11.Bolt11NetworkType;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.client.negotiation.InvoiceValidator;
import org.ngengine.nostrads.security.VerifiedNostrPool;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.wallets.PayResponse;
import org.ngengine.wallets.nip47.NWCException;
import org.ngengine.wallets.nip47.NWCUri;
import org.ngengine.wallets.nip47.NWCWallet;

/**
 * NIP-47 wallet path that authenticates replies through a verified pool and enforces max_fee.
 */
final class SecureNWCWallet extends NWCWallet {

    private final NostrPool ownedPool;

    SecureNWCWallet(NWCUri uri) {
        this(validatedPool(uri), uri);
    }

    private SecureNWCWallet(NostrPool pool, NWCUri uri) {
        super(pool, uri);
        this.ownedPool = pool;
    }

    private static NostrPool validatedPool(NWCUri uri) {
        List<String> relays = uri.getRelays();
        if (relays.isEmpty() || relays.size() > 5) {
            throw new IllegalArgumentException("NWC must specify between one and five relays");
        }
        for (String relay : relays) validatePublicRelay(relay);
        return new VerifiedNostrPool();
    }

    private static void validatePublicRelay(String relay) {
        try {
            URI uri = URI.create(relay);
            if (!"wss".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("NWC relays must be public wss URLs without credentials");
            }
            String host = uri.getHost();
            if ("localhost".equalsIgnoreCase(host) || host.endsWith(".localhost") || host.endsWith(".local")) {
                throw new IllegalArgumentException("Local NWC relay host is not allowed");
            }
            for (InetAddress address : InetAddress.getAllByName(host)) {
                byte[] raw = address.getAddress();
                boolean uniqueLocalV6 = raw.length == 16 && (raw[0] & 0xfe) == 0xfc;
                if (
                    address.isAnyLocalAddress() ||
                    address.isLoopbackAddress() ||
                    address.isLinkLocalAddress() ||
                    address.isSiteLocalAddress() ||
                    address.isMulticastAddress() ||
                    uniqueLocalV6
                ) {
                    throw new IllegalArgumentException("NWC relay resolves to a non-public address");
                }
            }
        } catch (IllegalArgumentException securityError) {
            throw securityError;
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid or unresolvable NWC relay", error);
        }
    }

    @Override
    public void close() {
        super.close();
        ownedPool.close();
    }

    AsyncTask<PayResponse> payInvoice(
        @Nonnull String invoice,
        long amountMsats,
        long maxRoutingFeeMsats,
        @Nullable Instant expiresAt,
        @Nonnull Bolt11NetworkType invoiceNetwork
    ) {
        if (amountMsats <= 0 || maxRoutingFeeMsats < 0) {
            return AsyncTask.failed(new IllegalArgumentException("Invalid invoice amount or routing-fee limit"));
        }
        Instant requestExpiry = expiresAt == null ? Instant.now().plusSeconds(60) : expiresAt;
        if (!requestExpiry.isAfter(Instant.now())) {
            return AsyncTask.failed(new IllegalArgumentException("NWC request expiry must be in the future"));
        }

        Map<String, Object> params = new HashMap<>();
        params.put("invoice", Objects.requireNonNull(invoice));
        params.put("amount", amountMsats);
        params.put("max_fee", maxRoutingFeeMsats);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("method", "pay_invoice");
        requestBody.put("params", params);
        String requestJson = NGEPlatform.get().toJSON(requestBody);
        NostrKeyPairSigner signer = uri.getSigner();

        return signer
            .getPublicKey()
            .compose(clientPubkey -> {
                UnsignedNostrEvent request = new UnsignedNostrEvent()
                    .withKind(REQUEST_KIND)
                    .withContent(requestJson)
                    .withExpiration(requestExpiry)
                    .withTag("p", uri.getPubkey().asHex());
                return signer
                    .encrypt(requestJson, uri.getPubkey(), NostrSigner.EncryptAlgo.NIP04)
                    .compose(encrypted -> signer.sign(request.withContent(encrypted)))
                    .compose(signedRequest -> {
                        pool.publish(signedRequest);
                        Duration timeout = Duration.between(Instant.now(), requestExpiry);
                        return pool
                            .fetch(
                                new NostrFilter()
                                    .withKind(RESPONSE_KIND)
                                    .withAuthor(uri.getPubkey())
                                    .withTag("e", signedRequest.getId())
                                    .withTag("p", clientPubkey.asHex())
                                    .limit(1),
                                1,
                                timeout
                            )
                            .then(responses -> {
                                if (responses.isEmpty()) throw new IllegalStateException("NWC payment response timed out");
                                return responses.get(0);
                            });
                    });
            })
            .compose(response -> signer.decrypt(response.getContent(), uri.getPubkey(), NostrSigner.EncryptAlgo.NIP04))
            .then(decrypted -> {
                Map<String, Object> response = NGEPlatform.get().fromJSON(decrypted, Map.class);
                if (!"pay_invoice".equals(response.get("result_type"))) {
                    throw new IllegalStateException("Unexpected NWC payment response type");
                }
                Map<String, Object> error = (Map<String, Object>) response.get("error");
                if (error != null) {
                    throw new NWCException(NGEUtils.safeString(error.get("code")), NGEUtils.safeString(error.get("message")));
                }
                Map<String, Object> result = (Map<String, Object>) Objects.requireNonNull(response.get("result"));
                String preimage = NGEUtils.safeString(Objects.requireNonNull(result.get("preimage")));
                InvoiceValidator.validatePaymentPreimage(invoice, preimage, invoiceNetwork);
                Long feesPaid = result.get("fees_paid") == null ? null : NGEUtils.safeMSats(result.get("fees_paid"));
                if (feesPaid != null && (feesPaid < 0 || feesPaid > maxRoutingFeeMsats)) {
                    throw new SecurityException("NWC wallet exceeded the authorized max_fee");
                }
                return new PayResponse(preimage, feesPaid, null);
            });
    }
}
