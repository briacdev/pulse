package com.pulse.agent;

import com.pulse.agent.instrumentation.DispatcherServletAdvice;
import com.pulse.agent.instrumentation.PrepareStatementAdvice;
import com.pulse.agent.instrumentation.StatementExecutionAdvice;
import com.pulse.agent.instrumentation.WebTransactionAdvice;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.*;

public final class PulseInstrumentationInstaller {

    private PulseInstrumentationInstaller() {
    }

    public static void install(Instrumentation instrumentation) {
        AgentBuilder builder = new AgentBuilder.Default()
                .ignore(nameContains("net.bytebuddy.")
                        .or(nameContains("com.pulse.")));

        builder = installSqlPreparationAdvice(builder);
        builder = installSqlExecutionAdvice(builder);
        builder = installDispatcherAdvice(builder);
        builder = installServletAdvice(builder);

        builder.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .installOn(instrumentation);
    }

    private static AgentBuilder installSqlPreparationAdvice(AgentBuilder builder) {
        return builder
                .type(hasSuperType(named("java.sql.Connection")).and(not(isInterface())))
                .transform((builder1, typeDescription, classLoader, module, protectionDomain) -> builder1
                        .visit(Advice.to(PrepareStatementAdvice.class).on(
                                named("prepareStatement").and(takesArguments(1)).and(takesArgument(0, String.class))
                                        .or(named("prepareCall").and(takesArguments(1)).and(takesArgument(0, String.class)))
                        )));
    }

    private static AgentBuilder installSqlExecutionAdvice(AgentBuilder builder) {
        return builder
                .type(hasSuperType(named("java.sql.Statement")).and(not(isInterface())))
                .transform((builder1, typeDescription, classLoader, module, protectionDomain) -> builder1
                        .visit(Advice.to(StatementExecutionAdvice.class).on(
                                named("execute")
                                        .or(named("executeQuery"))
                                        .or(named("executeUpdate"))
                                        .or(named("executeLargeUpdate"))
                                        .or(named("executeBatch"))
                                        .or(named("executeLargeBatch"))
                        )));
    }

    private static AgentBuilder installDispatcherAdvice(AgentBuilder builder) {
        return builder
                .type(named("org.springframework.web.servlet.DispatcherServlet"))
                .transform((builder1, typeDescription, classLoader, module, protectionDomain) -> builder1
                        .visit(Advice.to(DispatcherServletAdvice.class).on(named("doDispatch").and(takesArguments(2)))));
    }

    private static AgentBuilder installServletAdvice(AgentBuilder builder) {
        return builder
                .type(named("jakarta.servlet.http.HttpServlet"))
                .transform((builder1, typeDescription, classLoader, module, protectionDomain) -> builder1
                        .visit(Advice.to(WebTransactionAdvice.class).on(named("service").and(takesArguments(2)))));
    }
}
