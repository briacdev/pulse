package com.pulse.agent.instrumentation;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import net.bytebuddy.asm.Advice;

import java.util.Map;

@RequiredArgsConstructor
public final class DispatcherServletAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnterAdvice(@Advice.Origin("#t.#m") String origin,
                                     @Advice.Argument(value = 0, readOnly = false) HttpServletRequest request) {
        request = HttpTransactionSupport.prepareRequest(request);
        HttpTransactionSupport.onEnter(origin, request);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExitAdvice(@Advice.Argument(value = 0, optional = true) Object request,
                                    @Advice.Argument(value = 1, optional = true) Object response,
                                    @Advice.Thrown Throwable thrown) {
        HttpTransactionSupport.onExit(request, response, thrown);
    }

    // Helper entrypoint used by local tests without ByteBuddy weaving.
    public static void onEnter(@Advice.Origin("#t.#m") String origin,
                               @Advice.Argument(value = 0, optional = true) Object request) {
        Object effectiveRequest = HttpTransactionSupport.prepareRequest(request);
        HttpTransactionSupport.onEnter(origin, effectiveRequest);
    }

    // Helper entrypoint used by local tests without ByteBuddy weaving.
    public static void onExit(@Advice.Argument(value = 0, optional = true) Object request,
                              @Advice.Argument(value = 1, optional = true) Object response,
                              @Advice.Thrown Throwable thrown) {
        HttpTransactionSupport.onExit(request, response, thrown);
    }

    public static Map<String, Object> debugCounters() {
        return HttpTransactionSupport.debugCounters();
    }
}
