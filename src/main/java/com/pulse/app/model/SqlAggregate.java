package com.pulse.app.model;

public record SqlAggregate(
        String normalizedSql,
        SqlType sqlType,
        long count,
        long errorCount,
        double avgLatencyMs,
        long maxLatencyMs
) {
}
