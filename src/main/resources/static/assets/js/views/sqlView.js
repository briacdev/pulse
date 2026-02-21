import { escapeHtml, shortText, timeAgo } from "../shared/formatters.js";
import { renderLatencyTimeline } from "../components/latencyTimeline.js";

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

    function renderSelected(recent, aggregates) {
        if (!recent.length) {
            ui.selectedTitle.textContent = "SQL Execution";
            ui.selectedAgo.textContent = "-";
            ui.mDuration.textContent = "0 ms";
            ui.mFreq.textContent = "0";
            ui.mErrors.textContent = "0";
            ui.sqlBody.textContent = "No query selected.";
            ui.contextBody.textContent = "endpoint: -\nhandler: -\nthread: -\ntraceId: -\nstatus: -\nexception: -";
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
            `handler: ${event.handler || "-"}`,
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
        renderLatencyTimeline(ui, state, snapshot, snapshot.recent || []);
    }

    return { render };
}
