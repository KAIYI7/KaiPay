package com.lky.experimental.bridge;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Collections;

final class BridgeFilter implements Filter {
    private static final int MAX_BODY = 4096;
    private static final String UUID_PATH = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    // Integration references are canonical lowercase UUIDs; any other spelling is an unlisted route.
    static final String INTEGRATION_PATH = "/api/integrations/checkouts/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String LIVE_INVALIDATE = "/api/notification-candidates/" + UUID_PATH + "/invalidate";
    /** Request attribute carrying the rejection code; set only from compiled constants or a BridgeFailure code. */
    static final String REJECTION = BridgeFilter.class.getName() + ".rejection";
    private final BridgeAccess access;
    private final BridgeLog log;
    BridgeFilter(BridgeAccess access) { this(access, new BridgeLog(System.out, java.time.Clock.systemUTC())); }
    BridgeFilter(BridgeAccess access, BridgeLog log) { this.access = access; this.log = log; }

    @Override public void doFilter(ServletRequest input, ServletResponse output, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) input;
        HttpServletResponse response = (HttpServletResponse) output;
        // Observed mode only: one allowlisted code per rejected POST on a live-device route, so that a transport probe can
        // tell an authenticated 422 from a 401. Decided from method and exact canonical path before any other check.
        String path = request.getRequestURI();
        boolean liveRoute = access.observedMode() && "POST".equals(request.getMethod()) && path != null
                && (path.equals("/api/notification-candidates") || path.matches(LIVE_INVALIDATE));
        try { filter(request, response, chain); }
        finally {
            if (liveRoute && response.getStatus() >= 400) log.liveRejected(request.getAttribute(REJECTION) instanceof String code ? code : null);
        }
    }

    private void filter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' blob:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
        if (!access.enabled) { error(request, response, 404, "EXPERIMENT_DISABLED"); return; }
        if (!request.isSecure() && (!loopback(request.getRemoteAddr()) || !loopback(request.getLocalAddr()))) {
            error(request, response, 403, "TLS_REQUIRED"); return;
        }
        String path = request.getRequestURI();
        boolean publicResource = path.equals("/") || path.equals("/demo.js") || path.equals("/demo.css")
                || path.equals("/checkout") || path.equals("/checkout.js");
        if (path.matches("/api/checkout/" + UUID_PATH + "(?:/qr)?")) {
            if (!"GET".equals(request.getMethod())) { error(request, response, 405, "METHOD_INVALID"); return; }
            if (request.getQueryString() != null
                    || Collections.list(request.getHeaders("Authorization")).size() != 1
                    || !access.payers.authorized(java.util.UUID.fromString(path.substring("/api/checkout/".length(), "/api/checkout/".length() + 36)), request.getHeader("Authorization"))) {
                error(request, response, 401, "UNAUTHORIZED"); return;
            }
        } else if (path.matches(INTEGRATION_PATH)) {
            // Explicit role mapping: only the integration credential, never the receiver default.
            if (!access.integrationEnabled()) { error(request, response, 404, "INTEGRATION_DISABLED"); return; }
            if (Collections.list(request.getHeaders("Authorization")).size() != 1
                    || !access.authorized(request.getHeader("Authorization"), "integration")) {
                error(request, response, 401, "UNAUTHORIZED"); return;
            }
            if (request.getQueryString() != null) { error(request, response, 404, "NOT_FOUND"); return; }
            if (!"GET".equals(request.getMethod()) && !"PUT".equals(request.getMethod())) { error(request, response, 405, "METHOD_INVALID"); return; }
        } else if (!publicResource) {
            boolean canonical = path.equals("/api/health") || path.equals("/api/state") || path.equals("/api/requests") || path.equals("/api/observations") || path.equals("/api/notification-candidates")
                    || path.matches("/api/notification-candidates/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/invalidate")
                    || path.matches("/api/observations/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/resolve")
                    || path.matches("/api/requests/" + UUID_PATH + "/(?:verify|confirm|payer-access|payer-revoke)");
            // Reject encoded/matrix paths before MVC normalizes them: roles use the same canonical route.
            if (!canonical) { error(request, response, 404, "NOT_FOUND"); return; }
            if (path.startsWith("/api/notification-candidates") && access.deviceId == null) { error(request, response, 404, "LIVE_DELIVERY_DISABLED"); return; }
            String role = request.getMethod().equals("POST") && path.equals("/api/observations") ? "simulation"
                    : request.getMethod().equals("POST") && path.startsWith("/api/notification-candidates") ? "live" : "receiver";
            if (Collections.list(request.getHeaders("Authorization")).size() != 1
                    || !access.authorized(request.getHeader("Authorization"), role)) {
                error(request, response, 401, "UNAUTHORIZED"); return;
            }
            if (path.equals("/api/health")) {
                if (request.getQueryString() != null) { error(request, response, 404, "NOT_FOUND"); return; }
                if (!"GET".equals(request.getMethod())) { error(request, response, 405, "METHOD_INVALID"); return; }
            }
        } else if (!"GET".equals(request.getMethod())) { error(request, response, 405, "METHOD_INVALID"); return; }
        if ("POST".equals(request.getMethod()) || "PUT".equals(request.getMethod())) {
            if (request.getContentType() == null || !request.getContentType().matches("(?i)application/json(?:;\\s*charset=utf-8)?")) {
                error(request, response, 415, "JSON_REQUIRED"); return;
            }
            if (request.getContentLengthLong() > MAX_BODY) { error(request, response, 413, "BODY_TOO_LARGE"); return; }
            byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) { error(request, response, 413, "BODY_TOO_LARGE"); return; }
            request = new CachedRequest(request, body);
        }
        chain.doFilter(request, response);
    }

    private static boolean loopback(String address) {
        try { return InetAddress.getByName(address).isLoopbackAddress(); } catch (Exception ignored) { return false; }
    }

    static void error(HttpServletRequest request, HttpServletResponse response, int status, String code) throws IOException {
        request.setAttribute(REJECTION, code);
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }

    private static final class CachedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        CachedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream bytes = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return bytes.read(); }
                @Override public boolean isFinished() { return bytes.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
    }
}
