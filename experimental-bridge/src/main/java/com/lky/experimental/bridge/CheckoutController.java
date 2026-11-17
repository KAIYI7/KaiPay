package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController @ConditionalOnProperty(name = "bridge.enabled", havingValue = "true")
class CheckoutController {
    private final ObservationService service;
    private final BridgeAccess access;
    private final ReceivingQr qr;
    private final byte[] page;

    CheckoutController(ObservationService service, BridgeAccess access, ReceivingQr qr) {
        this.service = service; this.access = access; this.qr = qr;
        byte[] html;
        try { html = resource("demo/checkout.html"); }
        catch (IOException ignored) { throw new IllegalStateException("Checkout page unavailable"); }
        page = access.integrationEnabled() ? syntheticCheckout(html) : html;
    }

    // Integration mode is synthetic-only: the payer page must not describe genuine wallets, QR codes or history checks.
    // Every replaced passage must exist, so copy drift fails startup instead of silently serving genuine wording.
    static final String[][] SYNTHETIC_COPY = {
        {"data-checkout-entry=\"private\"", "data-checkout-entry=\"private\" data-checkout-mode=\"synthetic\""},
        {"<p class=\"intro\">A read-only view of one experimental payment request.</p>",
            "<p class=\"intro\">A read-only view of one synthetic demo checkout.</p>"},
        {"<p class=\"notice\">Private owner-controlled experiment. A notification is unverified evidence. Payment is confirmed only after the receiver independently checks TNG history.</p>",
            "<p class=\"notice\">Synthetic integration demo. This page cannot take a payment, and no wallet, bank or provider system is involved. Evidence is invented; confirmation is a manual organizer record and no money moves.</p>"},
        {"<strong id=\"qr-placeholder-title\">Payment QR unavailable</strong><span id=\"qr-placeholder-detail\">No payment code is displayed. Do not send a payment using this placeholder.</span>",
            "<strong id=\"qr-placeholder-title\">Synthetic demonstration — no payment needed</strong><span id=\"qr-placeholder-detail\">No payment code exists in synthetic mode. This placeholder cannot receive money.</span>"},
        {"<img id=\"receiving-qr\" alt=\"Receiver-supplied TNG receiving QR\"><figcaption>Receiver-supplied personal TNG receiving QR</figcaption>",
            "<img id=\"receiving-qr\" alt=\"No payment code in synthetic mode\"><figcaption>No payment code in synthetic mode</figcaption>"},
        {"<h2>Payment instructions</h2><div id=\"payment-instructions\"><p>Wait for a configured receiving QR before attempting a payment. This placeholder cannot receive money.</p></div><p>The receiving QR does not necessarily include this amount or KaiPay request reference. A matching amount or notification cannot identify the intended checkout or prove settlement. Keep the reference available for the receiver.</p>",
            "<h2>Demo instructions</h2><div id=\"payment-instructions\"><p>No payment is needed or possible for this synthetic demo. This placeholder cannot receive money.</p></div><p>Matching invented evidence by amount cannot identify a payer or prove settlement. Keep the reference available for the organizer.</p>"},
    };
    static byte[] syntheticCheckout(byte[] html) {
        String text = new String(html, java.nio.charset.StandardCharsets.UTF_8);
        for (String[] passage : SYNTHETIC_COPY) {
            if (!text.contains(passage[0])) throw new IllegalStateException("Synthetic checkout copy is out of date");
            text = text.replace(passage[0], passage[1]);
        }
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @PostMapping("/api/requests/{id}/payer-access") PayerLink issue(@PathVariable UUID id) {
        service.checkout(id); // Never issue access to nonexistent requests; receiver authentication is enforced by the filter.
        return new PayerLink(id, access.payers.issue(id));
    }

    @PostMapping("/api/requests/{id}/payer-revoke") @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID id) {
        service.checkout(id);
        access.payers.revoke(id);
    }

    @GetMapping("/api/checkout/{id}") Checkout checkout(@PathVariable UUID id) { return service.checkout(id); }

    @GetMapping(value = "/api/checkout/{id}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    byte[] qr(@PathVariable UUID id) {
        Checkout checkout = service.checkout(id);
        if (checkout.status() != Status.AWAITING_PAYMENT || checkout.paymentWindowClosed() || checkout.evidence() != PayerEvidence.NONE)
            throw new BridgeFailure(409, "CHECKOUT_NOT_PAYABLE");
        return qr.bytes();
    }

    @GetMapping(value = "/checkout", produces = MediaType.TEXT_HTML_VALUE) byte[] page() { return page.clone(); }
    @GetMapping(value = "/checkout.js", produces = "text/javascript") byte[] script() throws IOException { return resource("demo/checkout.js"); }
    private static byte[] resource(String path) throws IOException {
        try (var stream = new ClassPathResource(path).getInputStream()) { return stream.readAllBytes(); }
    }
}
