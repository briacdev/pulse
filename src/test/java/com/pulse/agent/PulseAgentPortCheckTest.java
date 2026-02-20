package com.pulse.agent;

import com.pulse.app.core.PulseConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PulseAgentPortCheckTest {

    @AfterEach
    void resetProbe() {
        PulseAgent.setPortProbeForTests(null);
    }

    @Test
    void ensurePortAvailableShouldFailWhenProbeReportsBusyPort() {
        PulseAgent.setPortProbeForTests((bind, port) -> {
            throw new IOException("busy");
        });

        PulseConfig config = new PulseConfig(17321, 60_000, 1.0, 100, 200, "127.0.0.1", "test-app");
        assertThrows(IllegalStateException.class, () -> PulseAgent.ensurePortAvailable(config));
    }

    @Test
    void ensurePortAvailableShouldPassWhenProbeReportsAvailablePort() {
        PulseAgent.setPortProbeForTests((bind, port) -> {
        });

        PulseConfig config = new PulseConfig(17329, 60_000, 1.0, 100, 200, "127.0.0.1", "test-app");
        assertDoesNotThrow(() -> PulseAgent.ensurePortAvailable(config));
    }
}
