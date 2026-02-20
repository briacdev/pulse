package com.pulse.agent.instrumentation;

import com.pulse.app.core.PulseConfig;
import com.pulse.app.core.PulseRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
