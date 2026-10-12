package com.lky.kaipay.common.observability;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterUnitTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void usesHeaderForRequestAndResponseAndRestoresContextOnException() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put("traceId", "previous");
        MDC.put("user", "retained");
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("traceId")).isEqualTo("request-123");
            assertThat(MDC.get("user")).isEqualTo("retained");
            throw new ServletException("controller failed");
        })).isInstanceOf(ServletException.class);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("request-123");
        assertThat(MDC.get("traceId")).isEqualTo("previous");
        assertThat(MDC.get("user")).isEqualTo("retained");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"bad value", "line\nvalue", "é", "one,two"})
    void missingOrInvalidHeaderGeneratesIdAndDoesNotLeakBetweenRequests(String header) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (header != null) request.addHeader("X-Correlation-Id", header);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> assertThat(MDC.get("traceId"))
                .isEqualTo(response.getHeader("X-Correlation-Id")));
        String first = response.getHeader("X-Correlation-Id");
        assertThat(UUID.fromString(first)).isNotNull();
        assertThat(MDC.get("traceId")).isNull();
        MockHttpServletResponse next = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), next, (req, res) -> { });
        assertThat(next.getHeader("X-Correlation-Id")).isNotEqualTo(first);
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void repeatedOrOversizedHeaderGeneratesOneSafeId() throws Exception {
        for (boolean repeated : new boolean[] {true, false}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Correlation-Id", repeated ? "first" : "a".repeat(65));
            if (repeated) request.addHeader("X-Correlation-Id", "second");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> { });
            assertThat(UUID.fromString(response.getHeader("X-Correlation-Id"))).isNotNull();
            assertThat(response.getHeaders("X-Correlation-Id")).hasSize(1);
        }
    }

    @Test
    void retainsEffectiveIdAcrossAsyncAndErrorRedispatch() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request, first, (req, res) -> { });
        String effective = first.getHeader("X-Correlation-Id");
        for (DispatcherType dispatch : new DispatcherType[] {DispatcherType.ASYNC, DispatcherType.ERROR}) {
            request.setDispatcherType(dispatch);
            request.setAttribute("jakarta.servlet.error.request_uri", "/failure");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> assertThat(MDC.get("traceId")).isEqualTo(effective));
            assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(effective);
            assertThat(MDC.get("traceId")).isNull();
        }
    }

    @Test
    void nestedErrorDispatchRetainsIdAndRestoresOuterScope() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            String effective = MDC.get("traceId");
            request.setDispatcherType(DispatcherType.ERROR);
            request.setAttribute("jakarta.servlet.error.request_uri", "/failure");
            filter.doFilter(request, new MockHttpServletResponse(), (nestedReq, nestedRes) ->
                    assertThat(MDC.get("traceId")).isEqualTo(effective));
            assertThat(MDC.get("traceId")).isEqualTo(effective);
        });
        assertThat(MDC.get("traceId")).isNull();
    }
}
