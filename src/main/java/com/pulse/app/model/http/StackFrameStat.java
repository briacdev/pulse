package com.pulse.app.model.http;

public record StackFrameStat(
        String frame,
        int samples,
        double percent
) {
}
