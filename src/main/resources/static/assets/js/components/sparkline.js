import {formatClock} from "../shared/formatters.js";

export function renderSparkline(samples, color, unitSuffix = "", windowStart, windowEnd) {
    const width = 420;
    const height = 132;
    const padLeft = 10;
    const padRight = 10;
    const padTop = 10;
    const padBottom = 20;
    const labelY = height - 4;
    const midTime = windowStart + Math.floor((windowEnd - windowStart) / 2);

    if (!samples || !samples.length) {
        return `
            <svg class="spark-svg" viewBox="0 0 ${width} ${height}" preserveAspectRatio="none">
                <line class="spark-grid" x1="${padLeft}" y1="${padTop}" x2="${width - padRight}" y2="${padTop}"></line>
                <line class="spark-grid" x1="${padLeft}" y1="${(padTop + (height - padBottom)) / 2}" x2="${width - padRight}" y2="${(padTop + (height - padBottom)) / 2}"></line>
                <line class="spark-grid" x1="${padLeft}" y1="${height - padBottom}" x2="${width - padRight}" y2="${height - padBottom}"></line>
                <text x="${padLeft}" y="${labelY}" fill="#9fb2d8" font-size="10">${formatClock(windowStart)}</text>
                <text x="${width / 2}" y="${labelY}" text-anchor="middle" fill="#9fb2d8" font-size="10">${formatClock(midTime)}</text>
                <text x="${width - padRight}" y="${labelY}" text-anchor="end" fill="#9fb2d8" font-size="10">${formatClock(windowEnd)}</text>
            </svg>
        `;
    }

    const values = samples.map(sample => sample.v);
    const min = Math.min(...values);
    const max = Math.max(...values);
    const span = Math.max(1e-6, max - min);
    const timeSpan = Math.max(1, windowEnd - windowStart);

    const points = samples.map((sample, index) => {
        const fallbackX = samples.length === 1 ? padLeft : padLeft + (index * (width - padLeft - padRight) / (samples.length - 1));
        const relative = (sample.t - windowStart) / timeSpan;
        const x = sample.t ? (padLeft + Math.max(0, Math.min(1, relative)) * (width - padLeft - padRight)) : fallbackX;
        const y = (height - padBottom) - ((sample.v - min) / span) * ((height - padBottom) - padTop);
        return {x, y};
    });

    const linePoints = points.map(point => `${point.x.toFixed(2)},${point.y.toFixed(2)}`).join(" ");
    const areaPoints = `${padLeft},${height - padBottom} ${linePoints} ${width - padRight},${height - padBottom}`;
    const last = points[points.length - 1];
    const midY = (padTop + (height - padBottom)) / 2;
    const topY = padTop;
    const bottomY = height - padBottom;

    return `
        <svg class="spark-svg" viewBox="0 0 ${width} ${height}" preserveAspectRatio="none">
            <line class="spark-grid" x1="${padLeft}" y1="${topY}" x2="${width - padRight}" y2="${topY}"></line>
            <line class="spark-grid" x1="${padLeft}" y1="${midY}" x2="${width - padRight}" y2="${midY}"></line>
            <line class="spark-grid" x1="${padLeft}" y1="${bottomY}" x2="${width - padRight}" y2="${bottomY}"></line>
            <polyline class="spark-area" fill="${color}" points="${areaPoints}"></polyline>
            <polyline class="spark-line" stroke="${color}" points="${linePoints}"></polyline>
            <circle class="spark-last" cx="${last.x.toFixed(2)}" cy="${last.y.toFixed(2)}" fill="${color}" stroke="#e7f2ff"></circle>
            <text x="${padLeft}" y="${topY - 2}" fill="#9fb2d8" font-size="10">${max.toFixed(1)}${unitSuffix}</text>
            <text x="${padLeft}" y="${bottomY - 2}" fill="#9fb2d8" font-size="10">${min.toFixed(1)}${unitSuffix}</text>
            <text x="${padLeft}" y="${labelY}" fill="#9fb2d8" font-size="10">${formatClock(windowStart)}</text>
            <text x="${width / 2}" y="${labelY}" text-anchor="middle" fill="#9fb2d8" font-size="10">${formatClock(midTime)}</text>
            <text x="${width - padRight}" y="${labelY}" text-anchor="end" fill="#9fb2d8" font-size="10">${formatClock(windowEnd)}</text>
        </svg>
    `;
}
