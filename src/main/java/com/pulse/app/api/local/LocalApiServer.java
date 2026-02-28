package com.pulse.app.api.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.app.core.PulseConfig;
import com.pulse.app.core.PulseRuntime;
import com.pulse.app.core.SqlEndpointCorrelationService;
import com.pulse.app.model.SqlSnapshot;
import com.pulse.app.model.http.HttpTraceDetail;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalApiServer implements AutoCloseable {

    private static final String API_HTTP_TRACE_PREFIX = "/api/http/trace/";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService executor;
    private final PulseConfig config;

    public LocalApiServer(PulseConfig config) throws IOException {
        this.config = config;
        this.server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName(config.bindAddress()), config.port()),
                0
        );
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "pulse-local-api");
            thread.setDaemon(true);
            return thread;
        });
        this.server.setExecutor(executor);
        this.server.createContext("/", this::handle);
    }

    private static void writeJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void writeText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void writeStatus(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private static boolean isSafePath(String path) {
        return path != null
                && !path.isBlank()
                && path.startsWith("/")
                && !path.contains("..");
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        if (path.startsWith("/")) {
            return path;
        }
        return "/" + path;
    }

    private static String contentType(String resourcePath) {
        if (resourcePath.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (resourcePath.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (resourcePath.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (resourcePath.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (resourcePath.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (resourcePath.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (resourcePath.endsWith(".woff")) {
            return "font/woff";
        }
        if (resourcePath.endsWith(".ttf")) {
            return "font/ttf";
        }
        return "application/octet-stream";
    }

    public void start() {
        server.start();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            URI uri = exchange.getRequestURI();
            String path = normalizePath(uri == null ? null : uri.getPath());

            if (!"GET".equals(method)) {
                writeStatus(exchange, 405);
                return;
            }

            if ("/api/sql/snapshot".equals(path)) {
                SqlSnapshot snapshot = PulseRuntime.getCollector().snapshot();
                SqlSnapshot correlated = SqlEndpointCorrelationService.correlate(
                        snapshot,
                        PulseRuntime.getHttpCollector().allEvents()
                );
                writeJson(exchange, 200, correlated);
                return;
            }

            if ("/api/sql/config".equals(path)) {
                PulseRuntime.refreshMonitoredAppName();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("port", config.port());
                payload.put("retentionMs", config.retentionMs());
                payload.put("sampleRate", config.sampleRate());
                payload.put("slowQueryThresholdMs", config.slowQueryThresholdMs());
                payload.put("slowHttpThresholdMs", config.slowHttpThresholdMs());
                payload.put("bindAddress", config.bindAddress());
                payload.put("appName", PulseRuntime.monitoredAppName());
                writeJson(exchange, 200, payload);
                return;
            }

            if ("/api/http/snapshot".equals(path)) {
                writeJson(exchange, 200, PulseRuntime.getHttpCollector().snapshot());
                return;
            }

            if (path.startsWith(API_HTTP_TRACE_PREFIX) && path.length() > API_HTTP_TRACE_PREFIX.length()) {
                String rawId = path.substring(API_HTTP_TRACE_PREFIX.length());
                String id = URLDecoder.decode(rawId, StandardCharsets.UTF_8);
                HttpTraceDetail detail = PulseRuntime.getHttpCollector().traceDetail(id);
                if (detail == null) {
                    writeStatus(exchange, 404);
                    return;
                }
                writeJson(exchange, 200, detail);
                return;
            }

            if ("/api/jvm/snapshot".equals(path)) {
                writeJson(exchange, 200, PulseRuntime.getJvmMetrics().snapshot(PulseRuntime.getHttpCollector()));
                return;
            }

            if ("/".equals(path)) {
                serveStatic(exchange, "/index.html");
                return;
            }

            serveStatic(exchange, path);
        } catch (Throwable error) {
            writeText(exchange, 500, "internal error");
        } finally {
            exchange.close();
        }
    }

    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        String sanitized = normalizePath(path);
        if (!isSafePath(sanitized)) {
            writeStatus(exchange, 404);
            return;
        }

        String resourcePath = "/static" + sanitized;
        try (InputStream input = LocalApiServer.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                writeStatus(exchange, 404);
                return;
            }
            byte[] body = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType(resourcePath));
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }
}
