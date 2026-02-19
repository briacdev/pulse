package com.pulse.agent;

import com.pulse.app.PulseApplication;
import com.pulse.app.core.PulseConfig;
import com.pulse.app.core.PulseRuntime;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PulseAgent {

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private PulseAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        PulseConfig config = PulseConfig.fromAgentArgs(agentArgs);
        PulseRuntime.initialize(config);

        PulseInstrumentationInstaller.install(instrumentation);
        startServer(config);
    }

    private static void startServer(PulseConfig config) {
        Thread serverThread = new Thread(() -> new SpringApplicationBuilder(PulseApplication.class)
                .properties(Map.of(
                        "server.port", String.valueOf(config.port()),
                        "server.address", config.bindAddress(),
                        "spring.main.banner-mode", "off",
                        "spring.application.admin.enabled", "false",
                        "spring.application.admin.jmx-name", "org.springframework.boot:type=Admin,name=PulseApplication",
                        "spring.jmx.enabled", "false",
                        "management.endpoints.jmx.exposure.include", "",
                        "spring.autoconfigure.exclude",
                        String.join(",",
                                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
                                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                                "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
                                "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"
                        )
                ))
                .run(), "pulse-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }
}
