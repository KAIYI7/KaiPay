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
    private final BridgeAccess access;
    BridgeFilter(BridgeAccess access) { this.access = access; }

    @Override public void doFilter(ServletRequest input, ServletResponse output, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) input;
        HttpServletResponse response = (HttpServletResponse) output;
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
        if (!access.enabled) { error(response, 404, "EXPERIMENT_DISABLED"); return; }
        if (!request.isSecure() && (!loopback(request.getRemoteAddr()) || !loopback(request.getLocalAddr()))) {
            error(response, 403, "TLS_REQUIRED"); return;
        }
        String path = request.getRequestURI();
        boolean publicResource = path.equals("/") || path.equals("/demo.js") || path.equals("/demo.css");
        if (!publicResource) {
            boolean canonical = path.equals("/api/state") || path.equals("/api/requests") || path.equals("/api/observations")
                    || path.matches("/api/requests/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(?:verify|confirm)");
            // Reject encoded/matrix paths before MVC normalizes them: roles use the same canonical route.
            if (!canonical) { error(response, 404, "NOT_FOUND"); return; }
            boolean deviceRole = path.equals("/api/observations") && request.getMethod().equals("POST");
            if (Collections.list(request.getHeaders("Authorization")).size() != 1
                    || !access.authorized(request.getHeader("Authorization"), deviceRole)) {
                error(response, 401, "UNAUTHORIZED"); return;
            }
        } else if (!"GET".equals(request.getMethod())) { error(response, 405, "METHOD_INVALID"); return; }
        if ("POST".equals(request.getMethod())) {
            if (request.getContentType() == null || !request.getContentType().matches("(?i)application/json(?:;\\s*charset=utf-8)?")) {
                error(response, 415, "JSON_REQUIRED"); return;
            }
            if (request.getContentLengthLong() > MAX_BODY) { error(response, 413, "BODY_TOO_LARGE"); return; }
            byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) { error(response, 413, "BODY_TOO_LARGE"); return; }
            request = new CachedRequest(request, body);
        }
        chain.doFilter(request, response);
    }

    private static boolean loopback(String address) {
        try { return InetAddress.getByName(address).isLoopbackAddress(); } catch (Exception ignored) { return false; }
    }

    static void error(HttpServletResponse response, int status, String code) throws IOException {
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
