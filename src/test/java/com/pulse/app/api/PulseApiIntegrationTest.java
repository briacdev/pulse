package com.pulse.app.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.app.api.local.LocalApiServer;
import com.pulse.app.core.PulseConfig;
import com.pulse.app.core.PulseRuntime;
import com.pulse.app.model.HttpRequestContext;
import com.pulse.app.model.SqlType;
import com.pulse.app.model.http.HttpRequestEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PulseApiIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LocalApiServer localApiServer;
    private HttpClient httpClient;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        port = freePort();
        PulseConfig config = new PulseConfig(port, 120_000, 1.0, 100, 100, "127.0.0.1", "test-app");
        PulseRuntime.initialize(config);

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
                new HttpRequestEvent(
                        "http-1",
                        System.currentTimeMillis(),
                        40,
                        "GET /users/1",
                        "/users/{id}",
                        200,
                        "trace-api-1",
                        "main",
                        null,
                        false,
                        null,
                        "page=1",
                        Map.of("page", "1"),
                        Map.of("X-Request-Id", "req-1"),
                        "type=Bearer | authorization=Bearer abc...",
                        "{\"id\":1}"
                ),
                0,
                null,
                null
        );

        localApiServer = new LocalApiServer(config);
        localApiServer.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (localApiServer != null) {
            localApiServer.close();
        }
    }

    @Test
    void shouldExposeSqlSnapshotAndConfig() throws Exception {
        JsonNode sqlSnapshot = getJson("/api/sql/snapshot");
        assertEquals(1, sqlSnapshot.path("totalEvents").asInt());
        assertEquals("GET /users/1", sqlSnapshot.path("recent").get(0).path("endpoint").asText());
        assertEquals("/users/{id}", sqlSnapshot.path("recent").get(0).path("handler").asText());
        assertEquals(200, sqlSnapshot.path("recent").get(0).path("httpStatus").asInt());
        assertEquals("trace-api-1", sqlSnapshot.path("recent").get(0).path("traceId").asText());

        JsonNode sqlConfig = getJson("/api/sql/config");
        assertFalse(sqlConfig.path("appName").asText().isBlank());
        assertEquals(100, sqlConfig.path("slowHttpThresholdMs").asInt());
    }

    @Test
    void shouldExposeHttpAndJvmAndTraceSnapshots() throws Exception {
        JsonNode httpSnapshot = getJson("/api/http/snapshot");
        assertEquals(1, httpSnapshot.path("totalEvents").asInt());
        assertEquals("page=1", httpSnapshot.path("recent").get(0).path("queryString").asText());
        assertEquals("1", httpSnapshot.path("recent").get(0).path("parameters").path("page").asText());
        assertEquals("req-1", httpSnapshot.path("recent").get(0).path("headers").path("X-Request-Id").asText());
        assertEquals("type=Bearer | authorization=Bearer abc...", httpSnapshot.path("recent").get(0).path("auth").asText());
        assertEquals("{\"id\":1}", httpSnapshot.path("recent").get(0).path("requestBody").asText());

        JsonNode traceDetail = getJson("/api/http/trace/http-1");
        assertEquals("GET /users/1", traceDetail.path("event").path("endpoint").asText());
        assertTrue(traceDetail.path("sampledStackCount").asInt() >= 0);

        JsonNode jvmSnapshot = getJson("/api/jvm/snapshot");
        assertEquals(120_000, jvmSnapshot.path("retentionMs").asLong());
    }

    private JsonNode getJson(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
