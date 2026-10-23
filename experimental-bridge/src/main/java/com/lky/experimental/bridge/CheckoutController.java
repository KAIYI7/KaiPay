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

    CheckoutController(ObservationService service, BridgeAccess access, ReceivingQr qr) {
        this.service = service; this.access = access; this.qr = qr;
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

    @GetMapping(value = "/checkout", produces = MediaType.TEXT_HTML_VALUE) byte[] page() throws IOException { return resource("demo/checkout.html"); }
    @GetMapping(value = "/checkout.js", produces = "text/javascript") byte[] script() throws IOException { return resource("demo/checkout.js"); }
    private byte[] resource(String path) throws IOException {
        try (var stream = new ClassPathResource(path).getInputStream()) { return stream.readAllBytes(); }
    }
}
