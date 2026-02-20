import { createSqlView } from "./views/sqlView.js";
import { createHttpView } from "./views/httpView.js";
import { createJvmView } from "./views/jvmView.js";

(() => {
    const state = {
        retentionMs: 15 * 60 * 1000,
        appName: "Monitored Application",
        activeView: "http",
        lastSqlSnapshot: null,
        lastHttpSnapshot: null,
        lastJvmSnapshot: null,
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
        menuJvm: document.getElementById("menuJvm"),
        sqlView: document.getElementById("sqlView"),
        httpView: document.getElementById("httpView"),
        jvmView: document.getElementById("jvmView"),
        sqlTimelineWrap: document.getElementById("sqlTimelineWrap"),
        retentionLabel: document.getElementById("retentionLabel"),
        appName: document.getElementById("appName"),
        searchInput: document.getElementById("searchInput"),
        interval: document.getElementById("interval"),
        refreshIcon: document.getElementById("refreshIcon"),
        traceSort: document.getElementById("traceSort"),

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
        httpSlowHint: document.getElementById("httpSlowHint"),

        jvmHeapValue: document.getElementById("jvmHeapValue"),
        jvmHeapMeta: document.getElementById("jvmHeapMeta"),
        jvmHeapChart: document.getElementById("jvmHeapChart"),
        jvmCpuValue: document.getElementById("jvmCpuValue"),
        jvmCpuMeta: document.getElementById("jvmCpuMeta"),
        jvmCpuChart: document.getElementById("jvmCpuChart"),
        jvmThreadValue: document.getElementById("jvmThreadValue"),
        jvmThreadMeta: document.getElementById("jvmThreadMeta"),
        jvmThreadChart: document.getElementById("jvmThreadChart"),
        jvmGcValue: document.getElementById("jvmGcValue"),
        jvmGcMeta: document.getElementById("jvmGcMeta"),
        jvmGcCountChart: document.getElementById("jvmGcCountChart"),
        jvmGcPauseChart: document.getElementById("jvmGcPauseChart"),
        jvmLatencyValue: document.getElementById("jvmLatencyValue"),
        jvmLatencyMeta: document.getElementById("jvmLatencyMeta"),
        jvmLatencyChart: document.getElementById("jvmLatencyChart"),
        jvmErrorRateValue: document.getElementById("jvmErrorRateValue"),
        jvmErrorRateMeta: document.getElementById("jvmErrorRateMeta"),
        jvmErrorRateChart: document.getElementById("jvmErrorRateChart")
    };

    function getSearchQuery() {
        return ui.searchInput.value.trim().toLowerCase();
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

    const sqlView = createSqlView(ui, state, getSearchQuery);
    const httpView = createHttpView(ui, state, getSearchQuery, getJson);
    const jvmView = createJvmView(ui, state);

    function setView(view) {
        state.activeView = view;
        ui.menuSql.classList.toggle("active", view === "sql");
        ui.menuHttp.classList.toggle("active", view === "http");
        ui.menuJvm.classList.toggle("active", view === "jvm");
        ui.sqlView.classList.toggle("hidden", view !== "sql");
        ui.sqlTimelineWrap.classList.toggle("hidden", view !== "sql");
        ui.httpView.classList.toggle("hidden", view !== "http");
        ui.jvmView.classList.toggle("hidden", view !== "jvm");

        if (view === "sql" && state.lastSqlSnapshot) {
            sqlView.render(state.lastSqlSnapshot);
        }
        if (view === "http" && state.lastHttpSnapshot) {
            httpView.render(state.lastHttpSnapshot);
        }
        if (view === "jvm" && state.lastJvmSnapshot) {
            jvmView.render(state.lastJvmSnapshot);
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
        const [sqlSnapshot, httpSnapshot, jvmSnapshot] = await Promise.all([
            getJson("/api/sql/snapshot"),
            getJson("/api/http/snapshot"),
            getJson("/api/jvm/snapshot")
        ]);

        sqlView.render(sqlSnapshot);
        httpView.render(httpSnapshot);
        jvmView.render(jvmSnapshot);
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
        ui.menuJvm.addEventListener("click", () => setView("jvm"));

        ui.interval.addEventListener("change", startPolling);
        ui.searchInput.addEventListener("input", () => {
            if (state.lastSqlSnapshot) {
                sqlView.render(state.lastSqlSnapshot);
            }
            if (state.lastHttpSnapshot) {
                httpView.render(state.lastHttpSnapshot);
            }
        });

        ui.traceSort.addEventListener("change", () => {
            state.traceSort = ui.traceSort.value;
            if (state.lastSqlSnapshot) {
                sqlView.render(state.lastSqlSnapshot);
            }
        });
    }

    async function bootstrap() {
        bindEvents();
        httpView.clearTraceDetail();
        setView("http");

        try {
            await fetchConfig();
        } catch (_) {
        }

        startPolling();
    }

    bootstrap();
})();
