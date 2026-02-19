package com.pulse.app.core;

import com.pulse.app.model.http.StackFrameStat;
import com.pulse.app.model.http.StackSample;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HttpStackProfilerService {

    private final Map<String, ActiveProfile> activeById = new ConcurrentHashMap<>();
    private final Map<String, StackProfile> profileBySpanId = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sampler;

    public HttpStackProfilerService() {
        this.sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pulse-http-stack-sampler");
            thread.setDaemon(true);
            return thread;
        });
        this.sampler.scheduleAtFixedRate(this::sampleActive, 25, 25, TimeUnit.MILLISECONDS);
    }

    public Handle start(Thread thread) {
        String id = UUID.randomUUID().toString();
        activeById.put(id, new ActiveProfile(thread));
        return new Handle(id);
    }

    public StackProfile finishAndStore(String spanId, Handle handle) {
        if (spanId == null || spanId.isBlank() || handle == null) {
            return null;
        }
        ActiveProfile active = activeById.remove(handle.id());
        if (active == null) {
            return null;
        }
        StackProfile profile = active.toProfile();
        profileBySpanId.put(spanId, profile);
        return profile;
    }

    public StackProfile popBySpanId(String spanId) {
        if (spanId == null || spanId.isBlank()) {
            return null;
        }
        return profileBySpanId.remove(spanId);
    }

    private void sampleActive() {
        try {
            for (ActiveProfile active : activeById.values()) {
                active.sample();
            }
        } catch (Throwable ignored) {
        }
    }

    public record Handle(String id) {
    }

    public record StackProfile(int totalSamples,
                               List<StackFrameStat> hotspots,
                               List<StackSample> stacks,
                               String hottestFrame) {
    }

    private static final class ActiveProfile {
        private final Thread thread;
        private int totalSamples;
        private final Map<String, Integer> hotspotSamples = new HashMap<>();
        private final Map<String, Integer> stackSamples = new HashMap<>();

        private ActiveProfile(Thread thread) {
            this.thread = thread;
        }

        private synchronized void sample() {
            StackTraceElement[] frames = thread.getStackTrace();
            if (frames == null || frames.length == 0) {
                return;
            }
            totalSamples++;

            String hotspot = findHotspot(frames);
            hotspotSamples.merge(hotspot, 1, Integer::sum);

            String stack = buildStack(frames);
            stackSamples.merge(stack, 1, Integer::sum);
        }

        private StackProfile toProfile() {
            List<StackFrameStat> hotspots = hotspotSamples.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(40)
                    .map(entry -> new StackFrameStat(
                            entry.getKey(),
                            entry.getValue(),
                            totalSamples == 0 ? 0D : (entry.getValue() * 100D) / totalSamples
                    ))
                    .toList();

            List<StackSample> stacks = stackSamples.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(30)
                    .map(entry -> new StackSample(
                            entry.getKey(),
                            entry.getValue(),
                            totalSamples == 0 ? 0D : (entry.getValue() * 100D) / totalSamples
                    ))
                    .toList();

            String hottest = hotspots.isEmpty() ? null : hotspots.getFirst().frame();
            return new StackProfile(totalSamples, hotspots, stacks, hottest);
        }

        private String findHotspot(StackTraceElement[] frames) {
            for (StackTraceElement frame : frames) {
                String className = frame.getClassName();
                if (className.startsWith("java.lang.Thread")
                        || className.startsWith("jdk.internal")
                        || className.startsWith("sun.")) {
                    continue;
                }
                return compact(frame);
            }
            return compact(frames[0]);
        }

        private String buildStack(StackTraceElement[] frames) {
            StringBuilder builder = new StringBuilder();
            int limit = Math.min(frames.length, 24);
            int appended = 0;

            for (int i = 0; i < limit; i++) {
                StackTraceElement frame = frames[i];
                String className = frame.getClassName();
                if (className.startsWith("java.lang.Thread")) {
                    continue;
                }
                if (appended > 0) {
                    builder.append(" <- ");
                }
                builder.append(compact(frame));
                appended++;
            }

            return builder.isEmpty() ? "n/a" : builder.toString();
        }

        private String compact(StackTraceElement frame) {
            return frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber();
        }
    }
}
