package com.pulse.agent.instrumentation;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class DispatcherServletAdvice {

    private DispatcherServletAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnterAdvice(@Advice.Origin("#t.#m") String origin,
                                     @Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] args) {
        Object request = args != null && args.length > 0 ? args[0] : null;
        Object effectiveRequest = HttpTransactionSupport.prepareRequest(request);
        if (args != null && args.length > 0) {
            args[0] = effectiveRequest;
        }
        HttpTransactionSupport.onEnter(origin, effectiveRequest);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExitAdvice(@Advice.Argument(value = 0, optional = true) Object request,
                                    @Advice.Argument(value = 1, optional = true) Object response,
                                    @Advice.Thrown Throwable thrown) {
        HttpTransactionSupport.onExit(request, response, thrown);
    }

    // Helper entrypoint used by local tests without ByteBuddy weaving.
    public static void onEnter(String origin, Object request) {
        Object effectiveRequest = HttpTransactionSupport.prepareRequest(request);
        HttpTransactionSupport.onEnter(origin, effectiveRequest);
    }

    // Helper entrypoint used by local tests without ByteBuddy weaving.
    public static void onExit(Object request, Object response, Throwable thrown) {
        HttpTransactionSupport.onExit(request, response, thrown);
    }
}
