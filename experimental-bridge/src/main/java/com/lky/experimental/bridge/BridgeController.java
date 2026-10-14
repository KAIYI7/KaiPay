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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController @ConditionalOnProperty(name = "bridge.enabled", havingValue = "true")
class BridgeController {
    private final ObservationService service;
    BridgeController(ObservationService service) { this.service = service; }

    @GetMapping("/api/state") Snapshot state() { return service.view(); }
    @PostMapping("/api/requests") PaymentRequest create(@RequestBody CreateRequest input) { return service.create(input); }
    @PostMapping("/api/observations") Observation ingest(@RequestBody Candidate event) { return service.ingest(event); }
    @PostMapping("/api/notification-candidates") Observation notification(@RequestBody NotificationInput event) { return service.ingestNotification(event.candidate()); }
    @PostMapping("/api/notification-candidates/{id}/invalidate") Observation invalidate(@PathVariable UUID id, @RequestBody Invalidation input) { return service.invalidate(id, input); }
    @PostMapping("/api/observations/{id}/resolve") Observation resolve(@PathVariable UUID id, @RequestBody Resolution input) { return service.resolve(id, input); }
    @PostMapping("/api/requests/{id}/verify") PaymentRequest verify(@PathVariable UUID id, @RequestBody Confirmation input) {
        if (!Boolean.TRUE.equals(input.historyChecked())) throw new BridgeFailure(422, "HISTORY_CHECK_REQUIRED");
        return service.beginVerification(id);
    }
    @PostMapping("/api/requests/{id}/confirm") PaymentRequest confirm(@PathVariable UUID id, @RequestBody Confirmation input) { return service.confirm(id, input); }
    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE) byte[] demo() throws IOException { return resource("demo/index.html"); }
    @GetMapping(value = "/demo.js", produces = "text/javascript") byte[] script() throws IOException { return resource("demo/demo.js"); }
    @GetMapping(value = "/demo.css", produces = "text/css") byte[] styles() throws IOException { return resource("demo/demo.css"); }
    private byte[] resource(String path) throws IOException {
        try (var stream = new ClassPathResource(path).getInputStream()) { return stream.readAllBytes(); }
    }
}
