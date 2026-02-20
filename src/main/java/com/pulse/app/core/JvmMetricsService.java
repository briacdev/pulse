package com.pulse.app.core;

import com.pulse.app.model.http.HttpRequestEvent;
import com.pulse.app.model.jvm.JvmSnapshot;
import com.pulse.app.model.jvm.JvmTimelinePoint;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

public class JvmMetricsService {

    private static final long MAX_POINTS = 6_000;

    private final PulseConfig config;
    private final Deque<JvmTimelinePoint> points = new ConcurrentLinkedDeque<>();

    private volatile long lastSampleTimeMs = 0L;
    private volatile long lastGcCount = -1L;
    private volatile long lastGcPauseMs = -1L;
    private volatile long lastProcessCpuNs = -1L;
    private volatile double lastCpuPct = 0D;

    public JvmMetricsService(PulseConfig config) {
        this.config = config;
    }

    public JvmSnapshot snapshot(HttpPerfCollectorService httpCollector) {
        long now = Instant.now().toEpochMilli();

        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        long heapUsedMb = toMb(heap.getUsed());
        long heapCommittedMb = toMb(heap.getCommitted());
        long heapMaxMb = heap.getMax() > 0 ? toMb(heap.getMax()) : 0L;
        double heapUsagePct = heap.getMax() > 0 ? percent(heap.getUsed(), heap.getMax()) : 0D;

        GcTotals gcTotals = readGcTotals();
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        int threadCount = threadMXBean.getThreadCount();
        int peakThreadCount = threadMXBean.getPeakThreadCount();

        CpuSnapshot cpu = readCpu(now);
        HttpSummary http = summarizeHttp(httpCollector.allEvents(), now, config.retentionMs());

        long gcCountDelta = 0L;
        long gcPauseDeltaMs = 0L;
        if (lastGcCount >= 0) {
            gcCountDelta = Math.max(0L, gcTotals.count - lastGcCount);
        }
        if (lastGcPauseMs >= 0) {
            gcPauseDeltaMs = Math.max(0L, gcTotals.pauseMs - lastGcPauseMs);
        }
        lastGcCount = gcTotals.count;
        lastGcPauseMs = gcTotals.pauseMs;
        lastSampleTimeMs = now;

        JvmTimelinePoint point = new JvmTimelinePoint(
                now,
                heapUsagePct,
                cpu.processCpuPct,
                threadCount,
                gcTotals.count,
                gcTotals.pauseMs,
                http.avgLatencyMs,
                http.p95LatencyMs,
                http.errorRatePct
        );
        points.addLast(point);
        trim(now);

        List<JvmTimelinePoint> timeline = new ArrayList<>(points);
        timeline.sort(Comparator.comparingLong(JvmTimelinePoint::timestamp));

        return new JvmSnapshot(
                now,
                config.retentionMs(),
                heapUsedMb,
                heapCommittedMb,
                heapMaxMb,
                heapUsagePct,
                gcTotals.count,
                gcTotals.pauseMs,
                gcCountDelta,
                gcPauseDeltaMs,
                threadCount,
                peakThreadCount,
                cpu.processCpuPct,
                cpu.processCpuTimeMs,
                http.avgLatencyMs,
                http.p95LatencyMs,
                http.errorRatePct,
                http.throughputPerSec,
                timeline
        );
    }

    private GcTotals readGcTotals() {
        long totalCount = 0L;
        long totalPauseMs = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            long timeMs = bean.getCollectionTime();
            if (count > 0) {
                totalCount += count;
            }
            if (timeMs > 0) {
                totalPauseMs += timeMs;
            }
        }
        return new GcTotals(totalCount, totalPauseMs);
    }

    private CpuSnapshot readCpu(long nowMs) {
        java.lang.management.OperatingSystemMXBean generic = ManagementFactory.getOperatingSystemMXBean();
        long processCpuNs = -1L;
        double processCpuLoad = -1D;
        if (generic instanceof com.sun.management.OperatingSystemMXBean os) {
            processCpuNs = os.getProcessCpuTime();
            processCpuLoad = os.getProcessCpuLoad();
        }
        long processCpuTimeMs = processCpuNs > 0 ? processCpuNs / 1_000_000L : 0L;
        double cpuPct = processCpuLoad >= 0 ? processCpuLoad * 100D : fallbackCpuPct(nowMs, processCpuNs);

        if (!Double.isNaN(cpuPct) && !Double.isInfinite(cpuPct) && cpuPct >= 0) {
            lastCpuPct = Math.min(100D, cpuPct);
        }
        return new CpuSnapshot(lastCpuPct, processCpuTimeMs);
    }

    private double fallbackCpuPct(long nowMs, long processCpuNs) {
        if (lastSampleTimeMs <= 0 || lastProcessCpuNs < 0 || processCpuNs < 0) {
            lastProcessCpuNs = processCpuNs;
            return lastCpuPct;
        }
        long wallDeltaMs = Math.max(1L, nowMs - lastSampleTimeMs);
        long cpuDeltaNs = Math.max(0L, processCpuNs - lastProcessCpuNs);
        lastProcessCpuNs = processCpuNs;
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        return (cpuDeltaNs / 1_000_000D) / (wallDeltaMs * cores) * 100D;
    }

    private HttpSummary summarizeHttp(List<HttpRequestEvent> events, long nowMs, long retentionMs) {
        if (events.isEmpty()) {
            return HttpSummary.EMPTY;
        }

        long keepAfter = nowMs - retentionMs;
        long throughputAfter = nowMs - 60_000L;
        long totalCount = 0L;
        long errorCount = 0L;
        long sumLatency = 0L;
        long throughputCount = 0L;
        List<Long> durations = new ArrayList<>();

        for (HttpRequestEvent event : events) {
            if (event.timestamp() < keepAfter || event.timestamp() > nowMs) {
                continue;
            }
            totalCount++;
            sumLatency += event.durationMs();
            durations.add(event.durationMs());
            if (event.errorType() != null || (event.httpStatus() != null && event.httpStatus() >= 500)) {
                errorCount++;
            }
            if (event.timestamp() >= throughputAfter) {
                throughputCount++;
            }
        }

        if (totalCount == 0) {
            return HttpSummary.EMPTY;
        }
        durations.sort(Long::compareTo);
        long p95 = percentile(durations, 95);
        return new HttpSummary(
                (double) sumLatency / totalCount,
                p95,
                ((double) errorCount / totalCount) * 100D,
                throughputCount / 60D
        );
    }

    private long percentile(List<Long> sorted, int percent) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int index = Math.min(sorted.size() - 1, (int) Math.ceil((percent / 100D) * sorted.size()) - 1);
        return sorted.get(Math.max(0, index));
    }

    private void trim(long now) {
        long keepAfter = now - config.retentionMs();
        while (true) {
            JvmTimelinePoint head = points.peekFirst();
            if (head == null || head.timestamp() >= keepAfter) {
                break;
            }
            points.pollFirst();
        }
        while (points.size() > MAX_POINTS) {
            points.pollFirst();
        }
    }

    private long toMb(long bytes) {
        return Math.max(0L, bytes / (1024L * 1024L));
    }

    private double percent(long value, long total) {
        if (total <= 0) {
            return 0D;
        }
        return (value * 100D) / total;
    }

    private record GcTotals(long count, long pauseMs) {
    }

    private record CpuSnapshot(double processCpuPct, long processCpuTimeMs) {
    }

    private record HttpSummary(double avgLatencyMs, double p95LatencyMs, double errorRatePct, double throughputPerSec) {
        private static final HttpSummary EMPTY = new HttpSummary(0D, 0D, 0D, 0D);
    }
}
