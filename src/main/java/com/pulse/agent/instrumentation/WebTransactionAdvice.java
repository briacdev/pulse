package com.pulse.agent.instrumentation;

import net.bytebuddy.asm.Advice;

import java.util.Map;

public final class WebTransactionAdvice {

    private WebTransactionAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Origin("#t.#m") String origin,
                               @Advice.Argument(value = 0, optional = true) Object request) {
        HttpTransactionSupport.onEnter(origin, request);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Argument(value = 0, optional = true) Object request,
                              @Advice.Argument(value = 1, optional = true) Object response,
                              @Advice.Thrown Throwable thrown) {
        HttpTransactionSupport.onExit(request, response, thrown);
    }

    public static Map<String, Object> debugCounters() {
        return HttpTransactionSupport.debugCounters();
    }
}
