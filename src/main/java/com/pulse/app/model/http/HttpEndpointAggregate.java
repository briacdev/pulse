package com.pulse.app.model.http;

public record HttpEndpointAggregate(
        String endpoint,
        long count,
        long errorCount,
        long slowCount,
        double avgLatencyMs,
        long p95LatencyMs,
        long maxLatencyMs
) {
}
