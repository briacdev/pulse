(() => {
    const state = {
        retentionMs: 15 * 60 * 1000,
        appName: "Monitored Application",
        activeView: "sql",
        lastSqlSnapshot: null,
        lastHttpSnapshot: null,
        selectedSqlIndex: 0,
        selectedEndpoint: null,
        selectedHttpCallId: null,
        traceSort: "desc",
        pollTimer: null,
        spinTimer: null
    };

    const ui = {
        menuSql: document.getElementById("menuSql"),
        menuHttp: document.getElementById("menuHttp"),
        sqlView: document.getElementById("sqlView"),
        httpView: document.getElementById("httpView"),
        sqlTimelineWrap: document.getElementById("sqlTimelineWrap"),
        retentionLabel: document.getElementById("retentionLabel"),
        appName: document.getElementById("appName"),
        searchInput: document.getElementById("searchInput"),
        interval: document.getElementById("interval"),
        refreshIcon: document.getElementById("refreshIcon"),

        tick1: document.getElementById("tick1"),
        tick2: document.getElementById("tick2"),
        tick3: document.getElementById("tick3"),
        tick4: document.getElementById("tick4"),
        tick5: document.getElementById("tick5"),
        tick6: document.getElementById("tick6"),
        timelineBars: document.getElementById("timelineBars"),

        sqlTotalEvents: document.getElementById("sqlTotalEvents"),
        sqlMaxSlow: document.getElementById("sqlMaxSlow"),
        recentList: document.getElementById("recentList"),
        traceList: document.getElementById("traceList"),
        traceSort: document.getElementById("traceSort"),

        selectedTitle: document.getElementById("selectedTitle"),
        selectedAgo: document.getElementById("selectedAgo"),
        mDuration: document.getElementById("mDuration"),
        mFreq: document.getElementById("mFreq"),
        mErrors: document.getElementById("mErrors"),
        sqlBody: document.getElementById("sqlBody"),
        contextBody: document.getElementById("contextBody"),

        httpTotalEvents: document.getElementById("httpTotalEvents"),
        endpointList: document.getElementById("endpointList"),
        httpSelectedEndpoint: document.getElementById("httpSelectedEndpoint"),
        httpSelectedStats: document.getElementById("httpSelectedStats"),
        httpP95: document.getElementById("httpP95"),
        httpAvg: document.getElementById("httpAvg"),
        httpErrors: document.getElementById("httpErrors"),
        httpCallList: document.getElementById("httpCallList"),
        slowCallList: document.getElementById("slowCallList"),
        httpHotspots: document.getElementById("httpHotspots"),
        httpStacks: document.getElementById("httpStacks"),
        httpSlowHint: document.getElementById("httpSlowHint")
    };

    function escapeHtml(value) {
        if (value == null) {
            return "";
        }
        return String(value)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/\"/g, "&quot;")
            .replace(/'/g, "&#039;");
    }

    function shortText(value, max = 96) {
        if (!value) {
            return "";
        }
        return value.length > max ? `${value.slice(0, max)}...` : value;
    }

    function timeAgo(timestamp) {
        const deltaSec = Math.max(0, Math.floor((Date.now() - timestamp) / 1000));
        if (deltaSec < 60) {
            return `${deltaSec}s ago`;
        }
        const min = Math.floor(deltaSec / 60);
        if (min < 60) {
            return `${min} min ago`;
        }
        return `${Math.floor(min / 60)}h ago`;
    }

    function formatClock(timestamp) {
        return new Date(timestamp).toLocaleTimeString([], {
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit",
            hour12: false
        });
    }

    async function getJson(path) {
        const response = await fetch(path);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status} on ${path}`);
        }
        return response.json();
    }

    function triggerRefreshSpin() {
        ui.refreshIcon.classList.remove("spin");
        void ui.refreshIcon.offsetWidth;
        ui.refreshIcon.classList.add("spin");
    }

    function matchesSearch(eventLike) {
        const query = ui.searchInput.value.trim().toLowerCase();
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

    function setView(view) {
        state.activeView = view;
        ui.menuSql.classList.toggle("active", view === "sql");
        ui.menuHttp.classList.toggle("active", view === "http");
        ui.sqlView.classList.toggle("hidden", view !== "sql");
        ui.sqlTimelineWrap.classList.toggle("hidden", view !== "sql");
        ui.httpView.classList.toggle("hidden", view !== "http");

        if (view === "sql" && state.lastSqlSnapshot) {
            renderSql(state.lastSqlSnapshot);
        }
        if (view === "http" && state.lastHttpSnapshot) {
            renderHttp(state.lastHttpSnapshot);
        }
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

    function renderSqlSelected(recent, aggregates) {
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

    function renderSqlLists(snapshot) {
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
                renderSql(snapshot);
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

        renderSqlSelected(allRecent, snapshot.mostFrequent || []);
        renderSqlTimeline(snapshot, snapshot.recent || []);
    }

    function renderSql(snapshot) {
        state.lastSqlSnapshot = snapshot;
        renderSqlLists(snapshot);
    }

    function renderHttp(snapshot) {
        state.lastHttpSnapshot = snapshot;
        const query = ui.searchInput.value.trim().toLowerCase();
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
                renderHttp(snapshot);
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
                renderHttp(snapshot);
                await renderHttpTraceDetail(id);
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
                renderHttp(snapshot);
                await renderHttpTraceDetail(id);
            });
        });

        if (state.selectedHttpCallId && !slowCalls.some(call => call.id === state.selectedHttpCallId)
            && !callsForEndpoint.some(call => call.id === state.selectedHttpCallId)) {
            state.selectedHttpCallId = null;
            clearHttpTraceDetail();
        }
    }

    function clearHttpTraceDetail() {
        ui.httpSlowHint.textContent = "Click a slow call";
        ui.httpHotspots.innerHTML = `<p class="muted" style="padding:8px;">No trace selected</p>`;
        ui.httpStacks.innerHTML = `<p class="muted" style="padding:8px;">No stack samples yet</p>`;
    }

    async function renderHttpTraceDetail(id) {
        try {
            ui.httpSlowHint.textContent = "Loading trace...";
            const detail = await getJson(`/api/http/trace/${encodeURIComponent(id)}`);
            if (!detail || !detail.event) {
                clearHttpTraceDetail();
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

    async function fetchConfig() {
        const config = await getJson("/api/sql/config");
        state.retentionMs = Number(config.retentionMs || state.retentionMs);
        state.appName = config.appName || state.appName;

        const minutes = Math.max(1, Math.round(state.retentionMs / 60000));
        ui.retentionLabel.textContent = `Data retention: ${minutes} minute${minutes > 1 ? "s" : ""}`;
        ui.appName.textContent = state.appName;
    }

    async function fetchSnapshots() {
        triggerRefreshSpin();
        const [sqlSnapshot, httpSnapshot] = await Promise.all([
            getJson("/api/sql/snapshot"),
            getJson("/api/http/snapshot")
        ]);

        renderSql(sqlSnapshot);
        renderHttp(httpSnapshot);
    }

    function startPolling() {
        const delay = Number(ui.interval.value);
        clearInterval(state.pollTimer);
        clearInterval(state.spinTimer);

        fetchSnapshots().catch(() => {
        });
        state.pollTimer = setInterval(() => {
            fetchSnapshots().catch(() => {
            });
        }, delay);
        state.spinTimer = setInterval(triggerRefreshSpin, delay);
    }

    function bindEvents() {
        ui.menuSql.addEventListener("click", () => setView("sql"));
        ui.menuHttp.addEventListener("click", () => setView("http"));

        ui.interval.addEventListener("change", startPolling);
        ui.searchInput.addEventListener("input", () => {
            if (state.lastSqlSnapshot) {
                renderSql(state.lastSqlSnapshot);
            }
            if (state.lastHttpSnapshot) {
                renderHttp(state.lastHttpSnapshot);
            }
        });

        ui.traceSort.addEventListener("change", () => {
            state.traceSort = ui.traceSort.value;
            if (state.lastSqlSnapshot) {
                renderSql(state.lastSqlSnapshot);
            }
        });
    }

    async function bootstrap() {
        bindEvents();
        clearHttpTraceDetail();
        setView("sql");

        try {
            await fetchConfig();
        } catch (_) {
        }

        startPolling();
    }

    bootstrap();
})();
