package com.pulse.app.model.http;

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
        String hottestFrame
) {
}
