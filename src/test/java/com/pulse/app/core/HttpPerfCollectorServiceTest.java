package com.pulse.app.core;

import com.pulse.app.model.http.HttpRequestEvent;
import com.pulse.app.model.http.HttpSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HttpPerfCollectorServiceTest {

    @Test
    void snapshotShouldAggregateByEndpoint() {
        PulseConfig config = new PulseConfig(17321, 60_000, 1.0, 100, 200, "127.0.0.1", "test-app");
        HttpPerfCollectorService service = new HttpPerfCollectorService(config);

        long now = System.currentTimeMillis();
        service.record(new HttpRequestEvent("1", now - 30, 30, "GET /users", "/users", 200, "t1", "main", null, false, null,
                        null, null, null, null, null),
                0, null, null);
        service.record(new HttpRequestEvent("2", now - 20, 90, "GET /users", "/users", 500, "t2", "main", "RuntimeException", true, null,
                        null, null, null, null, null),
                0, null, null);
        service.record(new HttpRequestEvent("3", now - 10, 10, "GET /health", "/health", 200, "t3", "main", null, false, null,
                        null, null, null, null, null),
                0, null, null);

        HttpSnapshot snapshot = service.snapshot();

        assertEquals(3, snapshot.totalEvents());
        assertFalse(snapshot.recent().isEmpty());
        assertFalse(snapshot.endpoints().isEmpty());
        assertNotNull(service.traceDetail("2"));
    }
}
