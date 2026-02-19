package com.pulse.agent.instrumentation;

import com.pulse.app.core.HttpContextHolder;
import com.pulse.app.core.PulseOpenTelemetry;
import com.pulse.app.core.PulseRuntime;
import com.pulse.app.core.SqlParser;
import com.pulse.app.model.HttpRequestContext;
import com.pulse.app.model.SqlType;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

public class StatementExecutionAdvice {

    private static final AttributeKey<String> DB_STATEMENT = AttributeKey.stringKey("db.statement");
    private static final AttributeKey<String> DB_OPERATION = AttributeKey.stringKey("db.operation");
    private static final AttributeKey<String> DB_NAMESPACE = AttributeKey.stringKey("db.namespace");
    private static final AttributeKey<String> THREAD_NAME = AttributeKey.stringKey("thread.name");
    private static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> PULSE_ENDPOINT = AttributeKey.stringKey("pulse.endpoint");
    private static final AttributeKey<String> PULSE_HANDLER = AttributeKey.stringKey("pulse.handler");
    private static final AttributeKey<String> PULSE_TRACE_ID = AttributeKey.stringKey("pulse.trace_id");

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

        if (normalized == null) {
            return new SqlExecutionState(System.nanoTime(), null, type, null, null);
        }

        HttpRequestContext context = HttpContextHolder.get();
        String datasource = statement == null ? "jdbc" : statement.getClass().getName();

        Span span = null;
        Scope scope = null;
        try {
            span = PulseOpenTelemetry.tracer()
                    .spanBuilder("db.query")
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(DB_STATEMENT, normalized)
                    .setAttribute(DB_OPERATION, type.name())
                    .setAttribute(DB_NAMESPACE, datasource)
                    .setAttribute(THREAD_NAME, Thread.currentThread().getName())
                    .startSpan();

            if (context != null) {
                if (context.endpoint() != null) {
                    span.setAttribute(PULSE_ENDPOINT, context.endpoint());
                }
                if (context.handler() != null) {
                    span.setAttribute(PULSE_HANDLER, context.handler());
                }
                if (context.traceId() != null) {
                    span.setAttribute(PULSE_TRACE_ID, context.traceId());
                }
            }
            scope = span.makeCurrent();
        } catch (Throwable ignored) {
            span = null;
            scope = null;
        }
        return new SqlExecutionState(System.nanoTime(), normalized, type, span, scope);
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
        try {
            if (state.span() != null) {
                state.span().setAttribute(AttributeKey.longKey("pulse.duration_ms"), durationMs);
                if (thrown != null) {
                    state.span().setStatus(StatusCode.ERROR);
                    state.span().setAttribute(EXCEPTION_TYPE, exceptionType);
                    state.span().recordException(thrown);
                }
            }
        } finally {
            if (state.scope() != null) {
                state.scope().close();
            }
            if (state.span() != null) {
                state.span().end();
            }
        }

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
                                    SqlType sqlType,
                                    Span span,
                                    Scope scope) {
    }
}
