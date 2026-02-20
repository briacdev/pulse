package com.pulse.agent;

import com.pulse.app.PulseApplication;
import com.pulse.app.core.PulseConfig;
import com.pulse.app.core.PulseRuntime;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

public final class PulseAgent {

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile PortProbe portProbe = PulseAgent::defaultProbe;

    private PulseAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        makeAgentClassesVisible(instrumentation);

        PulseConfig config = PulseConfig.fromAgentArgs(agentArgs);
        PulseRuntime.initialize(config);
        ensurePortAvailable(config);
        PulseInstrumentationInstaller.install(instrumentation);
        startServer(config);
    }

    static void ensurePortAvailable(PulseConfig config) {
        PortProbe probe = portProbe;
        try {
            probe.assertAvailable(config.bindAddress(), config.port());
        } catch (IOException error) {
            throw new IllegalStateException("Pulse cannot start on " + config.bindAddress() + ":" + config.port()
                    + " because the port is already in use.", error);
        }
    }

    static void setPortProbeForTests(PortProbe probe) {
        portProbe = probe == null ? PulseAgent::defaultProbe : probe;
    }

    private static void defaultProbe(String bindAddress, int port) throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port));
        }
    }

    @FunctionalInterface
    interface PortProbe {
        void assertAvailable(String bindAddress, int port) throws IOException;
    }

    private static void startServer(PulseConfig config) {
        Thread serverThread = new Thread(() -> {
            SpringApplication app = new SpringApplication(PulseApplication.class);
            app.setBannerMode(Banner.Mode.OFF);
            app.setLogStartupInfo(false);
            app.setListeners(List.of());
            app.setDefaultProperties(Map.of(
                    "server.port", String.valueOf(config.port()),
                    "server.address", config.bindAddress(),
                    "spring.main.banner-mode", "off",
                    "spring.devtools.restart.enabled", "false",
                    "spring.devtools.livereload.enabled", "false",
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
            ));
            app.run();
        }, "pulse-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private static void makeAgentClassesVisible(Instrumentation instrumentation) {
        try {
            CodeSource codeSource = PulseAgent.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return;
            }
            File file = new File(codeSource.getLocation().toURI());
            if (!file.isFile() || !file.getName().endsWith(".jar")) {
                return;
            }
            instrumentation.appendToSystemClassLoaderSearch(new JarFile(file));
        } catch (URISyntaxException ignored) {
        } catch (Throwable error) {
            System.err.println("[pulse-agent] failed to append agent jar to system classloader: " + error.getMessage());
        }
    }
}
