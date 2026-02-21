const METRIC_INFO = {
    "heap-usage": {
        title: "Heap usage",
        summary: "Part of JVM heap memory currently used by Java objects.",
        interpretation: [
            "Stable usage with regular drops is healthy.",
            "A continuous rise without drops can indicate memory pressure or leak risk.",
            "If usage stays high (>80%) and latency rises, garbage collection can become more expensive."
        ],
        actions: [
            "Check allocation hot spots (large collections, temporary objects).",
            "Review cache size and object lifetime.",
            "Watch this metric with GC count and GC pause time."
        ]
    },
    "cpu-usage": {
        title: "CPU usage",
        summary: "CPU consumed by the monitored JVM process.",
        interpretation: [
            "Short peaks are normal under load.",
            "Sustained high CPU can slow requests and background work.",
            "High CPU with low throughput may indicate lock contention or busy loops."
        ],
        actions: [
            "Compare with endpoint latency spikes.",
            "Inspect hottest endpoints and stack samples.",
            "Check thread count growth for contention patterns."
        ]
    },
    "thread-count": {
        title: "Thread count",
        summary: "Number of live threads currently running inside the JVM.",
        interpretation: [
            "A stable baseline is expected for a stable load.",
            "A steady increase can indicate blocked threads or pool misconfiguration.",
            "Sudden spikes can follow traffic bursts or retries."
        ],
        actions: [
            "Inspect thread pool sizes in the application.",
            "Correlate spikes with slow endpoints and GC pauses.",
            "Capture thread dumps if count grows without returning to baseline."
        ]
    },
    "gc-count": {
        title: "GC count",
        summary: "Total number of garbage collection cycles since JVM start.",
        interpretation: [
            "A regular increase is normal.",
            "A faster-than-usual increase usually means more object churn.",
            "If GC count acceleration matches latency increase, memory pressure is likely."
        ],
        actions: [
            "Track object allocation rate in hot endpoints.",
            "Reduce temporary allocations in tight loops.",
            "Tune heap sizing only after confirming allocation behavior."
        ]
    },
    "gc-pause": {
        title: "GC pause time",
        summary: "Cumulative time spent in garbage collection pauses.",
        interpretation: [
            "Small occasional pauses are expected.",
            "Large or frequent pause jumps can be visible in request latency.",
            "Growing pause deltas under steady load often indicate heap pressure."
        ],
        actions: [
            "Compare pause spikes with p95 request latency spikes.",
            "Review heap usage trend and allocation-heavy code paths.",
            "Adjust memory settings only with measured before/after snapshots."
        ]
    },
    "request-latency": {
        title: "Request latency",
        summary: "Average processing time of captured HTTP transactions.",
        interpretation: [
            "Short-term fluctuations are expected with traffic variation.",
            "If average and p95 increase together, slowdown is systemic.",
            "If only p95 increases, a subset of requests is likely problematic."
        ],
        actions: [
            "Open Endpoint view and inspect slow traces first.",
            "Correlate with SQL durations and error events.",
            "Use stack hotspots to locate expensive execution paths."
        ]
    }
};

function renderSection(title, items) {
    return `
        <section class="info-modal-section">
            <h4>${title}</h4>
            <ul>
                ${items.map(item => `<li>${item}</li>`).join("")}
            </ul>
        </section>
    `;
}

function renderContent(definition) {
    return `
        <p class="info-modal-summary">${definition.summary}</p>
        ${renderSection("How to interpret", definition.interpretation)}
        ${renderSection("What to check", definition.actions)}
    `;
}

export function initInfoModal() {
    const modal = document.getElementById("infoModal");
    if (!modal) {
        return;
    }

    const title = document.getElementById("infoModalTitle");
    const body = document.getElementById("infoModalBody");
    if (!title || !body) {
        return;
    }

    const close = () => {
        modal.classList.add("hidden");
        document.body.classList.remove("modal-open");
    };

    const open = key => {
        const definition = METRIC_INFO[key];
        if (!definition) {
            return;
        }
        title.textContent = definition.title;
        body.innerHTML = renderContent(definition);
        modal.classList.remove("hidden");
        document.body.classList.add("modal-open");
    };

    document.addEventListener("click", event => {
        if (!(event.target instanceof Element)) {
            return;
        }

        const trigger = event.target.closest(".info-trigger");
        if (trigger) {
            event.preventDefault();
            open(trigger.getAttribute("data-info-key"));
            return;
        }

        if (event.target.closest("[data-info-close]")) {
            event.preventDefault();
            close();
        }
    });

    document.addEventListener("keydown", event => {
        if (event.key === "Escape" && !modal.classList.contains("hidden")) {
            close();
        }
    });
}
