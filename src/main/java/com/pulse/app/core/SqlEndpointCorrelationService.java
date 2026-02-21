package com.pulse.app.core;

import com.pulse.app.model.SqlEvent;
import com.pulse.app.model.SqlSnapshot;
import com.pulse.app.model.http.HttpRequestEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SqlEndpointCorrelationService {

    private static final long THREAD_MATCH_WINDOW_MS = 5_000L;

    private SqlEndpointCorrelationService() {
    }

    public static SqlSnapshot correlate(SqlSnapshot snapshot, List<HttpRequestEvent> httpEvents) {
        if (snapshot == null || snapshot.recent() == null || snapshot.recent().isEmpty()) {
            return snapshot;
        }
        if (httpEvents == null || httpEvents.isEmpty()) {
            return snapshot;
        }

        Map<String, HttpRequestEvent> byTraceId = new HashMap<>();
        Map<String, List<HttpRequestEvent>> byThread = new HashMap<>();

        for (HttpRequestEvent event : httpEvents) {
            if (event == null) {
                continue;
            }
            if (hasText(event.traceId())) {
                byTraceId.put(event.traceId(), event);
            }

            String thread = normalize(event.threadName());
            if (!thread.isEmpty()) {
                byThread.computeIfAbsent(thread, ignored -> new ArrayList<>()).add(event);
            }
        }

        List<SqlEvent> correlatedRecent = snapshot.recent().stream()
                .map(event -> correlateEvent(event, byTraceId, byThread))
                .toList();

        return new SqlSnapshot(
                snapshot.generatedAt(),
                snapshot.totalEvents(),
                correlatedRecent,
                snapshot.slowest(),
                snapshot.mostFrequent(),
                snapshot.timeline()
        );
    }

    private static SqlEvent correlateEvent(SqlEvent sqlEvent,
                                           Map<String, HttpRequestEvent> byTraceId,
                                           Map<String, List<HttpRequestEvent>> byThread) {
        if (sqlEvent == null) {
            return null;
        }

        HttpRequestEvent httpEvent = findByTraceId(sqlEvent, byTraceId);
        if (httpEvent == null) {
            httpEvent = findByThreadAndTime(sqlEvent, byThread);
        }
        if (httpEvent == null) {
            return sqlEvent;
        }

        String endpoint = firstNonBlank(sqlEvent.endpoint(), httpEvent.endpoint());
        String handler = firstNonBlank(sqlEvent.handler(), httpEvent.handler());
        Integer status = sqlEvent.httpStatus() != null ? sqlEvent.httpStatus() : httpEvent.httpStatus();
        String traceId = firstNonBlank(sqlEvent.traceId(), httpEvent.traceId(), httpEvent.id());

        if (Objects.equals(endpoint, sqlEvent.endpoint())
                && Objects.equals(handler, sqlEvent.handler())
                && Objects.equals(status, sqlEvent.httpStatus())
                && Objects.equals(traceId, sqlEvent.traceId())) {
            return sqlEvent;
        }

        return new SqlEvent(
                sqlEvent.timestamp(),
                sqlEvent.durationMs(),
                sqlEvent.sqlType(),
                sqlEvent.datasource(),
                sqlEvent.status(),
                sqlEvent.exceptionType(),
                sqlEvent.normalizedSql(),
                sqlEvent.threadName(),
                endpoint,
                handler,
                status,
                traceId
        );
    }

    private static HttpRequestEvent findByTraceId(SqlEvent sqlEvent, Map<String, HttpRequestEvent> byTraceId) {
        if (!hasText(sqlEvent.traceId())) {
            return null;
        }
        return byTraceId.get(sqlEvent.traceId());
    }

    private static HttpRequestEvent findByThreadAndTime(SqlEvent sqlEvent,
                                                         Map<String, List<HttpRequestEvent>> byThread) {
        String thread = normalize(sqlEvent.threadName());
        if (thread.isEmpty()) {
            return null;
        }

        List<HttpRequestEvent> candidates = byThread.get(thread);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        HttpRequestEvent best = null;
        long bestDistance = Long.MAX_VALUE;
        for (HttpRequestEvent candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            long distance = Math.abs(candidate.timestamp() - sqlEvent.timestamp());
            if (distance > THREAD_MATCH_WINDOW_MS || distance >= bestDistance) {
                continue;
            }
            best = candidate;
            bestDistance = distance;
        }
        return best;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalize(String value) {
        return hasText(value) ? value.trim() : "";
    }
}
