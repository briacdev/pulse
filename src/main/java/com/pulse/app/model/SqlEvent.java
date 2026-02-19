package com.pulse.app.model;

public record SqlEvent(
        long timestamp,
        long durationMs,
        SqlType sqlType,
        String datasource,
        SqlStatus status,
        String exceptionType,
        String normalizedSql,
        String threadName,
        String endpoint,
        String handler,
        Integer httpStatus,
        String traceId
) {
}
