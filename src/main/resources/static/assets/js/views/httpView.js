import {escapeHtml, shortText, timeAgo} from "../shared/formatters.js";
import {renderLatencyTimeline} from "../components/latencyTimeline.js";

export function createHttpView(ui, state, getSearchQuery, getJson) {
    function mapToText(mapLike) {
        if (!mapLike || typeof mapLike !== "object") {
            return "-";
        }
        const entries = Object.entries(mapLike);
        if (!entries.length) {
            return "-";
        }
        return entries.map(([key, value]) => `${key}: ${value}`).join("\n");
    }

    function mapToInlineText(mapLike) {
        if (!mapLike || typeof mapLike !== "object") {
            return "";
        }
        return Object.entries(mapLike).map(([key, value]) => `${key}=${value}`).join(" ");
    }

    function requestDetailsText(call) {
        if (!call) {
            return "No transaction selected.";
        }
        return [
            `${call.endpoint || "(unknown endpoint)"}`,
            `handler: ${call.handler || "-"}`,
            `status: ${call.httpStatus ?? "-"}`,
            `durationMs: ${call.durationMs || 0}`,
            `thread: ${call.threadName || "-"}`,
            `query: ${call.queryString || "-"}`,
            "",
            "params:",
            mapToText(call.parameters),
            "",
            "headers:",
            mapToText(call.headers),
            "",
            "auth:",
            call.auth || "-",
            "",
            "body:",
            call.requestBody || "-"
        ].join("\n");
    }

    function matchesSearch(eventLike) {
        const query = getSearchQuery();
        if (!query) {
            return true;
        }
        return [
            eventLike.endpoint,
            eventLike.handler,
            eventLike.threadName,
            eventLike.errorType,
            eventLike.hottestFrame,
            eventLike.traceId,
            eventLike.queryString,
            eventLike.auth,
            eventLike.requestBody,
            mapToInlineText(eventLike.parameters),
            mapToInlineText(eventLike.headers)
        ]
            .filter(Boolean)
            .join(" ")
            .toLowerCase()
            .includes(query);
    }

    function isError(call) {
        if (!call) {
            return false;
        }
        return Boolean(call.errorType) || (call.httpStatus != null && Number(call.httpStatus) >= 500);
    }

    function clearTraceDetail() {
        ui.httpHotspots.innerHTML = `<p class="muted" style="padding:8px;">No trace selected</p>`;
        ui.httpStacks.innerHTML = `<p class="muted" style="padding:8px;">No stack samples yet</p>`;
    }

    async function renderTraceDetail(id) {
        if (!id) {
            clearTraceDetail();
            return;
        }
        try {
            ui.httpSlowHint.textContent = "Loading trace...";
            const detail = await getJson(`/api/http/trace/${encodeURIComponent(id)}`);
            if (!detail || !detail.event) {
                clearTraceDetail();
                ui.httpSlowHint.textContent = "trace unavailable";
                return;
            }

            const event = detail.event;
            ui.httpSlowHint.textContent = `${event.durationMs} ms • ${detail.totalSamples} samples`;

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
            ui.httpSlowHint.textContent = "trace unavailable";
            ui.httpHotspots.innerHTML = `<p class="muted" style="padding:8px;">Failed to load trace details</p>`;
            ui.httpStacks.innerHTML = `<p class="muted" style="padding:8px;">${escapeHtml(error.message)}</p>`;
        }
    }

    function renderSelected(snapshot, selectedCall) {
        if (!selectedCall) {
            ui.httpSelectedEndpoint.textContent = "HTTP Transaction";
            ui.httpSelectedStats.textContent = "-";
            ui.httpDuration.textContent = "0 ms";
            ui.httpFreq.textContent = "0";
            ui.httpErrors.textContent = "0";
            ui.httpRequestBody.textContent = "No transaction selected.";
            ui.httpContextBody.textContent = "endpoint: -\nhandler: -\nthread: -\ntraceId: -\nstatus: -\nparams: 0\nheaders: 0\nauth: -\nbody: -\nexception: -";
            clearTraceDetail();
            return;
        }

        const aggregate = (snapshot.endpoints || []).find(endpoint => endpoint.endpoint === selectedCall.endpoint);
        const eventErrors = isError(selectedCall) ? 1 : 0;

        ui.httpSelectedEndpoint.textContent = selectedCall.endpoint || "(unknown endpoint)";
        ui.httpSelectedStats.textContent = timeAgo(selectedCall.timestamp);
        ui.httpDuration.textContent = `${selectedCall.durationMs || 0} ms`;
        ui.httpFreq.textContent = String(aggregate?.count || 1);
        ui.httpErrors.textContent = String(aggregate?.errorCount || eventErrors);

        ui.httpRequestBody.textContent = requestDetailsText(selectedCall);

        const paramCount = selectedCall.parameters ? Object.keys(selectedCall.parameters).length : 0;
        const headerCount = selectedCall.headers ? Object.keys(selectedCall.headers).length : 0;
        ui.httpContextBody.textContent = [
            `endpoint: ${selectedCall.endpoint || "-"}`,
            `handler: ${selectedCall.handler || "-"}`,
            `thread: ${selectedCall.threadName || "-"}`,
            `traceId: ${selectedCall.traceId || "-"}`,
            `status: ${selectedCall.httpStatus ?? "-"}`,
            `params: ${paramCount}`,
            `headers: ${headerCount}`,
            `auth: ${selectedCall.auth || "-"}`,
            `body: ${selectedCall.requestBody ? "captured" : "-"}`,
            `exception: ${selectedCall.errorType || "-"}`
        ].join("\n");
    }

    function render(snapshot) {
        state.lastHttpSnapshot = snapshot;

        const filteredRecent = (snapshot.recent || []).filter(matchesSearch);
        ui.httpTotalEvents.textContent = `${snapshot.totalEvents || 0} calls`;

        if (!state.selectedHttpCallId || !filteredRecent.some(call => call.id === state.selectedHttpCallId)) {
            state.selectedHttpCallId = filteredRecent[0]?.id || null;
            state.lastHttpDetailId = null;
        }

        const selectedCall = filteredRecent.find(call => call.id === state.selectedHttpCallId) || null;

        ui.endpointList.innerHTML = filteredRecent.length
            ? filteredRecent.map(call => {
                const active = call.id === state.selectedHttpCallId ? "active" : "";
                const dotClass = isError(call) ? "dot error" : "dot";
                return `
                    <article class="item ${active}" data-http-call-id="${call.id}">
                        <div class="item-head">
                            <div style="display:flex; gap:8px; min-width:0;">
                                <span class="${dotClass}"></span>
                                <strong style="font-size:13px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">${escapeHtml(shortText(call.endpoint || "(unknown endpoint)"))}</strong>
                            </div>
                            <span class="muted">${call.durationMs || 0}ms</span>
                        </div>
                        <p class="muted" style="margin:8px 0 0; font-size:12px;">status ${call.httpStatus ?? "-"} • ${escapeHtml(call.handler || "no-handler")} • ${timeAgo(call.timestamp)}</p>
                    </article>
                `;
            }).join("")
            : `<p class="muted" style="padding:8px;">No HTTP transactions</p>`;

        ui.endpointList.querySelectorAll("[data-http-call-id]").forEach(element => {
            element.addEventListener("click", () => {
                state.selectedHttpCallId = element.getAttribute("data-http-call-id");
                render(snapshot);
            });
        });

        const direction = ui.httpTraceSort.value === "asc" ? 1 : -1;
        const traceEvents = [...filteredRecent].sort((a, b) => ((a.durationMs || 0) - (b.durationMs || 0)) * direction);
        const maxDuration = traceEvents.length ? Math.max(...traceEvents.map(call => call.durationMs || 0)) : 0;

        ui.slowCallList.innerHTML = traceEvents.length
            ? traceEvents.slice(0, 120).map(call => {
                const statusClass = isError(call) ? "status-pill error" : "status-pill";
                const dotClass = isError(call) ? "dot error" : "dot";
                const activeClass = call.id === state.selectedHttpCallId ? " active-trace" : "";
                return `
                    <div class="trace-row trace-clickable${activeClass}" data-trace-call-id="${call.id}">
                        <span class="${dotClass}" style="margin:0;"></span>
                        <div class="trace-name">${escapeHtml(shortText(call.endpoint || "(unknown endpoint)", 72))} • ${escapeHtml(call.handler || "no-handler")}</div>
                        <div style="display:flex; align-items:center; gap:8px;">
                            <span class="${statusClass}">${isError(call) ? "ERROR" : "OK"}</span>
                            <span class="muted" style="font-size:12px;">${call.durationMs || 0} ms</span>
                        </div>
                    </div>
                `;
            }).join("")
            : `<p class="muted" style="padding:8px;">No traces</p>`;

        ui.slowCallList.querySelectorAll("[data-trace-call-id]").forEach(element => {
            element.addEventListener("click", () => {
                state.selectedHttpCallId = element.getAttribute("data-trace-call-id");
                render(snapshot);
            });
        });

        renderSelected(snapshot, selectedCall);
        renderLatencyTimeline(ui, state, snapshot, snapshot.recent || []);

        if (selectedCall && state.lastHttpDetailId !== selectedCall.id) {
            state.lastHttpDetailId = selectedCall.id;
            renderTraceDetail(selectedCall.id);
        } else if (!selectedCall) {
            ui.httpSlowHint.textContent = `max ${maxDuration} ms`;
        }

        if (selectedCall && state.lastHttpDetailId === selectedCall.id) {
            ui.httpSlowHint.textContent = ui.httpSlowHint.textContent.includes("samples")
                ? ui.httpSlowHint.textContent
                : `${selectedCall.durationMs || 0} ms`;
        }
    }

    return {render, clearTraceDetail};
}
