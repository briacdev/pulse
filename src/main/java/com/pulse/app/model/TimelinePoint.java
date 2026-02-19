package com.pulse.app.model;

public record TimelinePoint(
        long timestamp,
        double avgLatencyMs,
        long count,
        long errorCount
) {
}
