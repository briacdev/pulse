package com.pulse.agent.instrumentation;

import com.pulse.app.core.HttpContextHolder;
import com.pulse.app.core.HttpStackProfilerService;
import com.pulse.app.core.PulseRuntime;
import com.pulse.app.model.HttpRequestContext;
import com.pulse.app.model.http.HttpRequestEvent;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class HttpTransactionSupport {

    private static final ThreadLocal<Integer> REQUEST_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<RequestState> ACTIVE_STATE = new ThreadLocal<>();

    private static final AtomicLong ENTER_TOTAL = new AtomicLong();
    private static final AtomicLong ENTER_ROOT = new AtomicLong();
    private static final AtomicLong EXIT_TOTAL = new AtomicLong();
    private static final AtomicLong EXIT_ROOT = new AtomicLong();
    private static final AtomicLong RECORDED = new AtomicLong();
    private static final AtomicLong IGNORED_INTERNAL = new AtomicLong();
    private static final AtomicLong RECORD_ERRORS = new AtomicLong();
    private static final ConcurrentHashMap<String, AtomicLong> ORIGIN_HITS = new ConcurrentHashMap<>();

    private static volatile String lastError = "";
    private static volatile String lastOrigin = "";

    private HttpTransactionSupport() {
    }

    public static void onEnter(String origin, Object request) {
        ENTER_TOTAL.incrementAndGet();
        lastOrigin = origin;
        ORIGIN_HITS.computeIfAbsent(origin, key -> new AtomicLong()).incrementAndGet();

        int depth = REQUEST_DEPTH.get();
        REQUEST_DEPTH.set(depth + 1);
        if (depth > 0) {
            return;
        }
        ENTER_ROOT.incrementAndGet();

        String method = safeString(readMethod(request, "getMethod"));
        String path = safeString(readMethod(request, "getRequestURI"));
        String handler = safeString(readMethod(request, "getAttribute",
                "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern"));
        String endpoint = endpointName(method, path);
        String traceId = UUID.randomUUID().toString();
        boolean ignoredInternal = isInternalPulseRequest(request, path);

        if (ignoredInternal) {
            IGNORED_INTERNAL.incrementAndGet();
            ACTIVE_STATE.set(new RequestState(null, System.nanoTime(), endpoint, handler, traceId, true));
            return;
        }

        HttpStackProfilerService.Handle stackHandle = null;
        try {
            stackHandle = PulseRuntime.getHttpStackProfiler().start(Thread.currentThread());
        } catch (Throwable error) {
            lastError = "onEnter start profiler: " + error.getClass().getSimpleName() + " - " + String.valueOf(error.getMessage());
        }

        HttpContextHolder.set(new HttpRequestContext(endpoint, handler, null, traceId));
        ACTIVE_STATE.set(new RequestState(stackHandle, System.nanoTime(), endpoint, handler, traceId, false));
    }

    public static void onExit(Object request,
                              Object response,
                              Throwable thrown) {
        EXIT_TOTAL.incrementAndGet();
        int depth = REQUEST_DEPTH.get() - 1;
        if (depth <= 0) {
            REQUEST_DEPTH.remove();
        } else {
            REQUEST_DEPTH.set(depth);
        }
        if (depth > 0) {
            return;
        }
        EXIT_ROOT.incrementAndGet();

        RequestState state = ACTIVE_STATE.get();
        ACTIVE_STATE.remove();
        if (state == null) {
            HttpContextHolder.clear();
            return;
        }
        if (state.ignoredInternal) {
            HttpContextHolder.clear();
            return;
        }

        String handler = safeString(readMethod(request, "getAttribute",
                "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern"));
        if (handler.isBlank()) {
            handler = state.handler;
        }
        Integer status = readInteger(response, "getStatus");
        long durationMs = Math.max(0L, (System.nanoTime() - state.startNs) / 1_000_000L);

        HttpStackProfilerService.StackProfile profile = null;
        try {
            profile = PulseRuntime.getHttpStackProfiler().finishAndStore(state.traceId, state.stackHandle);
            if (profile == null) {
                profile = PulseRuntime.getHttpStackProfiler().popBySpanId(state.traceId);
            } else {
                PulseRuntime.getHttpStackProfiler().popBySpanId(state.traceId);
            }
        } catch (Throwable error) {
            lastError = "onExit collect profiler: " + error.getClass().getSimpleName() + " - " + String.valueOf(error.getMessage());
        }

        HttpRequestEvent event = new HttpRequestEvent(
                state.traceId,
                System.currentTimeMillis() - durationMs,
                durationMs,
                state.endpoint,
                handler,
                status,
                state.traceId,
                Thread.currentThread().getName(),
                thrown == null ? null : thrown.getClass().getSimpleName(),
                durationMs >= PulseRuntime.getConfig().slowHttpThresholdMs(),
                profile == null ? null : profile.hottestFrame()
        );

        try {
            PulseRuntime.getHttpCollector().record(
                    event,
                    profile == null ? 0 : profile.totalSamples(),
                    profile == null ? null : profile.hotspots(),
                    profile == null ? null : profile.stacks()
            );
            RECORDED.incrementAndGet();
        } catch (Throwable error) {
            RECORD_ERRORS.incrementAndGet();
            lastError = "onExit record: " + error.getClass().getSimpleName() + " - " + String.valueOf(error.getMessage());
        } finally {
            HttpContextHolder.clear();
        }
    }

    static Map<String, Object> debugCounters() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("enterTotal", ENTER_TOTAL.get());
        output.put("enterRoot", ENTER_ROOT.get());
        output.put("exitTotal", EXIT_TOTAL.get());
        output.put("exitRoot", EXIT_ROOT.get());
        output.put("recorded", RECORDED.get());
        output.put("ignoredInternal", IGNORED_INTERNAL.get());
        output.put("recordErrors", RECORD_ERRORS.get());
        output.put("activeDepthCurrentThread", REQUEST_DEPTH.get());
        output.put("activeStateCurrentThread", ACTIVE_STATE.get() != null);
        output.put("lastOrigin", lastOrigin);
        output.put("originHitsTop", topOriginHits());
        output.put("lastError", lastError);
        return output;
    }

    private static Map<String, Long> topOriginHits() {
        Map<String, Long> top = new LinkedHashMap<>();
        ORIGIN_HITS.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, AtomicLong> e) -> e.getValue().get()).reversed())
                .limit(8)
                .forEach(entry -> top.put(entry.getKey(), entry.getValue().get()));
        return top;
    }

    private static String endpointName(String method, String path) {
        String effectiveMethod = method == null || method.isBlank() ? "UNKNOWN" : method;
        String effectivePath = path == null || path.isBlank() ? "/" : path;
        return effectiveMethod + " " + effectivePath;
    }

    private static String safeString(Object value) {
        if (value == null) {
            return "";
        }
        String asString = String.valueOf(value);
        return asString == null ? "" : asString;
    }

    private static Object readMethod(Object target, String methodName) {
        if (target == null) {
            return "";
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            return value == null ? "" : value;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static Object readMethod(Object target, String methodName, String arg) {
        if (target == null) {
            return "";
        }
        try {
            Method method = target.getClass().getMethod(methodName, String.class);
            Object value = method.invoke(target, arg);
            return value == null ? "" : value;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static Integer readInteger(Object target, String methodName) {
        Object raw = readMethod(target, methodName);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private static boolean isInternalPulseRequest(Object request, String path) {
        Integer localPort = readInteger(request, "getLocalPort");
        if (localPort == null) {
            localPort = readInteger(request, "getServerPort");
        }
        return localPort != null
                && localPort == PulseRuntime.getConfig().port()
                && (path == null || path.isBlank() || path.startsWith("/") || "unknown".equalsIgnoreCase(path));
    }

    private record RequestState(HttpStackProfilerService.Handle stackHandle,
                                long startNs,
                                String endpoint,
                                String handler,
                                String traceId,
                                boolean ignoredInternal) {
    }
}
