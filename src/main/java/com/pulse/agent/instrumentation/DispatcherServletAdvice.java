package com.pulse.agent.instrumentation;

import lombok.RequiredArgsConstructor;
import net.bytebuddy.asm.Advice;

@RequiredArgsConstructor
public final class DispatcherServletAdvice {

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
}
