package com.pulse.agent.instrumentation;

import com.pulse.app.core.HttpContextHolder;
import com.pulse.app.core.PulseRuntime;
import com.pulse.app.core.SqlParser;
import com.pulse.app.model.HttpRequestContext;
import com.pulse.app.model.SqlType;
import net.bytebuddy.asm.Advice;

public class StatementExecutionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SqlExecutionState onEnter(@Advice.This Object statement,
                                            @Advice.AllArguments Object[] args) {
        String normalized = null;
        SqlType type = SqlType.OTHER;

        if (args != null && args.length > 0 && args[0] instanceof String sql && !sql.isBlank()) {
            normalized = SqlParser.normalize(sql);
            type = SqlParser.detectType(normalized);
        } else {
            PreparedStatementRegistry.SqlMeta meta = PreparedStatementRegistry.find(statement);
            if (meta != null) {
                normalized = meta.normalizedSql();
                type = meta.type();
            }
        }

        return new SqlExecutionState(System.nanoTime(), normalized, type);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.This Object statement,
                              @Advice.Enter SqlExecutionState state,
                              @Advice.Thrown Throwable thrown) {
        if (state == null || state.normalizedSql() == null) {
            return;
        }
        long durationMs = Math.max(0L, (System.nanoTime() - state.startNs()) / 1_000_000L);
        HttpRequestContext context = HttpContextHolder.get();
        String exceptionType = thrown == null ? null : thrown.getClass().getSimpleName();

        PulseRuntime.getCollector().record(
                statement == null ? "jdbc" : statement.getClass().getName(),
                state.normalizedSql(),
                state.sqlType(),
                durationMs,
                exceptionType,
                Thread.currentThread().getName(),
                context
        );
    }

    public record SqlExecutionState(long startNs,
                                    String normalizedSql,
                                    SqlType sqlType) {
    }
}
