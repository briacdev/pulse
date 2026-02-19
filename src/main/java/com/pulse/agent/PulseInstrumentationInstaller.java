package com.pulse.agent;

import com.pulse.agent.instrumentation.DispatcherServletAdvice;
import com.pulse.agent.instrumentation.PrepareStatementAdvice;
import com.pulse.agent.instrumentation.StatementExecutionAdvice;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

public final class PulseInstrumentationInstaller {

    private static final AtomicLong DISCOVERED = new AtomicLong();
    private static final AtomicLong TRANSFORMED = new AtomicLong();
    private static final AtomicLong IGNORED = new AtomicLong();
    private static final AtomicLong ERRORS = new AtomicLong();
    private static final AtomicLong HTTP_TRANSFORMED = new AtomicLong();
    private static final Object HTTP_TYPES_LOCK = new Object();
    private static final ArrayDeque<String> HTTP_TYPES = new ArrayDeque<>();
    private static volatile String lastError = "";

    private PulseInstrumentationInstaller() {
    }

    public static void install(Instrumentation instrumentation) {
        AgentBuilder builder = new AgentBuilder.Default()
                .ignore(nameContains("net.bytebuddy.")
                        .or(nameContains("com.pulse.")));

        builder = builder
                .type(hasSuperType(named("java.sql.Connection")).and(not(isInterface())))
                .transform((builder1, typeDescription, classLoader, module, protectionDomain) -> builder1
                        .visit(Advice.to(PrepareStatementAdvice.class).on(
                                named("prepareStatement").and(takesArguments(1)).and(takesArgument(0, String.class))
                                        .or(named("prepareCall").and(takesArguments(1)).and(takesArgument(0, String.class)))
                        )));

        builder = builder
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

        builder = builder
                .type(named("org.springframework.web.servlet.DispatcherServlet"))
                .transform((builder1, typeDescription, classLoader, module, protectionDomain) -> builder1
                        .visit(Advice.to(DispatcherServletAdvice.class).on(named("doDispatch")
                                .or(named("doService"))
                                .or(named("service")))));

        builder = builder
                .type(named("org.springframework.web.servlet.FrameworkServlet"))
                .transform((builder1, typeDescription, classLoader, module, protectionDomain) -> builder1
                        .visit(Advice.to(DispatcherServletAdvice.class).on(named("processRequest")
                                .or(named("doService"))
                                .or(named("service")))));

        builder = builder
                .type(hasSuperType(named("jakarta.servlet.http.HttpServlet")).and(not(isInterface())))
                .transform((builder1, typeDescription, classLoader, module, protectionDomain) -> builder1
                        .visit(Advice.to(DispatcherServletAdvice.class).on(named("service")
                                .or(named("doService")))));

        builder = builder
                .type(named("org.apache.catalina.core.ApplicationFilterChain"))
                .transform((builder1, typeDescription, classLoader, module, protectionDomain) -> builder1
                        .visit(Advice.to(DispatcherServletAdvice.class).on(named("doFilter"))));

        AgentBuilder.Listener listener = new AgentBuilder.Listener() {
            @Override
            public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
                DISCOVERED.incrementAndGet();
            }

            @Override
            public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded, DynamicType dynamicType) {
                TRANSFORMED.incrementAndGet();
                String typeName = typeDescription.getName();
                if (isHttpTarget(typeName)) {
                    HTTP_TRANSFORMED.incrementAndGet();
                    rememberHttpType(typeName);
                }
            }

            @Override
            public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded) {
                IGNORED.incrementAndGet();
            }

            @Override
            public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
                ERRORS.incrementAndGet();
                lastError = typeName + ": " + throwable.getClass().getSimpleName() + " - " + String.valueOf(throwable.getMessage());
            }

            @Override
            public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
            }
        };

        builder.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .with(listener)
                .installOn(instrumentation);
    }

    public static Map<String, Object> debugCounters() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("agentDiscovered", DISCOVERED.get());
        output.put("agentTransformed", TRANSFORMED.get());
        output.put("agentIgnored", IGNORED.get());
        output.put("agentErrors", ERRORS.get());
        output.put("agentHttpTransformed", HTTP_TRANSFORMED.get());
        output.put("agentHttpTypes", recentHttpTypes());
        output.put("agentLastError", lastError);
        return output;
    }

    private static void rememberHttpType(String typeName) {
        synchronized (HTTP_TYPES_LOCK) {
            HTTP_TYPES.addLast(typeName);
            while (HTTP_TYPES.size() > 24) {
                HTTP_TYPES.removeFirst();
            }
        }
    }

    private static List<String> recentHttpTypes() {
        synchronized (HTTP_TYPES_LOCK) {
            return new ArrayList<>(HTTP_TYPES);
        }
    }

    private static boolean isHttpTarget(String typeName) {
        return "org.springframework.web.servlet.DispatcherServlet".equals(typeName)
                || "org.springframework.web.servlet.FrameworkServlet".equals(typeName)
                || "jakarta.servlet.http.HttpServlet".equals(typeName)
                || "org.apache.catalina.core.ApplicationFilterChain".equals(typeName)
                || typeName.startsWith("org.springframework.web.servlet.");
    }
}
