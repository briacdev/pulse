package com.pulse.app.core;

import com.pulse.app.model.HttpRequestContext;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class HttpContextHolder {

    private static final ThreadLocal<HttpRequestContext> CONTEXT = new ThreadLocal<>();

    public static void set(HttpRequestContext context) {
        CONTEXT.set(context);
    }

    public static HttpRequestContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
