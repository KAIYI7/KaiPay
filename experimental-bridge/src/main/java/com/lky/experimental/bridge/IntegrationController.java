package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Synthetic checkout creation and read-back for one external integration client. The filter admits only the
 * integration credential and canonical lowercase references here; this API never reviews, verifies, confirms,
 * resolves or issues payer access.
 */
@RestController @ConditionalOnProperty(name = "bridge.enabled", havingValue = "true")
class IntegrationController {
    private final ObservationService service;
    IntegrationController(ObservationService service) { this.service = service; }

    @PutMapping("/api/integrations/checkouts/{reference}")
    ResponseEntity<IntegrationCheckout> put(@PathVariable UUID reference, @RequestBody IntegrationCreate input) {
        if (input == null) throw new BridgeFailure(422, "AMOUNT_INVALID");
        ObservationService.amount(input.amountMinor());
        if (input.currency() == null || !input.currency().isTextual() || !"MYR".equals(input.currency().textValue()))
            throw new BridgeFailure(422, "CURRENCY_INVALID");
        ObservationService.IntegrationPut result = service.putIntegration(reference, input.amountMinor());
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.checkout());
    }

    @GetMapping("/api/integrations/checkouts/{reference}")
    IntegrationCheckout get(@PathVariable UUID reference) { return service.integrationCheckout(reference); }
}
