package com.pulse.app.core;

import com.pulse.app.model.http.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class HttpPerfCollectorService {

    private static final int MAX_EVENTS = 20_000;

    private final PulseConfig config;
    private final Deque<HttpRequestEvent> events = new ConcurrentLinkedDeque<>();
    private final Map<String, HttpTraceDetail> detailsById = new ConcurrentHashMap<>();

    public HttpPerfCollectorService(PulseConfig config) {
        this.config = config;
    }

    public void record(HttpRequestEvent event,
                       Integer totalSamples,
                       List<StackFrameStat> hotspots,
                       List<StackSample> stacks) {
        if (event == null) {
            return;
        }
        events.addLast(event);
        detailsById.put(event.id(), new HttpTraceDetail(
                event,
                totalSamples == null ? 0 : totalSamples,
                hotspots == null ? List.of() : hotspots,
                stacks == null ? List.of() : stacks
        ));
        trim();
    }

    public HttpSnapshot snapshot() {
        trim();
        List<HttpRequestEvent> copy = new ArrayList<>(events);
        copy.sort(Comparator.comparingLong(HttpRequestEvent::timestamp).reversed());

        List<HttpRequestEvent> recent = copy.stream().limit(300).toList();
        List<HttpEndpointAggregate> endpoints = aggregateByEndpoint(copy);
        List<HttpRequestEvent> slowCalls = copy.stream()
                .filter(HttpRequestEvent::slow)
                .sorted(Comparator.comparingLong(HttpRequestEvent::durationMs).reversed())
                .limit(100)
                .toList();

        return new HttpSnapshot(
                Instant.now().toEpochMilli(),
                copy.size(),
                recent,
                endpoints,
                slowCalls
        );
    }

    public HttpTraceDetail traceDetail(String id) {
        trim();
        return detailsById.get(id);
    }

    public List<HttpRequestEvent> allEvents() {
        trim();
        return new ArrayList<>(events);
    }

    private List<HttpEndpointAggregate> aggregateByEndpoint(List<HttpRequestEvent> source) {
        Map<String, EndpointMutable> grouped = new HashMap<>();

        for (HttpRequestEvent event : source) {
            String endpointKey = event.endpoint() == null || event.endpoint().isBlank() ? "(unknown endpoint)" : event.endpoint();
            EndpointMutable endpoint = grouped.computeIfAbsent(endpointKey, ignored -> new EndpointMutable());
            endpoint.count++;
            endpoint.sum += event.durationMs();
            endpoint.max = Math.max(endpoint.max, event.durationMs());
            endpoint.durations.add(event.durationMs());
            if (event.errorType() != null || (event.httpStatus() != null && event.httpStatus() >= 500)) {
                endpoint.errors++;
            }
            if (event.slow()) {
                endpoint.slow++;
            }
        }

        List<HttpEndpointAggregate> output = new ArrayList<>();
        for (Map.Entry<String, EndpointMutable> entry : grouped.entrySet()) {
            EndpointMutable data = entry.getValue();
            data.durations.sort(Long::compareTo);
            long p95 = percentile(data.durations, 95);
            output.add(new HttpEndpointAggregate(
                    entry.getKey(),
                    data.count,
                    data.errors,
                    data.slow,
                    data.count == 0 ? 0D : (double) data.sum / data.count,
                    p95,
                    data.max
            ));
        }

        return output.stream()
                .sorted(Comparator.comparingLong(HttpEndpointAggregate::maxLatencyMs).reversed())
                .limit(150)
                .toList();
    }

    private long percentile(List<Long> sorted, int percent) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int index = Math.min(sorted.size() - 1, (int) Math.ceil((percent / 100D) * sorted.size()) - 1);
        return sorted.get(Math.max(0, index));
    }

    private void trim() {
        long keepAfter = Instant.now().toEpochMilli() - config.retentionMs();

        while (true) {
            HttpRequestEvent head = events.peekFirst();
            if (head == null || head.timestamp() >= keepAfter) {
                break;
            }
            HttpRequestEvent removed = events.pollFirst();
            if (removed != null) {
                detailsById.remove(removed.id());
            }
        }

        while (events.size() > MAX_EVENTS) {
            HttpRequestEvent removed = events.pollFirst();
            if (removed != null) {
                detailsById.remove(removed.id());
            }
        }
    }

    private static final class EndpointMutable {
        private final List<Long> durations = new ArrayList<>();
        private long count;
        private long errors;
        private long slow;
        private long sum;
        private long max;
    }
}
