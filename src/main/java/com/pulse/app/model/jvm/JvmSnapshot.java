package com.pulse.app.model.jvm;

import java.util.List;

public record JvmSnapshot(
        long generatedAt,
        long retentionMs,
        long heapUsedMb,
        long heapCommittedMb,
        long heapMaxMb,
        double heapUsagePct,
        long gcCountTotal,
        long gcPauseMsTotal,
        long gcCountDelta,
        long gcPauseDeltaMs,
        int threadCount,
        int peakThreadCount,
        double cpuUsagePct,
        long processCpuTimeMs,
        double requestAvgLatencyMs,
        double requestP95LatencyMs,
        double errorRatePct,
        double requestThroughputPerSec,
        List<JvmTimelinePoint> timeline
) {
}
