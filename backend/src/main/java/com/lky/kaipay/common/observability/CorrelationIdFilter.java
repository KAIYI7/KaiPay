package com.lky.kaipay.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Enumeration;
import java.util.UUID;

public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".correlationId";

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object retained = request.getAttribute(REQUEST_ATTRIBUTE);
        String effective = retained instanceof String value && CorrelationContext.isValid(value) ? value : resolve(request);
        request.setAttribute(REQUEST_ATTRIBUTE, effective);
        response.setHeader(CorrelationContext.HEADER_NAME, effective);
        try (var ignored = CorrelationContext.open(effective)) {
            chain.doFilter(request, response);
        }
    }

    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        doFilterInternal(request, response, chain);
    }

    private String resolve(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(CorrelationContext.HEADER_NAME);
        String value = values != null && values.hasMoreElements() ? values.nextElement() : null;
        boolean repeated = values != null && values.hasMoreElements();
        return !repeated && CorrelationContext.isValid(value) ? value : UUID.randomUUID().toString();
    }
}
