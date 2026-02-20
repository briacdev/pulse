package com.pulse.app.core;

import com.pulse.app.model.http.HttpRequestEvent;
import com.pulse.app.model.jvm.JvmSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmMetricsServiceTest {

    @Test
    void snapshotShouldExposeJvmAndHttpDerivedMetrics() {
        PulseConfig config = new PulseConfig(17321, 120_000, 1.0, 100, 200, "127.0.0.1", "test-app");
        HttpPerfCollectorService httpCollector = new HttpPerfCollectorService(config);
        JvmMetricsService service = new JvmMetricsService(config);

        long now = System.currentTimeMillis();
        httpCollector.record(new HttpRequestEvent("h1", now - 500, 120, "GET /demo", "/demo", 200, "t1", "main", null, false, null),
                0, null, null);
        httpCollector.record(new HttpRequestEvent("h2", now - 250, 220, "GET /demo", "/demo", 500, "t2", "main", "RuntimeException", true, null),
                0, null, null);

        JvmSnapshot snapshot = service.snapshot(httpCollector);

        assertTrue(snapshot.heapUsedMb() >= 0);
        assertTrue(snapshot.cpuUsagePct() >= 0);
        assertTrue(snapshot.requestAvgLatencyMs() > 0);
        assertTrue(snapshot.errorRatePct() >= 0);
        assertFalse(snapshot.timeline().isEmpty());
    }
}
