package com.pulse.app.model.http;

import java.util.List;

public record HttpTraceDetail(
        HttpRequestEvent event,
        int totalSamples,
        List<StackFrameStat> hotspots,
        List<StackSample> sampledStacks
) {
}
