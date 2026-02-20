package com.pulse.app.model.jvm;

public record JvmTimelinePoint(
        long timestamp,
        double heapUsagePct,
        double cpuUsagePct,
        long threadCount,
        long gcCountTotal,
        long gcPauseMsTotal,
        double requestAvgLatencyMs,
        double requestP95LatencyMs,
        double errorRatePct
) {
}
