package com.pulse.app.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PulseConfigAppNameTest {

    private final String previousSpringName = System.getProperty("spring.application.name");
    private final String previousCommand = System.getProperty("sun.java.command");

    @AfterEach
    void restoreSystemProperties() {
        setOrClear("spring.application.name", previousSpringName);
        setOrClear("sun.java.command", previousCommand);
    }

    @Test
    void shouldDetectRunningAppNameFromJarCommand() {
        System.clearProperty("spring.application.name");
        System.setProperty("sun.java.command", "/opt/apps/orders-service-1.4.2.jar --server.port=8080");

        assertEquals("orders-service-1.4.2", PulseConfig.detectRunningAppName());
    }

    @Test
    void shouldPreferDetectedRunningAppOverExplicitAgentAppName() {
        System.clearProperty("spring.application.name");
        System.setProperty("sun.java.command", "billing-api.jar");

        PulseConfig config = PulseConfig.fromAgentArgs("appName=MonApp");
        assertEquals("billing-api", config.appName());
    }

    @Test
    void shouldUseSpringApplicationNameWhenPresent() {
        System.setProperty("spring.application.name", "inventory-service");
        System.setProperty("sun.java.command", "MonApp.jar");

        assertEquals("inventory-service", PulseConfig.detectRunningAppName());
    }

    private void setOrClear(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
