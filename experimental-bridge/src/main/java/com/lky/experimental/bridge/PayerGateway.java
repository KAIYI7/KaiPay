package com.lky.experimental.bridge;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.valves.ErrorReportValve;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.io.IOException;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Separate payer-only ingress. Never point a public tunnel at the receiver backend. */
public final class PayerGateway implements AutoCloseable {
    private static final String API = "/api/checkout/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:/qr)?";
    private static final Map<String, String> ASSETS = Map.of(
            "/checkout", "text/html; charset=utf-8", "/checkout.js", "text/javascript; charset=utf-8", "/demo.css", "text/css; charset=utf-8");
    private final Tomcat server;
    private final Connector connector;
    private final Path temporary;
    private final ThreadPoolExecutor workers;
    private final HttpClient client;
    private final URI backend;
    private final Map<String, byte[]> assets = new LinkedHashMap<>();
    private final Budget global;
    private final Map<String, Budget> capabilities = new LinkedHashMap<>(512, 0.75f, true);

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: PayerGateway <gateway-port> <backend-port>");
        PayerGateway gateway = new PayerGateway(Integer.parseInt(args[0]), Integer.parseInt(args[1]));
        Runtime.getRuntime().addShutdownHook(new Thread(gateway::close));
        gateway.start();
        System.out.println("Payer gateway ready on loopback; external HTTPS ingress requires separate owner authorization.");
    }

    PayerGateway(int port, int backendPort) throws IOException { this(port, backendPort, 60, 10); }

    // Injectable budget only for isolated test instances; runtime uses fixed bounds.
    PayerGateway(int port, int backendPort, int burst, int perSecond) throws IOException {
        if (port < 0 || port > 65535 || backendPort < 1 || backendPort > 65535 || port == backendPort)
            throw new IllegalArgumentException("Distinct valid loopback ports required");
        backend = URI.create("http://127.0.0.1:" + backendPort);
        global = new Budget(burst, perSecond);
        for (String path : ASSETS.keySet()) {
            String resource = "/demo/" + (path.equals("/checkout") ? "checkout.html" : path.substring(1));
            try (var stream = PayerGateway.class.getResourceAsStream(resource)) {
                if (stream == null) throw new IOException("Gateway resource unavailable");
                assets.put(path, stream.readNBytes(128 * 1024));
            }
        }
        workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
                new ThreadPoolExecutor.AbortPolicy());
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.of(null)).version(HttpClient.Version.HTTP_1_1).build();
        // Reuse the module's embedded Tomcat: bounded parsing on every supported JDK21.
        temporary = Files.createTempDirectory("kaipay-payer-gateway-");
        server = new Tomcat();
        server.setBaseDir(temporary.toString());
        server.setHostname("127.0.0.1");
        connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(port);
        connector.setProperty("address", "127.0.0.1");
        connector.setProperty("maxHttpRequestHeaderSize", "8192");
        connector.setProperty("maxHeaderCount", "32");
        connector.setProperty("connectionTimeout", "5000");
        connector.setProperty("keepAliveTimeout", "5000");
        connector.setProperty("connectionUploadTimeout", "5000");
        connector.setProperty("disableUploadTimeout", "false");
        connector.setProperty("maxConnections", "64");
        connector.setProperty("acceptCount", "16");
        connector.setProperty("maxKeepAliveRequests", "50");
        connector.setMaxPostSize(0);
        connector.setAllowTrace(false);
        ((org.apache.coyote.AbstractProtocol<?>) connector.getProtocolHandler()).setExecutor(workers);
        server.setConnector(connector);
        var context = server.addContext("", temporary.toString());
        context.setUseHttpOnly(true);
        context.setMapperContextRootRedirectEnabled(false);
        var errors = new ErrorReportValve() {
            @Override protected void report(org.apache.catalina.connector.Request request, org.apache.catalina.connector.Response response, Throwable failure) {
                if (response.getStatus() < 400 || response.isCommitted() || !response.setErrorReported()) return;
                headers(response);
                response.setContentType("application/json");
                try {
                    var writer = response.getReporter();
                    if (writer != null) writer.write("{\"error\":\"REQUEST_INVALID\"}");
                } catch (IOException ignored) { /* client disconnected */ }
            }
        };
        errors.setShowReport(false); errors.setShowServerInfo(false);
        ((org.apache.catalina.core.StandardHost) server.getHost()).setErrorReportValveClass(null);
        server.getHost().getPipeline().addValve(errors);
        // The API never logs request URLs/headers. Disable container exception diagnostics too.
        java.util.logging.Logger.getLogger("org.apache.coyote.http11.Http11Processor").setLevel(java.util.logging.Level.OFF);
        Tomcat.addServlet(context, "payer", new HttpServlet() {
            @Override protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                handle(request, response);
            }
        });
        context.addServletMappingDecoded("/*", "payer");
    }

    void start() throws Exception { server.start(); }
    int port() { return connector.getLocalPort(); }

    private void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        headers(response);
        if (!global.take()) { error(response, 429, "RATE_LIMITED"); return; }
        String path = request.getRequestURI();
        // Exact undecoded path; no redirects, path normalization or prefix routing here.
        // Tomcat also accepts valid HTTP/1.1 absolute-form targets with a matching Host.
        // That authority is never used as the upstream destination or authorization.
        if (request.getQueryString() != null || !(ASSETS.containsKey(path) || path.matches(API))) {
            error(response, 404, "NOT_FOUND"); return;
        }
        if (!request.getMethod().equals("GET")) { error(response, 405, "METHOD_INVALID"); return; }
        if (request.getHeader("Transfer-Encoding") != null || request.getHeader("Expect") != null
                || request.getContentLengthLong() > 0) {
            error(response, 400, "REQUEST_INVALID"); return;
        }
        if (!sameOrigin(request)) { error(response, 403, "ORIGIN_DENIED"); return; }
        if (ASSETS.containsKey(path)) { send(response, 200, ASSETS.get(path), assets.get(path)); return; }
        List<String> authorizations = Collections.list(request.getHeaders("Authorization"));
        if (authorizations.size() != 1 || !authorizations.getFirst().matches("Bearer [A-Za-z0-9_-]{43}")) {
            error(response, 401, "UNAUTHORIZED"); return;
        }
        String authorization = authorizations.getFirst();
        if (!capabilityBudget(authorization)) { error(response, 429, "RATE_LIMITED"); return; }
        proxy(response, path, authorization);
    }

    private static boolean sameOrigin(HttpServletRequest request) {
        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) return false;
        List<String> origins = Collections.list(request.getHeaders("Origin"));
        if (origins.isEmpty()) return true;
        if (origins.size() != 1 || Collections.list(request.getHeaders("Host")).size() != 1) return false;
        String origin = origins.getFirst(), host = request.getHeader("Host");
        return origin.equals("https://" + host)
                || (host.equals("127.0.0.1:" + request.getLocalPort()) && origin.equals("http://" + host));
    }
    private synchronized boolean capabilityBudget(String authorization) {
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(authorization.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(); }
        long now = System.nanoTime();
        capabilities.entrySet().removeIf(entry -> now - entry.getValue().lastUsed > TimeUnit.MINUTES.toNanos(1));
        Budget budget = capabilities.get(digest);
        if (budget == null) {
            // A stream of unauthenticated random keys must not reserve every slot and
            // deny all subsequently issued legitimate capabilities. Global limits still apply.
            if (capabilities.size() >= 512) capabilities.remove(capabilities.keySet().iterator().next());
            budget = new Budget(6, 0.5); capabilities.put(digest, budget);
        }
        return budget.take();
    }

    private void proxy(HttpServletResponse response, String path, String authorization) throws IOException {
        boolean qr = path.endsWith("/qr");
        var request = HttpRequest.newBuilder(backend.resolve(path)).timeout(Duration.ofSeconds(3))
                .header("Authorization", authorization).header("Accept", qr ? "image/png, application/json" : "application/json").GET().build();
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, info -> new LimitedBody(qr ? 2_000_000 : 4096));
        try {
            var result = pending.get(4, TimeUnit.SECONDS);
            if (result.headers().firstValue("Location").isPresent()) { error(response, 502, "UPSTREAM_UNAVAILABLE"); return; }
            if (result.statusCode() == 200) {
                String type = result.headers().firstValue("Content-Type").orElse("").split(";")[0].trim();
                if (!type.equals(qr ? "image/png" : "application/json")) { error(response, 502, "UPSTREAM_UNAVAILABLE"); return; }
                send(response, 200, type, result.body());
            } else {
                // Never forward upstream errors, cookies, redirects, diagnostics or cache headers.
                int status = switch (result.statusCode()) { case 401, 403 -> 401; case 404 -> 404; case 409 -> 409; case 429 -> 429; default -> 502; };
                error(response, status, switch (status) { case 401 -> "UNAUTHORIZED"; case 404 -> "NOT_FOUND"; case 409 -> "CHECKOUT_NOT_PAYABLE"; case 429 -> "RATE_LIMITED"; default -> "UPSTREAM_UNAVAILABLE"; });
            }
        } catch (Exception ignored) {
            pending.cancel(true);
            error(response, 503, "UPSTREAM_UNAVAILABLE");
        }
    }

    private static void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store"); response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff"); response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Strict-Transport-Security", "max-age=86400");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        response.setHeader("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src blob:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; object-src 'none'");
    }

    private static void error(HttpServletResponse response, int status, String code) throws IOException {
        if (status == 429) response.setHeader("Retry-After", "3");
        send(response, status, "application/json", ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.US_ASCII));
    }

    private static void send(HttpServletResponse response, int status, String type, byte[] body) throws IOException {
        response.setStatus(status); response.setContentType(type); response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    @Override public void close() {
        try { server.stop(); server.destroy(); } catch (Exception ignored) { /* no request diagnostics */ }
        workers.shutdownNow(); client.close();
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { /* Temporary container files contain no credentials. */ }
    }
    private static final class Budget {
        private final int capacity;
        private final double perSecond;
        private double tokens;
        private long lastUsed = System.nanoTime();
        Budget(int capacity, double perSecond) { this.capacity = capacity; this.perSecond = perSecond; this.tokens = capacity; }
        synchronized boolean take() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + Math.max(0, now - lastUsed) / 1_000_000_000.0 * perSecond);
            lastUsed = now;
            if (tokens < 1) return false;
            tokens--; return true;
        }
    }

    /** Enforce byte limit while receiving, before the body accumulator can grow without bound. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private int count;
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - count) { subscription.cancel(); delegate.onError(new IOException("Upstream limit")); return; }
                count += buffer.remaining();
            }
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { delegate.onError(error); }
        @Override public void onComplete() { delegate.onComplete(); }
    }
}
