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

import java.util.Map;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
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
                new JvmMetricsController()
        ).build();

        PulseRuntime.initialize(new PulseConfig(17321, 120_000, 1.0, 100, 100, "127.0.0.1", "test-app"));

        PulseRuntime.getCollector().record(
                "h2",
                "SELECT * FROM users WHERE id=?",
                SqlType.SELECT,
                25,
                (String) null,
                "main",
                new HttpRequestContext(null, null, null, "trace-api-1")
        );

        PulseRuntime.getHttpCollector().record(
                new HttpRequestEvent("http-1", System.currentTimeMillis(), 40, "GET /users/1", "/users/{id}", 200,
                        "trace-api-1", "main", null, false, null,
                        "page=1", Map.of("page", "1"), Map.of("X-Request-Id", "req-1"),
                        "type=Bearer | authorization=Bearer abc...",
                        "{\"id\":1}"),
                0,
                null,
                null
        );
    }

    @Test
    void shouldExposeSqlSnapshotAndConfig() throws Exception {
        mockMvc.perform(get("/api/sql/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(1))
                .andExpect(jsonPath("$.recent[0].endpoint").value("GET /users/1"))
                .andExpect(jsonPath("$.recent[0].handler").value("/users/{id}"))
                .andExpect(jsonPath("$.recent[0].httpStatus").value(200))
                .andExpect(jsonPath("$.recent[0].traceId").value("trace-api-1"));

        mockMvc.perform(get("/api/sql/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value(not(blankOrNullString())))
                .andExpect(jsonPath("$.slowHttpThresholdMs").value(100));
    }

    @Test
    void shouldExposeHttpAndJvmAndHealthSnapshots() throws Exception {
        mockMvc.perform(get("/api/http/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(1))
                .andExpect(jsonPath("$.recent[0].queryString").value("page=1"))
                .andExpect(jsonPath("$.recent[0].parameters.page").value("1"))
                .andExpect(jsonPath("$.recent[0].headers.X-Request-Id").value("req-1"))
                .andExpect(jsonPath("$.recent[0].auth").value("type=Bearer | authorization=Bearer abc..."))
                .andExpect(jsonPath("$.recent[0].requestBody").value("{\"id\":1}"));

        mockMvc.perform(get("/api/jvm/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retentionMs").value(120000));
    }
}
