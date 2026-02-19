package com.pulse.app.core;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

public final class PulseOpenTelemetry {

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    private static final AttributeKey<String> SERVICE_NAMESPACE = AttributeKey.stringKey("service.namespace");

    private static volatile OpenTelemetrySdk sdk;
    private static volatile Tracer tracer;

    private PulseOpenTelemetry() {
    }

    public static synchronized void initialize(PulseConfig config) {
        shutdown();

        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
                SERVICE_NAME, config.appName(),
                SERVICE_NAMESPACE, "pulse-agent"
        )));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(SimpleSpanProcessor.create(new PulseSpanExporter()))
                .build();

        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        tracer = sdk.getTracer("com.pulse.agent", "1.0.0");
    }

    public static Tracer tracer() {
        Tracer current = tracer;
        if (current == null) {
            initialize(PulseRuntime.getConfig());
            current = tracer;
        }
        return current;
    }

    public static synchronized void shutdown() {
        if (sdk != null) {
            sdk.getSdkTracerProvider().shutdown();
            sdk = null;
            tracer = null;
        }
    }
}
