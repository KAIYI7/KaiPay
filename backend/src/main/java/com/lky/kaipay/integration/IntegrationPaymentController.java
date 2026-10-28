package com.lky.kaipay.integration;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/v1/integrations/payments")
public class IntegrationPaymentController {
    private static final ObjectMapper STRICT = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final IntegrationPaymentService service;
    public IntegrationPaymentController(IntegrationPaymentService service) { this.service = service; }

    @PutMapping("/{reference}")
    public ResponseEntity<?> put(@PathVariable String reference, Authentication authentication, HttpServletRequest request) {
        return safely(() -> {
            UUID id = reference(reference);
            if (request.getContentType() == null || !request.getContentType().matches("(?i)application/json(?:;\\s*charset=utf-8)?")
                    || request.getContentLengthLong() > 1024) throw new IntegrationFailure(400, "INVALID_REQUEST");
            long amount;
            try {
                byte[] body = request.getInputStream().readNBytes(1025);
                if (body.length > 1024) throw new IntegrationFailure(400, "INVALID_REQUEST");
                var node = STRICT.readTree(body);
                if (node == null || !node.isObject() || node.size() != 2 || !node.has("amountCents") || !node.has("currency")
                        || !node.get("amountCents").isIntegralNumber() || !node.get("amountCents").canConvertToLong()
                        || !node.get("currency").isTextual() || !"MYR".equals(node.get("currency").textValue()))
                    throw new IntegrationFailure(400, "INVALID_REQUEST");
                amount = node.get("amountCents").longValue();
                if (amount < 1 || amount > 1_000_000_000L) throw new IntegrationFailure(400, "INVALID_REQUEST");
            } catch (IOException failure) { throw new IntegrationFailure(400, "INVALID_REQUEST"); }
            return service.put((IntegrationProperties.Binding) authentication.getPrincipal(), id, amount);
        });
    }
    @GetMapping("/{reference}")
    public ResponseEntity<?> get(@PathVariable String reference, Authentication authentication, HttpServletRequest request) {
        return safely(() -> {
            if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null)
                throw new IntegrationFailure(400, "INVALID_REQUEST");
            return service.get((IntegrationProperties.Binding) authentication.getPrincipal(), reference(reference));
        });
    }
    private static UUID reference(String value) {
        if (!value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IntegrationFailure(400, "INVALID_REQUEST");
        return UUID.fromString(value);
    }
    private ResponseEntity<?> safely(java.util.function.Supplier<IntegrationPaymentService.Projection> operation) {
        try { return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.success(operation.get())); }
        catch (IntegrationFailure failure) { return error(failure.status, failure.code); }
        catch (RuntimeException failure) { return error(503, "TEMPORARILY_UNAVAILABLE"); }
    }
    private ResponseEntity<?> error(int status, String code) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(java.util.Map.of("success", false, "status", status, "code", code,
                        "message", IntegrationErrors.message(code)));
    }
}
