import {formatClock} from "../shared/formatters.js";

function hasError(value) {
    if (!value) {
        return false;
    }
    if (value.status === "ERROR") {
        return true;
    }
    if (value.errorType) {
        return true;
    }
    return value.httpStatus != null && Number(value.httpStatus) >= 500;
}

export function renderLatencyTimeline(ui, state, snapshot, recentEvents) {
    const now = Date.now();
    const start = now - state.retentionMs;
    const binsCount = 96;
    const binSize = state.retentionMs / binsCount;
    const bins = Array.from({length: binsCount}, () => ({latency: 0, hasError: false, count: 0}));

    for (const event of recentEvents || []) {
        if (!event || event.timestamp < start || event.timestamp > now) continue;
        const index = Math.min(binsCount - 1, Math.max(0, Math.floor((event.timestamp - start) / binSize)));
        const bin = bins[index];
        bin.latency = Math.max(bin.latency, Number(event.durationMs) || 0);
        bin.hasError = bin.hasError || hasError(event);
        bin.count += 1;
    }

    if (snapshot && Array.isArray(snapshot.timeline)) {
        for (const point of snapshot.timeline) {
            if (!point || point.timestamp < start || point.timestamp > now) continue;
            const index = Math.min(binsCount - 1, Math.max(0, Math.floor((point.timestamp - start) / binSize)));
            const bin = bins[index];
            bin.latency = Math.max(bin.latency, Math.round(Number(point.avgLatencyMs) || 0));
            bin.hasError = bin.hasError || (Number(point.errorCount) || 0) > 0;
            bin.count += Number(point.count) || 0;
        }
    }

    const max = Math.max(...bins.map(bin => bin.latency), 1);
    ui.timelineBars.innerHTML = bins.map((bin, index) => {
        const height = bin.count === 0 ? 4 : Math.max(8, Math.round((bin.latency / max) * 40));
        const barStart = start + (index * binSize);
        const barEnd = Math.min(now, barStart + binSize);
        const background = bin.hasError
            ? "background:linear-gradient(180deg,#ff7994,#ff4f70);"
            : "";
        return `<div class="bar" style="height:${height}px;${background}" title="${formatClock(barStart)} -> ${formatClock(barEnd)} | ${bin.latency} ms"></div>`;
    }).join("");

    const step = state.retentionMs / 5;
    ui.tick1.textContent = formatClock(start);
    ui.tick2.textContent = formatClock(start + step);
    ui.tick3.textContent = formatClock(start + (step * 2));
    ui.tick4.textContent = formatClock(start + (step * 3));
    ui.tick5.textContent = formatClock(start + (step * 4));
    ui.tick6.textContent = formatClock(now);
}
