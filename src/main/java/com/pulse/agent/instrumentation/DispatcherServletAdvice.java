package com.pulse.agent.instrumentation;

import com.pulse.app.core.HttpContextHolder;
import com.pulse.app.core.HttpStackProfilerService;
import com.pulse.app.core.PulseOpenTelemetry;
import com.pulse.app.core.PulseRuntime;
import com.pulse.app.model.HttpRequestContext;
import com.pulse.app.model.http.HttpRequestEvent;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class DispatcherServletAdvice {

    private static final ThreadLocal<Integer> REQUEST_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<RequestState> ACTIVE_STATE = new ThreadLocal<>();

    private static final AttributeKey<String> HTTP_METHOD = AttributeKey.stringKey("http.request.method");
    private static final AttributeKey<String> URL_PATH = AttributeKey.stringKey("url.path");
    private static final AttributeKey<String> HTTP_ROUTE = AttributeKey.stringKey("http.route");
    private static final AttributeKey<Long> HTTP_STATUS = AttributeKey.longKey("http.response.status_code");
    private static final AttributeKey<String> THREAD_NAME = AttributeKey.stringKey("thread.name");
    private static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> PULSE_ENDPOINT = AttributeKey.stringKey("pulse.endpoint");
    private static final AttributeKey<String> PULSE_HANDLER = AttributeKey.stringKey("pulse.handler");
    private static final AttributeKey<String> PULSE_TRACE_ID = AttributeKey.stringKey("pulse.trace_id");
    private static final AttributeKey<String> PULSE_HOTTEST = AttributeKey.stringKey("pulse.hottest_frame");

    private static final AtomicLong ENTER_TOTAL = new AtomicLong();
    private static final AtomicLong ENTER_ROOT = new AtomicLong();
    private static final AtomicLong EXIT_TOTAL = new AtomicLong();
    private static final AtomicLong EXIT_ROOT = new AtomicLong();
    private static final AtomicLong RECORDED = new AtomicLong();
    private static final AtomicLong OTel_START_ERRORS = new AtomicLong();
    private static final AtomicLong OTel_FINISH_ERRORS = new AtomicLong();
    private static final AtomicLong RECORD_ERRORS = new AtomicLong();
    private static final ConcurrentHashMap<String, AtomicLong> ORIGIN_HITS = new ConcurrentHashMap<>();
    private static volatile String lastError = "";
    private static volatile String lastOrigin = "";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Origin("#t.#m") String origin,
                               @Advice.Argument(0) Object request) {
        ENTER_TOTAL.incrementAndGet();
        lastOrigin = origin;
        ORIGIN_HITS.computeIfAbsent(origin, key -> new AtomicLong()).incrementAndGet();
        int depth = REQUEST_DEPTH.get();
        REQUEST_DEPTH.set(depth + 1);
        if (depth > 0) {
            return;
        }
        ENTER_ROOT.incrementAndGet();

        String method = String.valueOf(readMethod(request, "getMethod"));
        String path = String.valueOf(readMethod(request, "getRequestURI"));
        String handler = String.valueOf(readMethod(request, "getAttribute", "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern"));
        String endpoint = method + " " + path;
        String traceId = firstNonBlank(MDC.get("traceId"), MDC.get("X-B3-TraceId"), MDC.get("trace_id"));

        Span span = null;
        Scope scope = null;
        String effectiveTraceId = traceId;
        HttpStackProfilerService.Handle stackHandle = null;
        try {
            span = PulseOpenTelemetry.tracer()
                    .spanBuilder(endpoint)
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute(HTTP_METHOD, method)
                    .setAttribute(URL_PATH, path)
                    .setAttribute(PULSE_ENDPOINT, endpoint)
                    .setAttribute(THREAD_NAME, Thread.currentThread().getName())
                    .startSpan();

            if (handler != null && !handler.isBlank()) {
                span.setAttribute(HTTP_ROUTE, handler);
                span.setAttribute(PULSE_HANDLER, handler);
            }
            String spanTraceId = span.getSpanContext().getTraceId();
            if (spanTraceId != null && !spanTraceId.isBlank()) {
                span.setAttribute(PULSE_TRACE_ID, spanTraceId);
                effectiveTraceId = spanTraceId;
            } else if (traceId != null && !traceId.isBlank()) {
                span.setAttribute(PULSE_TRACE_ID, traceId);
            }

            scope = span.makeCurrent();
            stackHandle = PulseRuntime.getHttpStackProfiler().start(Thread.currentThread());
        } catch (Throwable error) {
            OTel_START_ERRORS.incrementAndGet();
            lastError = "onEnter OTel start: " + error.getClass().getSimpleName() + " - " + String.valueOf(error.getMessage());
            span = null;
            scope = null;
            stackHandle = null;
        }

        HttpContextHolder.set(new HttpRequestContext(endpoint, handler, null, effectiveTraceId));
        ACTIVE_STATE.set(new RequestState(span, scope, stackHandle, System.nanoTime(), endpoint, handler, effectiveTraceId));
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Argument(0) Object request,
                              @Advice.Argument(1) Object response,
                              @Advice.Thrown Throwable thrown) {
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

        Integer status = null;
        String handler = state.handler;
        try {
            status = readInteger(response, "getStatus");
            handler = String.valueOf(readMethod(request, "getAttribute", "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern"));

            if (state.span != null && status != null) {
                state.span.setAttribute(HTTP_STATUS, status.longValue());
            }
            if (state.span != null && handler != null && !handler.isBlank()) {
                state.span.setAttribute(HTTP_ROUTE, handler);
                state.span.setAttribute(PULSE_HANDLER, handler);
            }

            HttpStackProfilerService.StackProfile profile = null;
            if (state.span != null && state.stackHandle != null) {
                try {
                    profile = PulseRuntime.getHttpStackProfiler()
                            .finishAndStore(state.span.getSpanContext().getSpanId(), state.stackHandle);
                } catch (Throwable error) {
                    OTel_FINISH_ERRORS.incrementAndGet();
                    lastError = "onExit OTel finish: " + error.getClass().getSimpleName() + " - " + String.valueOf(error.getMessage());
                }
            }
            if (state.span != null && profile != null && profile.hottestFrame() != null) {
                state.span.setAttribute(PULSE_HOTTEST, profile.hottestFrame());
            }

            if (state.span != null && thrown != null) {
                state.span.setStatus(StatusCode.ERROR);
                state.span.setAttribute(EXCEPTION_TYPE, thrown.getClass().getSimpleName());
                state.span.recordException(thrown);
            }
        } finally {
            if (state.scope != null) {
                state.scope.close();
            }
            if (state.span != null) {
                state.span.end();
            }

            long durationMs = Math.max(0L, (System.nanoTime() - state.startNs) / 1_000_000L);
            HttpStackProfilerService.StackProfile profile = null;
            if (state.span != null) {
                profile = PulseRuntime.getHttpStackProfiler().popBySpanId(state.span.getSpanContext().getSpanId());
            }
            String id = state.span == null ? "http-" + state.startNs : state.span.getSpanContext().getSpanId();
            HttpRequestEvent event = new HttpRequestEvent(
                    id,
                    System.currentTimeMillis() - durationMs,
                    durationMs,
                    state.endpoint,
                    handler == null || handler.isBlank() ? state.handler : handler,
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
            }
            HttpContextHolder.clear();
        }
    }

    public static Map<String, Object> debugCounters() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("enterTotal", ENTER_TOTAL.get());
        output.put("enterRoot", ENTER_ROOT.get());
        output.put("exitTotal", EXIT_TOTAL.get());
        output.put("exitRoot", EXIT_ROOT.get());
        output.put("recorded", RECORDED.get());
        output.put("otelStartErrors", OTel_START_ERRORS.get());
        output.put("otelFinishErrors", OTel_FINISH_ERRORS.get());
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record RequestState(Span span,
                                Scope scope,
                                HttpStackProfilerService.Handle stackHandle,
                                long startNs,
                                String endpoint,
                                String handler,
                                String traceId) {
    }
}
