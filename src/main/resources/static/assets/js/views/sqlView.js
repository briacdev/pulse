import { escapeHtml, shortText, timeAgo, formatClock } from "../shared/formatters.js";

export function createSqlView(ui, state, getSearchQuery) {
    function matchesSearch(eventLike) {
        const query = getSearchQuery();
        if (!query) {
            return true;
        }
        return [
            eventLike.normalizedSql,
            eventLike.endpoint,
            eventLike.handler,
            eventLike.threadName,
            eventLike.errorType,
            eventLike.hottestFrame
        ]
            .filter(Boolean)
            .join(" ")
            .toLowerCase()
            .includes(query);
    }

    function renderSqlTimeline(snapshot, recent) {
        const now = Date.now();
        const start = now - state.retentionMs;
        const binsCount = 96;
        const binSize = state.retentionMs / binsCount;
        const bins = Array.from({ length: binsCount }, () => ({ latency: 0, hasError: false, count: 0 }));

        for (const event of recent) {
            if (event.timestamp < start || event.timestamp > now) continue;
            const index = Math.min(binsCount - 1, Math.max(0, Math.floor((event.timestamp - start) / binSize)));
            const bin = bins[index];
            bin.latency = Math.max(bin.latency, event.durationMs || 0);
            bin.hasError = bin.hasError || event.status === "ERROR";
            bin.count += 1;
        }

        if (snapshot && Array.isArray(snapshot.timeline)) {
            for (const point of snapshot.timeline) {
                if (point.timestamp < start || point.timestamp > now) continue;
                const index = Math.min(binsCount - 1, Math.max(0, Math.floor((point.timestamp - start) / binSize)));
                const bin = bins[index];
                bin.latency = Math.max(bin.latency, Math.round(point.avgLatencyMs || 0));
                bin.hasError = bin.hasError || (point.errorCount || 0) > 0;
                bin.count += point.count || 0;
            }
        }

        const max = Math.max(...bins.map(b => b.latency), 1);
        ui.timelineBars.innerHTML = bins.map((bin, i) => {
            const height = bin.count === 0 ? 4 : Math.max(8, Math.round((bin.latency / max) * 40));
            const barStart = start + (i * binSize);
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

    function renderSelected(recent, aggregates) {
        if (!recent.length) {
            ui.selectedTitle.textContent = "SQL Execution";
            ui.selectedAgo.textContent = "-";
            ui.mDuration.textContent = "0 ms";
            ui.mFreq.textContent = "0";
            ui.mErrors.textContent = "0";
            ui.sqlBody.textContent = "No query selected.";
            ui.contextBody.textContent = "endpoint: -\nthread: -\ntraceId: -\nstatus: -\nexception: -";
            return;
        }

        const index = Math.min(state.selectedSqlIndex, recent.length - 1);
        state.selectedSqlIndex = index;
        const event = recent[index];

        const aggregate = (aggregates || []).find(value => value.normalizedSql === event.normalizedSql);
        ui.selectedTitle.textContent = `${event.sqlType} • ${event.datasource || "unknown datasource"}`;
        ui.selectedAgo.textContent = timeAgo(event.timestamp);
        ui.mDuration.textContent = `${event.durationMs} ms`;
        ui.mFreq.textContent = String(aggregate?.count || 1);
        ui.mErrors.textContent = String(aggregate?.errorCount || (event.status === "ERROR" ? 1 : 0));
        ui.sqlBody.textContent = event.normalizedSql || "-";

        ui.contextBody.textContent = [
            `endpoint: ${event.endpoint || "-"}`,
            `thread: ${event.threadName || "-"}`,
            `traceId: ${event.traceId || "-"}`,
            `status: ${event.httpStatus ?? "-"}`,
            `exception: ${event.exceptionType || "-"}`
        ].join("\n");
    }

    function render(snapshot) {
        state.lastSqlSnapshot = snapshot;
        const allRecent = (snapshot.recent || []).filter(matchesSearch);

        ui.sqlTotalEvents.textContent = `${snapshot.totalEvents || 0} events`;
        ui.sqlMaxSlow.textContent = `max ${snapshot.slowest?.[0]?.maxLatencyMs || 0} ms`;

        ui.recentList.innerHTML = allRecent.length
            ? allRecent.map((event, index) => {
                const active = index === state.selectedSqlIndex ? "active" : "";
                const dotClass = event.status === "ERROR" ? "dot error" : "dot";
                return `
                    <article class="item ${active}" data-sql-index="${index}">
                        <div class="item-head">
                            <div style="display:flex; gap:8px; min-width:0;">
                                <span class="${dotClass}"></span>
                                <strong style="font-size:13px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">${escapeHtml(shortText(event.normalizedSql))}</strong>
                            </div>
                            <span class="muted">${event.durationMs}ms</span>
                        </div>
                        <p class="muted" style="margin:8px 0 0; font-size:12px;">${escapeHtml(event.endpoint || "No endpoint")} • ${timeAgo(event.timestamp)}</p>
                    </article>
                `;
            }).join("")
            : `<p class="muted" style="padding:8px;">No SQL execution</p>`;

        ui.recentList.querySelectorAll("[data-sql-index]").forEach(element => {
            element.addEventListener("click", () => {
                state.selectedSqlIndex = Number(element.getAttribute("data-sql-index"));
                render(snapshot);
            });
        });

        const direction = state.traceSort === "asc" ? 1 : -1;
        const sorted = [...allRecent].sort((a, b) => ((a.durationMs || 0) - (b.durationMs || 0)) * direction);
        ui.traceList.innerHTML = sorted.length
            ? sorted.slice(0, 24).map(event => {
                const statusClass = event.status === "ERROR" ? "status-pill error" : "status-pill";
                const dotClass = event.status === "ERROR" ? "dot error" : "dot";
                return `
                    <div class="trace-row">
                        <span class="${dotClass}" style="margin:0;"></span>
                        <div class="trace-name">${escapeHtml(event.sqlType || "SQL")} • ${escapeHtml(shortText(event.normalizedSql))}</div>
                        <div style="display:flex; align-items:center; gap:8px;">
                            <span class="${statusClass}">${event.status}</span>
                            <span class="muted" style="font-size:12px;">${event.durationMs} ms</span>
                        </div>
                    </div>
                `;
            }).join("")
            : `<p class="muted" style="padding:8px;">No traces</p>`;

        renderSelected(allRecent, snapshot.mostFrequent || []);
        renderSqlTimeline(snapshot, snapshot.recent || []);
    }

    return { render };
}
