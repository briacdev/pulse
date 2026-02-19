package com.pulse.app.model.http;

import java.util.List;

public record HttpSnapshot(
        long generatedAt,
        long totalEvents,
        List<HttpRequestEvent> recent,
        List<HttpEndpointAggregate> endpoints,
        List<HttpRequestEvent> slowCalls
) {
}
