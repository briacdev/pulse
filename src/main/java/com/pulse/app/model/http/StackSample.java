package com.pulse.app.model.http;

public record StackSample(
        String stack,
        int samples,
        double percent
) {
}
