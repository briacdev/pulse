package com.pulse.agent.instrumentation;

import lombok.RequiredArgsConstructor;
import net.bytebuddy.asm.Advice;

@RequiredArgsConstructor
public class PrepareStatementAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Argument(0) String sql,
                              @Advice.Return Object statement) {
        PreparedStatementRegistry.register(statement, sql);
    }
}
