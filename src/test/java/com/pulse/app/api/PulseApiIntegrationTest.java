package com.pulse.app.api;

import com.pulse.app.core.PulseConfig;
import com.pulse.app.core.PulseRuntime;
import com.pulse.app.model.HttpRequestContext;
import com.pulse.app.model.SqlType;
import com.pulse.app.model.http.HttpRequestEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PulseApiIntegrationTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new SqlMetricsController(),
                new HttpMetricsController(),
                new JvmMetricsController(),
                new HealthController()
        ).build();

        PulseRuntime.initialize(new PulseConfig(17321, 120_000, 1.0, 100, 100, "127.0.0.1", "test-app"));

        PulseRuntime.getCollector().record(
                "h2",
                "SELECT * FROM users WHERE id=?",
                SqlType.SELECT,
                25,
                (String) null,
                "main",
                new HttpRequestContext("GET /users/1", "/users/{id}", 200, "trace-api-1")
        );

        PulseRuntime.getHttpCollector().record(
                new HttpRequestEvent("http-1", System.currentTimeMillis(), 40, "GET /users/1", "/users/{id}", 200,
                        "trace-api-1", "main", null, false, null),
                0,
                null,
                null
        );
    }

    @Test
    void shouldExposeSqlSnapshotAndConfig() throws Exception {
        mockMvc.perform(get("/api/sql/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(1));

        mockMvc.perform(get("/api/sql/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value("test-app"))
                .andExpect(jsonPath("$.slowHttpThresholdMs").value(100));
    }

    @Test
    void shouldExposeHttpAndJvmAndHealthSnapshots() throws Exception {
        mockMvc.perform(get("/api/http/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(1));

        mockMvc.perform(get("/api/jvm/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retentionMs").value(120000));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
