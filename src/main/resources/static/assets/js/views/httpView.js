import { escapeHtml, shortText, timeAgo } from "../shared/formatters.js";

export function createHttpView(ui, state, getSearchQuery, getJson) {
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

    function clearTraceDetail() {
        ui.httpSlowHint.textContent = "Click a slow call";
        ui.httpHotspots.innerHTML = `<p class="muted" style="padding:8px;">No trace selected</p>`;
        ui.httpStacks.innerHTML = `<p class="muted" style="padding:8px;">No stack samples yet</p>`;
    }

    async function renderTraceDetail(id) {
        try {
            ui.httpSlowHint.textContent = "Loading trace...";
            const detail = await getJson(`/api/http/trace/${encodeURIComponent(id)}`);
            if (!detail || !detail.event) {
                clearTraceDetail();
                return;
            }

            const event = detail.event;
            ui.httpSlowHint.textContent = `${event.endpoint} • ${event.durationMs} ms • ${detail.totalSamples} samples`;

            const hotspots = detail.hotspots || [];
            ui.httpHotspots.innerHTML = hotspots.length
                ? hotspots.map(spot => `
                    <article class="hotspot-row">
                        <div class="hotspot-title">${spot.samples} samples • ${spot.percent.toFixed(1)}%</div>
                        <div class="hotspot-value">${escapeHtml(spot.frame)}</div>
                    </article>
                `).join("")
                : `<p class="muted" style="padding:8px;">No hotspot samples captured for this call</p>`;

            const stacks = detail.sampledStacks || [];
            ui.httpStacks.innerHTML = stacks.length
                ? stacks.map(sample => `
                    <article class="hotspot-row">
                        <div class="hotspot-title">${sample.samples} samples • ${sample.percent.toFixed(1)}%</div>
                        <div class="hotspot-value">${escapeHtml(sample.stack)}</div>
                    </article>
                `).join("")
                : `<p class="muted" style="padding:8px;">No stack samples captured</p>`;
        } catch (error) {
            ui.httpSlowHint.textContent = "Trace unavailable";
            ui.httpHotspots.innerHTML = `<p class="muted" style="padding:8px;">Failed to load trace details</p>`;
            ui.httpStacks.innerHTML = `<p class="muted" style="padding:8px;">${escapeHtml(error.message)}</p>`;
        }
    }

    function render(snapshot) {
        state.lastHttpSnapshot = snapshot;
        const query = getSearchQuery();
        const endpointFiltered = (snapshot.endpoints || []).filter(endpoint => endpoint.endpoint.toLowerCase().includes(query));
        const endpointList = endpointFiltered.length ? endpointFiltered : (snapshot.endpoints || []);

        ui.httpTotalEvents.textContent = `${snapshot.totalEvents || 0} calls`;

        if (!state.selectedEndpoint && endpointList.length) {
            state.selectedEndpoint = endpointList[0].endpoint;
        }
        if (state.selectedEndpoint && !endpointList.some(endpoint => endpoint.endpoint === state.selectedEndpoint)) {
            state.selectedEndpoint = endpointList[0]?.endpoint || null;
        }

        ui.endpointList.innerHTML = endpointList.length
            ? endpointList.map(endpoint => {
                const active = endpoint.endpoint === state.selectedEndpoint ? "active" : "";
                return `
                    <article class="item ${active}" data-endpoint="${escapeHtml(endpoint.endpoint)}">
                        <div class="item-head">
                            <strong style="font-size:13px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">${escapeHtml(endpoint.endpoint)}</strong>
                            <span class="muted">${endpoint.maxLatencyMs}ms</span>
                        </div>
                        <p class="muted" style="margin:8px 0 0; font-size:12px;">count ${endpoint.count} • p95 ${endpoint.p95LatencyMs}ms • errors ${endpoint.errorCount}</p>
                    </article>
                `;
            }).join("")
            : `<p class="muted" style="padding:8px;">No endpoint data</p>`;

        ui.endpointList.querySelectorAll("[data-endpoint]").forEach(element => {
            element.addEventListener("click", () => {
                state.selectedEndpoint = element.getAttribute("data-endpoint");
                render(snapshot);
            });
        });

        const selectedEndpointAggregate = (snapshot.endpoints || []).find(endpoint => endpoint.endpoint === state.selectedEndpoint);
        const callsForEndpoint = (snapshot.recent || [])
            .filter(call => call.endpoint === state.selectedEndpoint)
            .filter(matchesSearch)
            .sort((a, b) => (b.durationMs || 0) - (a.durationMs || 0));

        ui.httpSelectedEndpoint.textContent = selectedEndpointAggregate?.endpoint || "HTTP(s) calls";
        ui.httpSelectedStats.textContent = selectedEndpointAggregate
            ? `${selectedEndpointAggregate.count} calls • ${selectedEndpointAggregate.slowCount} slow • ${selectedEndpointAggregate.errorCount} errors`
            : "-";

        ui.httpP95.textContent = `${selectedEndpointAggregate?.p95LatencyMs || 0} ms`;
        ui.httpAvg.textContent = `${Math.round(selectedEndpointAggregate?.avgLatencyMs || 0)} ms`;
        ui.httpErrors.textContent = String(selectedEndpointAggregate?.errorCount || 0);

        ui.httpCallList.innerHTML = callsForEndpoint.length
            ? callsForEndpoint.slice(0, 80).map(call => {
                const active = call.id === state.selectedHttpCallId ? "active" : "";
                return `
                    <article class="item ${active}" data-http-call-id="${call.id}">
                        <div class="item-head">
                            <strong>${escapeHtml(call.endpoint)}</strong>
                            <span class="muted">${call.durationMs} ms</span>
                        </div>
                        <p class="muted" style="margin:8px 0 0; font-size:12px;">status ${call.httpStatus ?? "-"} • ${escapeHtml(call.handler || "no-handler")} • ${timeAgo(call.timestamp)}</p>
                    </article>
                `;
            }).join("")
            : `<p class="muted" style="padding:8px;">No HTTP calls for this endpoint</p>`;

        ui.httpCallList.querySelectorAll("[data-http-call-id]").forEach(element => {
            element.addEventListener("click", async () => {
                const id = element.getAttribute("data-http-call-id");
                state.selectedHttpCallId = id;
                render(snapshot);
                await renderTraceDetail(id);
            });
        });

        const slowCalls = (snapshot.slowCalls || []).filter(matchesSearch);
        ui.slowCallList.innerHTML = slowCalls.length
            ? slowCalls.slice(0, 50).map(call => {
                const active = call.id === state.selectedHttpCallId ? "active" : "";
                const slowClass = call.errorType ? "status-pill error" : "status-pill";
                return `
                    <article class="item ${active}" data-slow-call-id="${call.id}">
                        <div class="item-head">
                            <strong style="font-size:13px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">${escapeHtml(shortText(call.endpoint, 68))}</strong>
                            <span class="muted">${call.durationMs} ms</span>
                        </div>
                        <p class="muted" style="margin:8px 0 0; font-size:12px;">status ${call.httpStatus ?? "-"} • ${escapeHtml(call.hottestFrame || "no-sample")}</p>
                        <div style="margin-top:8px;"><span class="${slowClass}">${call.errorType ? "ERROR" : "SLOW"}</span></div>
                    </article>
                `;
            }).join("")
            : `<p class="muted" style="padding:8px;">No slow HTTP(s) calls captured</p>`;

        ui.slowCallList.querySelectorAll("[data-slow-call-id]").forEach(element => {
            element.addEventListener("click", async () => {
                const id = element.getAttribute("data-slow-call-id");
                state.selectedHttpCallId = id;
                render(snapshot);
                await renderTraceDetail(id);
            });
        });

        if (state.selectedHttpCallId && !slowCalls.some(call => call.id === state.selectedHttpCallId)
            && !callsForEndpoint.some(call => call.id === state.selectedHttpCallId)) {
            state.selectedHttpCallId = null;
            clearTraceDetail();
        }
    }

    return { render, clearTraceDetail };
}
