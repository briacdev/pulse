package com.pulse.agent.instrumentation;

import net.bytebuddy.asm.Advice;

public class PrepareStatementAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Argument(0) String sql,
                              @Advice.Return Object statement) {
        PreparedStatementRegistry.register(statement, sql);
    }
}
