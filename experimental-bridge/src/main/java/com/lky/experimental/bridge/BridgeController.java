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
    private final byte[] page;
    // Null except in multi-checkout observed mode, which serves its own rewritten script; every other mode reads the file.
    private final byte[] multiScript;
    BridgeController(ObservationService service) { this(service, false, false); }
    @org.springframework.beans.factory.annotation.Autowired
    BridgeController(ObservationService service, BridgeAccess access) { this(service, access.observedMode(), access.multiCheckout()); }
    private BridgeController(ObservationService service, boolean observed, boolean multi) {
        this.service = service;
        byte[] html, script;
        try { html = resource("demo/index.html"); script = multi ? resource("demo/demo.js") : null; }
        catch (IOException ignored) { throw new IllegalStateException("Receiver page unavailable"); }
        page = observed ? (multi ? multiIndex(observedIndex(html)) : observedIndex(html)) : html;
        multiScript = multi ? CheckoutController.rewrite(script, MULTI_SCRIPT, "Multi-checkout receiver script is out of date") : null;
    }

    // Observed-evidence mode: factual receiver copy, and the create and developer panels hidden (the server refuses both).
    // data-bridge-mode selects demo.js's observed copy set. Every passage must exist, so drift fails startup.
    static final String[][] OBSERVED_INDEX = {
        {"<html lang=\"en\">", "<html lang=\"en\" data-bridge-mode=\"observed\">"},
        {"<p class=\"intro\">Create a checkout, review candidate evidence, and confirm only after your own verification.</p>",
            "<p class=\"intro\">Review the wallet notification your own phone observed for the one integration checkout. Confirm manually only after checking your own wallet history.</p>"},
        {"<p class=\"notice\">Android notifications are UNVERIFIED candidate evidence. Amount and time do not identify the payer or prove settlement. This isolated experiment never changes KaiPay payments or ledger entries.</p>",
            "<p class=\"notice\">Observed-evidence mode. A wallet notification observed on your own phone is UNVERIFIED: amount and time do not identify who sent money, or show that it arrived. This bridge does not take, move or verify money, and never changes KaiPay records.</p>"},
        {"<label>Approved remote payer origin (optional) ", "<label class=\"hidden\">Approved remote payer origin (optional) "},
        {"<p class=\"muted\">Use only the HTTPS origin of the payer gateway you control.", "<p class=\"muted\" hidden>Use only the HTTPS origin of the payer gateway you control."},
        {"<section class=\"panel\" aria-labelledby=\"create-title\">", "<section class=\"panel\" aria-labelledby=\"create-title\" hidden>"},
        {"<details class=\"panel\"><summary>Developer tools", "<details class=\"panel\" hidden><summary>Developer tools"},
        {"<footer>Genuine payments always require manual receiver confirmation after independently checking actual TNG history, amount and intended checkout. Notification matching never confirms a payment automatically. Synthetic evidence is invented. Expired, unmatched and ambiguous evidence never proves a payment.</footer>",
            "<footer>Observed-evidence mode serves one integration checkout at one fixed amount. A matched notification is never confirmed automatically: the receiver checks their own wallet history and confirms manually. Expired, unmatched and ambiguous notifications are never confirmed. Creating checkouts, simulation and manual resolve are disabled in this mode.</footer>"},
    };
    static byte[] observedIndex(byte[] html) { return CheckoutController.rewrite(html, OBSERVED_INDEX, "Observed receiver copy is out of date"); }

    // Multi-checkout observed mode only, applied after OBSERVED_INDEX: the single-checkout sentences would be false here.
    static final String[][] MULTI_INDEX = {
        {"<p class=\"intro\">Review the wallet notification your own phone observed for the one integration checkout. Confirm manually only after checking your own wallet history.</p>",
            "<p class=\"intro\">Review each wallet notification your own phone observed for an integration checkout. Confirm each one manually only after checking your own wallet history.</p>"},
        {"<footer>Observed-evidence mode serves one integration checkout at one fixed amount.",
            "<footer>Observed-evidence mode serves a configured number of single-use integration checkouts, and no two of them hold the same amount at once."},
    };
    static byte[] multiIndex(byte[] html) { return CheckoutController.rewrite(html, MULTI_INDEX, "Multi-checkout receiver copy is out of date"); }
    static final String[][] MULTI_SCRIPT = {
        {"'No checkout yet. The integration client creates the one checkout for this mode.'",
            "'No checkout yet. The integration client creates each checkout for this mode.'"},
    };

    // A successful sample proves only this receiver-authorized process responded now.
    // Do not inspect payment state or infer phone connectivity from backend availability.
    @GetMapping("/api/health") OperationalHealth health() {
        return new OperationalHealth(java.time.Instant.now().toString(), "RUNNING", "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN");
    }
    record OperationalHealth(String sampledAt, String backendProcess, String androidListener,
                             String androidQueue, String androidWorker, String notificationTransport) {}

    @GetMapping("/api/state") Snapshot state() { return service.view(); }
    @PostMapping("/api/requests") PaymentRequest create(@RequestBody CreateRequest input) { return service.create(input); }
    @PostMapping("/api/observations") Observation ingest(@RequestBody Candidate event) { return service.ingest(event); }
    @PostMapping("/api/notification-candidates") Observation notification(@RequestBody NotificationInput event) { return service.ingestNotification(event.candidate()); }
    @PostMapping("/api/notification-candidates/{id}/invalidate") Observation invalidate(@PathVariable UUID id, @RequestBody Invalidation input) { return service.invalidate(id, input); }
    @PostMapping("/api/observations/{id}/resolve") Observation resolve(@PathVariable UUID id, @RequestBody Resolution input) { return service.resolve(id, input); }
    @PostMapping("/api/requests/{id}/verify") PaymentRequest verify(@PathVariable UUID id, @RequestBody Confirmation input) {
        if (!Boolean.TRUE.equals(input.historyChecked())) throw new BridgeFailure(422, "HISTORY_CHECK_REQUIRED");
        return service.beginVerification(id, input);
    }
    @PostMapping("/api/requests/{id}/confirm") PaymentRequest confirm(@PathVariable UUID id, @RequestBody Confirmation input) { return service.confirm(id, input); }
    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE) byte[] demo() { return page.clone(); }
    @GetMapping(value = "/demo.js", produces = "text/javascript") byte[] script() throws IOException {
        return multiScript != null ? multiScript.clone() : resource("demo/demo.js");
    }
    @GetMapping(value = "/demo.css", produces = "text/css") byte[] styles() throws IOException { return resource("demo/demo.css"); }
    private static byte[] resource(String path) throws IOException {
        try (var stream = new ClassPathResource(path).getInputStream()) { return stream.readAllBytes(); }
    }
}
