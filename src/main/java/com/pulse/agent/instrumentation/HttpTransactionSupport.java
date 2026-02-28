package com.pulse.agent.instrumentation;

import com.pulse.app.core.HttpContextHolder;
import com.pulse.app.core.HttpStackProfilerService;
import com.pulse.app.core.PulseRuntime;
import com.pulse.app.model.HttpRequestContext;
import com.pulse.app.model.http.HttpRequestEvent;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class HttpTransactionSupport {
    private static final ThreadLocal<Integer> REQUEST_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<RequestState> ACTIVE_STATE = new ThreadLocal<>();
    private static final int MAX_CAPTURED_ITEMS = 40;
    private static final int MAX_CAPTURED_VALUE_LEN = 320;
    private static final int MAX_CAPTURED_BODY_BYTES = 8192;
    private static final int MAX_CAPTURED_BODY_LEN = 4096;
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie"
    );
    private static final Set<String> BINARY_BODY_TYPES = Set.of(
            "application/octet-stream",
            "application/pdf",
            "application/zip",
            "image/",
            "audio/",
            "video/"
    );

    private HttpTransactionSupport() {
    }

    public static Object prepareRequest(Object request) {
        if (request == null) {
            return null;
        }
        try {
            Object contentBytes = readMethod(request, "getContentAsByteArray");
            if (contentBytes instanceof byte[]) {
                return request;
            }
            ClassLoader loader = request.getClass().getClassLoader();
            Class<?> servletRequestClass = Class.forName("jakarta.servlet.http.HttpServletRequest", false, loader);
            if (!servletRequestClass.isInstance(request)) {
                return request;
            }
            Class<?> wrapperClass = Class.forName("org.springframework.web.util.ContentCachingRequestWrapper", false, loader);
            if (wrapperClass.isInstance(request)) {
                return request;
            }
            return wrapperClass.getConstructor(servletRequestClass).newInstance(request);
        } catch (Throwable ignored) {
            return request;
        }
    }

    public static void onEnter(Object request) {
        int depth = REQUEST_DEPTH.get();
        REQUEST_DEPTH.set(depth + 1);
        if (depth > 0) {
            return;
        }
        String method = safeString(readMethod(request, "getMethod"));
        String path = safeString(readMethod(request, "getRequestURI"));
        String handler = safeString(readMethod(request, "getAttribute",
                "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern"));
        String endpoint = endpointName(method, path);
        String traceId = UUID.randomUUID().toString();
        RequestCapture capture = captureRequest(request);
        boolean ignoredInternal = isInternalPulseRequest(request, path);
        if (ignoredInternal) {
            ACTIVE_STATE.set(new RequestState(
                    null,
                    System.nanoTime(),
                    endpoint,
                    handler,
                    traceId,
                    capture.queryString,
                    capture.parameters,
                    capture.headers,
                    capture.auth,
                    true
            ));
            return;
        }
        HttpStackProfilerService.Handle stackHandle = null;
        try {
            stackHandle = PulseRuntime.getHttpStackProfiler().start(Thread.currentThread());
        } catch (Throwable ignored) {
        }
        HttpContextHolder.set(new HttpRequestContext(endpoint, handler, null, traceId));
        ACTIVE_STATE.set(new RequestState(
                stackHandle,
                System.nanoTime(),
                endpoint,
                handler,
                traceId,
                capture.queryString,
                capture.parameters,
                capture.headers,
                capture.auth,
                false
        ));
    }

    public static void onExit(Object request,
                              Object response,
                              Throwable thrown) {
        int depth = REQUEST_DEPTH.get() - 1;
        if (depth <= 0) {
            REQUEST_DEPTH.remove();
        } else {
            REQUEST_DEPTH.set(depth);
        }
        if (depth > 0) {
            return;
        }
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
        } catch (Throwable ignored) {
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
                profile == null ? null : profile.hottestFrame(),
                state.queryString,
                state.parameters,
                state.headers,
                state.auth,
                captureRequestBody(request, state.parameters)
        );
        try {
            PulseRuntime.getHttpCollector().record(
                    event,
                    profile == null ? 0 : profile.totalSamples(),
                    profile == null ? null : profile.hotspots(),
                    profile == null ? null : profile.stacks()
            );
        } catch (Throwable ignored) {
        } finally {
            HttpContextHolder.clear();
        }
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
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            return value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object readMethod(Object target, String methodName, String arg) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName, String.class);
            Object value = method.invoke(target, arg);
            return value;
        } catch (Exception ignored) {
            return null;
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

    private static RequestCapture captureRequest(Object request) {
        String queryString = truncate(safeString(readMethod(request, "getQueryString")), MAX_CAPTURED_VALUE_LEN);
        Map<String, String> parameters = captureParameters(request);
        Map<String, String> headers = captureHeaders(request);
        String auth = captureAuth(request, headers);
        return new RequestCapture(queryString, parameters, headers, auth);
    }

    private static Map<String, String> captureParameters(Object request) {
        Object raw = readMethod(request, "getParameterMap");
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (out.size() >= MAX_CAPTURED_ITEMS) {
                break;
            }
            String key = safeString(entry.getKey()).trim();
            if (key.isBlank()) {
                continue;
            }
            String value = stringifyMultiValue(entry.getValue());
            if (value.isBlank()) {
                continue;
            }
            out.put(key, truncate(value, MAX_CAPTURED_VALUE_LEN));
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private static Map<String, String> captureHeaders(Object request) {
        Object namesRaw = readMethod(request, "getHeaderNames");
        if (!(namesRaw instanceof Enumeration<?> names)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        while (names.hasMoreElements() && out.size() < MAX_CAPTURED_ITEMS) {
            String name = safeString(names.nextElement()).trim();
            if (name.isBlank()) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (SENSITIVE_HEADERS.contains(lower)) {
                continue;
            }
            String value = readHeaderValues(request, name);
            if (value.isBlank()) {
                continue;
            }
            out.put(name, truncate(value, MAX_CAPTURED_VALUE_LEN));
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private static String captureAuth(Object request, Map<String, String> headers) {
        String authType = truncate(safeString(readMethod(request, "getAuthType")), MAX_CAPTURED_VALUE_LEN);
        String principal = principalName(readMethod(request, "getUserPrincipal"));
        String authorization = maskAuthorization(safeString(readMethod(request, "getHeader", "Authorization")));
        List<String> parts = new ArrayList<>();
        if (!authType.isBlank()) {
            parts.add("type=" + authType);
        }
        if (!principal.isBlank()) {
            parts.add("principal=" + principal);
        }
        if (!authorization.isBlank()) {
            parts.add("authorization=" + authorization);
        } else if (headers.containsKey("X-Api-Key")) {
            parts.add("authorization=ApiKey ***");
        }
        if (parts.isEmpty()) {
            return null;
        }
        return String.join(" | ", parts);
    }

    private static String captureRequestBody(Object request, Map<String, String> parameters) {
        String contentType = safeString(readMethod(request, "getContentType")).toLowerCase(Locale.ROOT);
        if (isBinaryContentType(contentType)) {
            return null;
        }
        byte[] rawBody = readCachedBodyBytes(request, 0);
        if (rawBody != null && rawBody.length > 0) {
            String body = new String(rawBody, StandardCharsets.UTF_8);
            return sanitizeBody(body);
        }
        if (contentType.contains("application/x-www-form-urlencoded") && parameters != null && !parameters.isEmpty()) {
            return sanitizeBody(joinMap(parameters, "&", "="));
        }
        return null;
    }

    private static boolean isBinaryContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        for (String type : BINARY_BODY_TYPES) {
            if (contentType.contains(type)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readCachedBodyBytes(Object request, int depth) {
        if (request == null || depth > 3) {
            return null;
        }
        Object body = readMethod(request, "getContentAsByteArray");
        if (body instanceof byte[] bytes && bytes.length > 0) {
            if (bytes.length <= MAX_CAPTURED_BODY_BYTES) {
                return bytes;
            }
            byte[] truncated = new byte[MAX_CAPTURED_BODY_BYTES];
            System.arraycopy(bytes, 0, truncated, 0, MAX_CAPTURED_BODY_BYTES);
            return truncated;
        }
        Object wrapped = readMethod(request, "getRequest");
        if (wrapped != null && wrapped != request) {
            return readCachedBodyBytes(wrapped, depth + 1);
        }
        return null;
    }

    private static String readHeaderValues(Object request, String headerName) {
        Object raw = readMethod(request, "getHeaders", headerName);
        if (raw instanceof Enumeration<?> values) {
            List<String> all = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            while (values.hasMoreElements() && all.size() < 3) {
                String value = truncate(safeString(values.nextElement()).trim(), MAX_CAPTURED_VALUE_LEN);
                if (value.isBlank() || !seen.add(value)) {
                    continue;
                }
                all.add(value);
            }
            return String.join(", ", all);
        }
        return truncate(safeString(readMethod(request, "getHeader", headerName)).trim(), MAX_CAPTURED_VALUE_LEN);
    }

    private static String principalName(Object principal) {
        if (principal == null) {
            return "";
        }
        Object name = readMethod(principal, "getName");
        if (name != null) {
            return truncate(safeString(name), MAX_CAPTURED_VALUE_LEN);
        }
        return truncate(safeString(principal), MAX_CAPTURED_VALUE_LEN);
    }

    private static String maskAuthorization(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = trimmed.substring(7).trim();
            if (token.length() <= 8) {
                return "Bearer ***";
            }
            return "Bearer " + token.substring(0, 8) + "...";
        }
        if (trimmed.regionMatches(true, 0, "Basic ", 0, 6)) {
            return "Basic ***";
        }
        if (trimmed.length() <= 8) {
            return "***";
        }
        return trimmed.substring(0, 8) + "...";
    }

    private static String stringifyMultiValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String[] strings) {
            List<String> items = new ArrayList<>();
            for (String string : strings) {
                if (string == null || string.isBlank()) {
                    continue;
                }
                items.add(truncate(string, MAX_CAPTURED_VALUE_LEN));
                if (items.size() >= 4) {
                    break;
                }
            }
            return String.join(", ", items);
        }
        if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            for (Object item : list) {
                String str = safeString(item).trim();
                if (str.isBlank()) {
                    continue;
                }
                items.add(truncate(str, MAX_CAPTURED_VALUE_LEN));
                if (items.size() >= 4) {
                    break;
                }
            }
            return String.join(", ", items);
        }
        return truncate(safeString(value), MAX_CAPTURED_VALUE_LEN);
    }

    private static String joinMap(Map<String, String> values, String pairSeparator, String keyValueSeparator) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                builder.append(pairSeparator);
            }
            builder.append(entry.getKey()).append(keyValueSeparator).append(entry.getValue());
            first = false;
        }
        return builder.toString();
    }

    private static String sanitizeBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String cleaned = body.replace("\r", "").trim();
        if (cleaned.length() > MAX_CAPTURED_BODY_LEN) {
            cleaned = cleaned.substring(0, MAX_CAPTURED_BODY_LEN) + "...";
        }
        return cleaned;
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...";
    }

    private record RequestState(HttpStackProfilerService.Handle stackHandle,
                                long startNs,
                                String endpoint,
                                String handler,
                                String traceId,
                                String queryString,
                                Map<String, String> parameters,
                                Map<String, String> headers,
                                String auth,
                                boolean ignoredInternal) {
    }

    private record RequestCapture(String queryString,
                                  Map<String, String> parameters,
                                  Map<String, String> headers,
                                  String auth) {
    }
}
