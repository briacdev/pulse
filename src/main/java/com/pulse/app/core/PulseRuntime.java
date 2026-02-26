package com.pulse.app.core;

public final class PulseRuntime {
    private static volatile PulseConfig config = PulseConfig.fromAgentArgs(null);
    private static volatile SqlCollectorService collector = new SqlCollectorService(config);
    private static volatile HttpPerfCollectorService httpCollector = new HttpPerfCollectorService(config);
    private static volatile JvmMetricsService jvmMetrics = new JvmMetricsService(config);
    private static volatile String monitoredAppName = config.appName();
    private static volatile HttpStackProfilerService httpStackProfiler = new HttpStackProfilerService();

    private PulseRuntime() {
    }

    public static void initialize(PulseConfig pulseConfig) {
        config = pulseConfig;
        collector = new SqlCollectorService(pulseConfig);
        httpCollector = new HttpPerfCollectorService(pulseConfig);
        jvmMetrics = new JvmMetricsService(pulseConfig);
        monitoredAppName = pulseConfig.appName();
        refreshMonitoredAppName();
        httpStackProfiler = new HttpStackProfilerService();
    }
    public static PulseConfig getConfig() {
        return config;
    }
    public static SqlCollectorService getCollector() {
        return collector;
    }
    public static HttpPerfCollectorService getHttpCollector() {
        return httpCollector;
    }
    public static JvmMetricsService getJvmMetrics() {
        return jvmMetrics;
    }
    public static String monitoredAppName() {
        return monitoredAppName;
    }
    public static void refreshMonitoredAppName() {
        String detected = PulseConfig.detectRunningAppName();
        if (detected != null && !detected.isBlank()) {
            monitoredAppName = detected;
        }
    }
    public static HttpStackProfilerService getHttpStackProfiler() {
        return httpStackProfiler;
    }
}
