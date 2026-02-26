package com.pulse.agent;

import com.pulse.app.api.local.LocalApiServer;
import com.pulse.app.core.PulseConfig;
import com.pulse.app.core.PulseRuntime;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

public final class PulseAgent {

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile PortProbe portProbe = PulseAgent::defaultProbe;
    private static volatile LocalApiServer localApiServer;

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        // Keep instrumentation tolerant to newer classfile versions on recent JDKs.
        System.setProperty("net.bytebuddy.experimental", "true");
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
            throw new IllegalStateException("Pulse cannot start on " + config.bindAddress() + ":" + config.port() + " because the port is already in use.", error);
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

    private static void startServer(PulseConfig config) {
        try {
            localApiServer = new LocalApiServer(config);
            localApiServer.start();
        } catch (IOException error) {
            throw new IllegalStateException("Pulse local API cannot start on "
                    + config.bindAddress()
                    + ":"
                    + config.port(), error);
        }
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

    @FunctionalInterface
    interface PortProbe {
        void assertAvailable(String bindAddress, int port) throws IOException;
    }
}
