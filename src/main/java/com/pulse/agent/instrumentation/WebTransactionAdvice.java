package com.pulse.agent.instrumentation;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class WebTransactionAdvice {

    private WebTransactionAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnterAdvice(@Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] args) {
        HttpAdviceBridge.onEnter(args);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExitAdvice(@Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] args,
                                    @Advice.Thrown Throwable thrown) {
        HttpAdviceBridge.onExit(args, thrown);
    }

    // Helper entrypoint used by local tests without ByteBuddy weaving.
    public static void onEnter(String origin, Object request) {
        HttpAdviceBridge.onEnter(request);
    }

    // Helper entrypoint used by local tests without ByteBuddy weaving.
    public static void onExit(Object request, Object response, Throwable thrown) {
        HttpAdviceBridge.onExit(request, response, thrown);
    }
}
