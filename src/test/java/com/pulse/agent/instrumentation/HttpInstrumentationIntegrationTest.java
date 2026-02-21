package com.pulse.agent.instrumentation;

import com.pulse.app.core.PulseConfig;
import com.pulse.app.core.PulseRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpInstrumentationIntegrationTest {

    @BeforeEach
    void setUp() {
        PulseRuntime.initialize(new PulseConfig(17321, 120_000, 1.0, 100, 50, "127.0.0.1", "test-app"));
    }

    @Test
    void webAdviceShouldCaptureHttpTransaction() {
        FakeRequest request = new FakeRequest("GET", "/demo", "/demo");
        FakeResponse response = new FakeResponse(200);

        WebTransactionAdvice.onEnter("org.apache.catalina.core.CoyoteAdapter.service", request);
        WebTransactionAdvice.onExit(request, response, null);

        var snapshot = PulseRuntime.getHttpCollector().snapshot();
        assertEquals(1, snapshot.totalEvents());
        assertEquals("GET /demo", snapshot.recent().getFirst().endpoint());
        assertEquals(200, snapshot.recent().getFirst().httpStatus());
        assertNotNull(snapshot.recent().getFirst().traceId());
        assertFalse(snapshot.recent().getFirst().traceId().isBlank());
    }

    @Test
    void dispatcherAdviceShouldCaptureErrorRequest() {
        FakeRequest request = new FakeRequest("POST", "/fail", "/fail");
        FakeResponse response = new FakeResponse(500);

        DispatcherServletAdvice.onEnter("org.springframework.web.servlet.DispatcherServlet.doDispatch", request);
        DispatcherServletAdvice.onExit(request, response, new IllegalStateException("boom"));

        var snapshot = PulseRuntime.getHttpCollector().snapshot();
        assertTrue(snapshot.totalEvents() >= 1);
        assertTrue(snapshot.recent().stream().anyMatch(event -> "POST /fail".equals(event.endpoint())
                && "IllegalStateException".equals(event.errorType())));
    }

    @Test
    void shouldCaptureRequestMetadataWhenAvailable() {
        FakeRequest request = new FakeRequest("POST", "/users", "/users")
                .withQuery("page=1")
                .withParam("page", "1")
                .withParam("filter", "active")
                .withHeader("X-Request-Id", "req-42")
                .withHeader("Authorization", "Bearer secret-token-123")
                .withAuth("Bearer", "alice")
                .withBody("{\"name\":\"alice\"}", "application/json");
        FakeResponse response = new FakeResponse(201);

        DispatcherServletAdvice.onEnter("org.springframework.web.servlet.DispatcherServlet.doDispatch", request);
        DispatcherServletAdvice.onExit(request, response, null);

        var event = PulseRuntime.getHttpCollector().snapshot().recent().getFirst();
        assertEquals("page=1", event.queryString());
        assertEquals("1", event.parameters().get("page"));
        assertEquals("active", event.parameters().get("filter"));
        assertEquals("req-42", event.headers().get("X-Request-Id"));
        assertTrue(event.auth().contains("principal=alice"));
        assertTrue(event.auth().contains("authorization=Bearer"));
        assertEquals("{\"name\":\"alice\"}", event.requestBody());
    }

    @Test
    void shouldIgnoreInternalPulseRequests() {
        FakeRequest request = new FakeRequest("GET", "/api/http/snapshot", "/api/http/snapshot", 17321);
        FakeResponse response = new FakeResponse(200);

        DispatcherServletAdvice.onEnter("org.springframework.web.servlet.DispatcherServlet.doDispatch", request);
        DispatcherServletAdvice.onExit(request, response, null);

        var snapshot = PulseRuntime.getHttpCollector().snapshot();
        assertEquals(0, snapshot.totalEvents());
    }

    public static final class FakeRequest {
        private final String method;
        private final String path;
        private final String handler;
        private final int localPort;
        private String queryString;
        private String contentType;
        private byte[] contentBytes;
        private String authType;
        private Principal principal;
        private final Map<String, String[]> params = new LinkedHashMap<>();
        private final Map<String, List<String>> headers = new LinkedHashMap<>();

        FakeRequest(String method, String path, String handler) {
            this(method, path, handler, 8080);
        }

        FakeRequest(String method, String path, String handler, int localPort) {
            this.method = method;
            this.path = path;
            this.handler = handler;
            this.localPort = localPort;
        }

        public String getMethod() {
            return method;
        }

        public String getRequestURI() {
            return path;
        }

        public Object getAttribute(String name) {
            return "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern".equals(name) ? handler : null;
        }

        FakeRequest withQuery(String queryString) {
            this.queryString = queryString;
            return this;
        }

        FakeRequest withParam(String key, String value) {
            this.params.put(key, new String[]{value});
            return this;
        }

        FakeRequest withHeader(String key, String value) {
            this.headers.put(key, List.of(value));
            return this;
        }

        FakeRequest withAuth(String authType, String principalName) {
            this.authType = authType;
            this.principal = () -> principalName;
            return this;
        }

        FakeRequest withBody(String body, String contentType) {
            this.contentType = contentType;
            this.contentBytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        public String getQueryString() {
            return queryString;
        }

        public Map<String, String[]> getParameterMap() {
            return params;
        }

        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(headers.keySet());
        }

        public Enumeration<String> getHeaders(String name) {
            return Collections.enumeration(headers.getOrDefault(name, List.of()));
        }

        public String getHeader(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.getFirst();
        }

        public String getAuthType() {
            return authType;
        }

        public Principal getUserPrincipal() {
            return principal;
        }

        public String getContentType() {
            return contentType;
        }

        public byte[] getContentAsByteArray() {
            return contentBytes;
        }

        public int getLocalPort() {
            return localPort;
        }
    }

    public static final class FakeResponse {
        private final int status;

        FakeResponse(int status) {
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
