import { renderSparkline } from "../components/sparkline.js";

export function createJvmView(ui, state) {
    function render(snapshot) {
        state.lastJvmSnapshot = snapshot;
        const timeline = snapshot.timeline || [];
        const windowEnd = snapshot.generatedAt || Date.now();
        const retentionMs = snapshot.retentionMs || state.retentionMs;
        const windowStart = windowEnd - retentionMs;
        const series = mapFn => timeline.map(point => ({ t: point.timestamp, v: mapFn(point) || 0 }));

        ui.jvmHeapValue.textContent = `${(snapshot.heapUsagePct || 0).toFixed(1)}%`;
        ui.jvmHeapMeta.textContent = `${snapshot.heapUsedMb || 0} MB / ${snapshot.heapMaxMb || 0} MB`;
        ui.jvmHeapChart.innerHTML = renderSparkline(series(point => point.heapUsagePct), "#2ef2c6", "%", windowStart, windowEnd);

        ui.jvmCpuValue.textContent = `${(snapshot.cpuUsagePct || 0).toFixed(1)}%`;
        ui.jvmCpuMeta.textContent = `process CPU time ${snapshot.processCpuTimeMs || 0} ms`;
        ui.jvmCpuChart.innerHTML = renderSparkline(series(point => point.cpuUsagePct), "#beff31", "%", windowStart, windowEnd);

        ui.jvmThreadValue.textContent = String(snapshot.threadCount || 0);
        ui.jvmThreadMeta.textContent = `peak ${snapshot.peakThreadCount || 0}`;
        ui.jvmThreadChart.innerHTML = renderSparkline(series(point => point.threadCount), "#9fd2ff", "", windowStart, windowEnd);

        ui.jvmGcValue.textContent = `${snapshot.gcCountTotal || 0} / ${snapshot.gcPauseMsTotal || 0} ms`;
        ui.jvmGcMeta.textContent = `delta +${snapshot.gcCountDelta || 0} / +${snapshot.gcPauseDeltaMs || 0} ms`;
        ui.jvmGcCountChart.innerHTML = renderSparkline(series(point => point.gcCountTotal), "#70ffc8", "", windowStart, windowEnd);
        ui.jvmGcPauseChart.innerHTML = renderSparkline(series(point => point.gcPauseMsTotal), "#66b8ff", "ms", windowStart, windowEnd);

        ui.jvmLatencyValue.textContent = `${Math.round(snapshot.requestAvgLatencyMs || 0)} ms`;
        ui.jvmLatencyMeta.textContent = `p95 ${Math.round(snapshot.requestP95LatencyMs || 0)} ms`;
        ui.jvmLatencyChart.innerHTML = renderSparkline(series(point => point.requestAvgLatencyMs), "#7be0ff", "ms", windowStart, windowEnd);

        ui.jvmErrorRateValue.textContent = `${(snapshot.errorRatePct || 0).toFixed(2)}%`;
        ui.jvmErrorRateMeta.textContent = `throughput ${(snapshot.requestThroughputPerSec || 0).toFixed(2)} req/s`;
        ui.jvmErrorRateChart.innerHTML = renderSparkline(series(point => point.errorRatePct), "#ff8aa0", "%", windowStart, windowEnd);
    }

    return { render };
}
