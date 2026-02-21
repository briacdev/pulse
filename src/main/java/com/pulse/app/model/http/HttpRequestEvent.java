package com.pulse.app.model.http;

import java.util.Map;

public record HttpRequestEvent(
        String id,
        long timestamp,
        long durationMs,
        String endpoint,
        String handler,
        Integer httpStatus,
        String traceId,
        String threadName,
        String errorType,
        boolean slow,
        String hottestFrame,
        String queryString,
        Map<String, String> parameters,
        Map<String, String> headers,
        String auth,
        String requestBody
) {
}
