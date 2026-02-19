package com.pulse.app.model;

public record HttpRequestContext(
        String endpoint,
        String handler,
        Integer httpStatus,
        String traceId
) {
}
