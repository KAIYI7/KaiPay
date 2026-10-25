package com.lky.experimental.bridge;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PayerGatewayTest {
    private static final String TOKEN = "T".repeat(43);
    private static final String PATH = "/api/checkout/00000000-0000-4000-8000-000000000001";
    private HttpServer upstream;
    private PayerGateway gateway;
    private HttpClient client;
    private final AtomicInteger received = new AtomicInteger();
    private final AtomicReference<Map<String, List<String>>> forwarded = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>("{\"status\":\"AWAITING_PAYMENT\"}");
    private final AtomicReference<String> type = new AtomicReference<>("application/json");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<CountDownLatch> block = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        upstream.createContext("/", exchange -> {
            received.incrementAndGet(); forwarded.set(Map.copyOf(exchange.getRequestHeaders()));
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", type.get());
                exchange.getResponseHeaders().set("Set-Cookie", "private=diagnostic");
                if (status.get() == 302) exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/api/state");
                byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(), bytes.length);
                if (block.get() != null) block.get().await(8, TimeUnit.SECONDS);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        upstream.start();
        gateway = new PayerGateway(0, upstream.getAddress().getPort(), 1000, 1000); gateway.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build();
    }
    @AfterEach void cleanup() {
        if (block.get() != null) block.get().countDown();
        gateway.close(); upstream.stop(0); client.close();
    }
    private HttpResponse<String> send(String path, String method, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gateway.port() + path)).timeout(Duration.ofSeconds(8))
                .method(method, HttpRequest.BodyPublishers.noBody());
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String raw(String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", gateway.port())) {
            socket.setSoTimeout(8000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void exactAssetsAndCanonicalReadOnlyApiOnly() throws Exception {
        for (String asset : new String[]{"/checkout", "/checkout.js", "/demo.css"}) {
            var response = send(asset, "GET"); assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(response.headers().firstValue("Referrer-Policy")).contains("no-referrer");
            assertThat(response.headers().firstValue("Content-Security-Policy").orElse("")).contains("default-src 'none'", "frame-ancestors 'none'");
            assertThat(response.headers().firstValue("Strict-Transport-Security")).isPresent();
            assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        }
        assertThat(received).hasValue(0);
        assertThat(send(PATH, "GET").statusCode()).isEqualTo(401);
        assertThat(send(PATH, "GET", "Authorization", "Bearer " + TOKEN).statusCode()).isEqualTo(200);
        type.set("image/png");
        assertThat(send(PATH + "/qr", "GET", "Authorization", "Bearer " + TOKEN).statusCode()).isEqualTo(200);
        assertThat(received).hasValue(2);
    }

    @Test void gatewayCheckoutIsAlwaysCodeOnlyAndTemplateAmbiguityFailsClosed() throws Exception {
        var response = send("/checkout", "GET");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("data-checkout-entry=\"code\"", "checkout-access-form", "checkout-code")
                .doesNotContain("data-checkout-entry=\"private\"");
        assertThat(received).hasValue(0);
        assertThatIOException().isThrownBy(() -> PayerGateway.codeOnlyCheckout("<html></html>".getBytes(StandardCharsets.UTF_8)));
        assertThatIOException().isThrownBy(() -> PayerGateway.codeOnlyCheckout(
                "<html data-checkout-entry=\"private\"><div data-checkout-entry=\"private\"></div></html>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void crossSiteTopLevelEntryDoesNotGrantCrossSiteResourceAccess() throws Exception {
        String[] navigation = {"Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "navigate", "Sec-Fetch-Dest", "document"};
        var entry = send("/checkout", "GET", navigation);
        assertThat(entry.statusCode()).isEqualTo(200);
        assertThat(entry.body()).contains("checkout-reference");
        assertThat(entry.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(entry.headers().firstValue("Content-Security-Policy").orElse("")).contains("frame-ancestors 'none'", "connect-src 'self'");
        assertThat(entry.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(entry.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(received).hasValue(0);

        for (String resource : new String[]{"/checkout.js", "/demo.css", PATH, PATH + "/qr"}) {
            assertThat(send(resource, "GET", "Authorization", "Bearer " + TOKEN,
                    "Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "navigate", "Sec-Fetch-Dest", "document").statusCode())
                    .as(resource).isEqualTo(403);
        }
        for (String[] headers : new String[][]{
                {"Sec-Fetch-Site", "cross-site"},
                {"Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "navigate"},
                {"Sec-Fetch-Site", "cross-site", "Sec-Fetch-Dest", "document"},
                {"Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "navigate", "Sec-Fetch-Dest", "iframe"},
                {"Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "cors", "Sec-Fetch-Dest", "document"},
                {"Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "navigate", "Sec-Fetch-Dest", "document", "Origin", "https://other.example"},
                {"Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "navigate", "Sec-Fetch-Dest", "document", "Origin", "https://other.example", "Origin", "https://other.example"},
                {"Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "navigate", "Sec-Fetch-Mode", "cors", "Sec-Fetch-Dest", "document"},
                {"Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "navigate", "Sec-Fetch-Dest", "document", "Sec-Fetch-Dest", "iframe"}}) {
            assertThat(send("/checkout", "GET", headers).statusCode()).as(java.util.Arrays.toString(headers)).isEqualTo(403);
        }
        assertThat(send("/api/state", "GET", navigation).statusCode()).isEqualTo(404);
        assertThat(send("/checkout?x=1", "GET", navigation).statusCode()).isEqualTo(404);
        assertThat(send("/checkout", "POST", navigation).statusCode()).isEqualTo(405);
        assertThat(received).hasValue(0);
    }

    @Test void everyPrivateRouteAndPathAmbiguityIsDeniedBeforeUpstreamEvenWithBearer() throws Exception {
        String id = UUID.randomUUID().toString();
        for (String path : new String[]{"/", "/demo.js", "/index.html", "/favicon.ico", "/health", "/actuator/health", "/error", "/receiving.png",
                "/api/state", "/api/requests", "/api/observations", "/api/notification-candidates",
                "/api/notification-candidates/" + id + "/invalidate", "/api/observations/" + id + "/resolve",
                "/api/requests/" + id + "/verify", "/api/requests/" + id + "/confirm", "/api/requests/" + id + "/payer-access", "/api/requests/" + id + "/payer-revoke",
                "/checkout/", "/checkout.js/", "/checkout;ignored", "/Checkout", PATH + "/extra", PATH + "/QR", PATH + "/", PATH + ";x=1", PATH + "?key=" + TOKEN,
                "/%63heckout", "/checkout%2f..%2fapi/state", "/checkout%252f..%252fapi/state", "/checkout/../api/state", "/api//state", "//api/state", "/checkout/./", "/api/checkout/../state"}) {
            var response = send(path, "GET", "Authorization", "Bearer " + TOKEN);
            assertThat(response.statusCode()).as(path).isBetween(400, 499);
            assertThat(response.headers().firstValue("Location")).isEmpty();
        }
        assertThat(received).hasValue(0);
        for (String method : new String[]{"HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "PROPFIND"}) {
            assertThat(send(PATH, method, "Authorization", "Bearer " + TOKEN).statusCode()).as(method).isBetween(400, 599);
        }
        assertThat(received).hasValue(0);
        assertThat(raw("CONNECT " + PATH + " HTTP/1.1\r\nHost: local\r\nConnection: close\r\n\r\n")).startsWith("HTTP/1.1 501");
    }

    @Test void forwardingHeadersCannotChooseTargetRoleOrRedirectAndCorsIsClosed() throws Exception {
        var response = send(PATH, "GET", "Authorization", "Bearer " + TOKEN, "Forwarded", "for=evil;host=evil;proto=https",
                "X-Forwarded-Host", "evil.example", "X-Forwarded-Proto", "https", "X-Original-URL", "/api/state", "X-Rewrite-URL", "/api/state",
                "Cookie", "receiver=secret", "Proxy-Authorization", "Bearer private");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(forwarded.get().keySet()).noneMatch(key -> key.equalsIgnoreCase("Forwarded") || key.toLowerCase().startsWith("x-") || key.equalsIgnoreCase("Cookie") || key.equalsIgnoreCase("Proxy-Authorization"));
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(send(PATH, "GET", "Authorization", "Bearer " + TOKEN, "Origin", "https://other.example").statusCode()).isEqualTo(403);
        assertThat(send(PATH, "GET", "Authorization", "Bearer " + TOKEN, "Sec-Fetch-Site", "cross-site").statusCode()).isEqualTo(403);
        assertThat(send(PATH, "OPTIONS", "Origin", "https://other.example").headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(received).hasValue(1);
        status.set(302);
        assertThat(send(PATH, "GET", "Authorization", "Bearer " + TOKEN).statusCode()).isEqualTo(502);
        assertThat(received).hasValue(2); // No redirect was followed.
        status.set(500); body.set("private diagnostic " + TOKEN);
        response = send(PATH, "GET", "Authorization", "Bearer " + TOKEN);
        assertThat(response.statusCode()).isEqualTo(502); assertThat(response.body()).doesNotContain(TOKEN, "diagnostic");
    }

    @Test void rawEncodedTraversalDuplicateCredentialsAndOversizedHeadersNeverReachBackend() throws Exception {
        for (String path : new String[]{PATH + "?", "/checkout%00", "/checkout%5c..%5capi/state", "/checkout\\..\\api/state", "/%2e%2e/api/state", "/checkout#ignored", "/api/checkout/%30" + PATH.substring("/api/checkout/0".length())}) {
            String response = raw("GET " + path + " HTTP/1.1\r\nHost: local\r\nConnection: close\r\nAuthorization: Bearer " + TOKEN + "\r\n\r\n");
            assertThat(response).startsWith("HTTP/1.1 4");
        }
        String duplicate = raw("GET " + PATH + " HTTP/1.1\r\nHost: local\r\nConnection: close\r\nAuthorization: Bearer " + TOKEN + "\r\nAuthorization: Bearer " + TOKEN + "\r\n\r\n");
        assertThat(duplicate).startsWith("HTTP/1.1 401");
        String huge = raw("GET /checkout HTTP/1.1\r\nHost: local\r\nConnection: close\r\nSecret-Marker: " + "x".repeat(9000) + "\r\n\r\n");
        assertThat(huge).startsWith("HTTP/1.1 400").doesNotContain("Secret-Marker", "x".repeat(100));
        for (String malformed : new String[]{huge,
                raw("GET /checkout HTTP/1.1\r\nHost: local\r\nConnection: close\r\nTransfer-Encoding: unsupported\r\n\r\n"),
                raw("GET /checkout%zz HTTP/1.1\r\nHost: local\r\nConnection: close\r\n\r\n")}) {
            assertThat(malformed.toLowerCase()).contains("content-type: application/json", "cache-control: no-store", "referrer-policy: no-referrer")
                    .doesNotContain("apache", "exception", "<html", "stacktrace");
        }
        String bodyGet = raw("GET " + PATH + " HTTP/1.1\r\nHost: local\r\nConnection: close\r\nContent-Length: 1\r\nAuthorization: Bearer " + TOKEN + "\r\n\r\nx");
        assertThat(bodyGet).startsWith("HTTP/1.1 400");
        assertThat(received).hasValue(0);
    }

    @Test void absoluteFormHasSamePathBoundaryAndCannotSelectAnUpstreamHost() throws Exception {
        assertThat(raw("GET http://local/checkout HTTP/1.1\r\nHost: local\r\nConnection: close\r\n\r\n")).startsWith("HTTP/1.1 200");
        assertThat(raw("GET http://local/api/state HTTP/1.1\r\nHost: local\r\nConnection: close\r\nAuthorization: Bearer " + TOKEN + "\r\n\r\n")).startsWith("HTTP/1.1 404");
        assertThat(raw("GET http://evil.example/checkout HTTP/1.1\r\nHost: local\r\nConnection: close\r\n\r\n")).startsWith("HTTP/1.1 400");
        assertThat(raw("GET http://local/checkout/../api/state HTTP/1.1\r\nHost: local\r\nConnection: close\r\n\r\n")).startsWith("HTTP/1.1 404");
        assertThat(received).hasValue(0);
    }

    @Test void upstreamTypeBodyAndTotalTimeAreBounded() throws Exception {
        type.set("text/html"); assertThat(send(PATH, "GET", "Authorization", "Bearer " + TOKEN).statusCode()).isEqualTo(502);
        type.set("application/json"); body.set("x".repeat(4097));
        assertThat(send(PATH, "GET", "Authorization", "Bearer " + TOKEN).statusCode()).isEqualTo(503);
        type.set("image/png"); body.set("x".repeat(2_000_001));
        assertThat(send(PATH + "/qr", "GET", "Authorization", "Bearer " + TOKEN).statusCode()).isEqualTo(503);
        type.set("application/json"); body.set("{}"); block.set(new CountDownLatch(1));
        long start = System.nanoTime();
        assertThat(send(PATH, "GET", "Authorization", "Bearer " + TOKEN).statusCode()).isEqualTo(503);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6));
        block.get().countDown();
    }

    @Test void globalAndCapabilityPollingBudgetsReturn429() throws Exception {
        for (int i = 0; i < 6; i++) assertThat(send(PATH, "GET", "Authorization", "Bearer " + TOKEN).statusCode()).isEqualTo(200);
        var response = send(PATH, "GET", "Authorization", "Bearer " + TOKEN);
        assertThat(response.statusCode()).isEqualTo(429); assertThat(response.headers().firstValue("Retry-After")).contains("3");
        assertThat(received).hasValue(6);
        gateway.close(); gateway = new PayerGateway(0, upstream.getAddress().getPort(), 1, 0); gateway.start();
        assertThat(send("/checkout", "GET").statusCode()).isEqualTo(200);
        assertThat(send("/checkout", "GET").statusCode()).isEqualTo(429);
    }

    @Test void anonymousCapabilityChurnCannotReserveAllBudgetSlots() throws Exception {
        status.set(401);
        for (int i = 0; i < 513; i++) {
            String invalid = String.format("%043d", i);
            assertThat(send(PATH, "GET", "Authorization", "Bearer " + invalid).statusCode()).isEqualTo(401);
        }
        status.set(200);
        assertThat(send(PATH, "GET", "Authorization", "Bearer " + TOKEN).statusCode()).isEqualTo(200);
    }
}
