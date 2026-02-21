package com.pulse.app.core;

import com.pulse.app.model.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;

public class SqlCollectorService {

    private static final int MAX_EVENTS = 20_000;

    private final PulseConfig config;
    private final Deque<SqlEvent> events = new ConcurrentLinkedDeque<>();

    public SqlCollectorService(PulseConfig config) {
        this.config = config;
    }

    public void record(String datasource,
                       String normalizedSql,
                       SqlType sqlType,
                       long durationMs,
                       Throwable error,
                       String threadName,
                       HttpRequestContext context) {
        record(datasource, normalizedSql, sqlType, durationMs, error == null ? null : error.getClass().getSimpleName(), threadName, context);
    }

    public void record(String datasource,
                       String normalizedSql,
                       SqlType sqlType,
                       long durationMs,
                       String exceptionType,
                       String threadName,
                       HttpRequestContext context) {
        if (normalizedSql == null || normalizedSql.isBlank()) {
            return;
        }

        boolean isSlow = durationMs >= config.slowQueryThresholdMs();
        if (!isSlow && config.sampleRate() < 1.0D && ThreadLocalRandom.current().nextDouble() > config.sampleRate()) {
            return;
        }

        SqlStatus status = exceptionType == null ? SqlStatus.OK : SqlStatus.ERROR;
        SqlEvent event = new SqlEvent(
                Instant.now().toEpochMilli(),
                durationMs,
                sqlType,
                datasource,
                status,
                exceptionType,
                normalizedSql,
                threadName,
                context == null ? null : context.endpoint(),
                context == null ? null : context.handler(),
                context == null ? null : context.httpStatus(),
                context == null ? null : context.traceId()
        );

        events.addLast(event);
        trim();
    }

    public SqlSnapshot snapshot() {
        trim();
        List<SqlEvent> copy = new ArrayList<>(events);
        copy.sort(Comparator.comparingLong(SqlEvent::timestamp).reversed());

        List<SqlEvent> recent = copy.stream().limit(300).toList();
        List<SqlAggregate> aggregates = aggregate(copy);

        List<SqlAggregate> slowest = aggregates.stream()
                .sorted(Comparator.comparingLong(SqlAggregate::maxLatencyMs).reversed())
                .limit(20)
                .toList();

        List<SqlAggregate> mostFrequent = aggregates.stream()
                .sorted(Comparator.comparingLong(SqlAggregate::count).reversed())
                .limit(20)
                .toList();

        return new SqlSnapshot(
                Instant.now().toEpochMilli(),
                copy.size(),
                recent,
                slowest,
                mostFrequent,
                buildTimeline(copy)
        );
    }

    private List<SqlAggregate> aggregate(List<SqlEvent> source) {
        Map<String, AggMutable> agg = new HashMap<>();
        for (SqlEvent event : source) {
            String key = event.normalizedSql();
            AggMutable mutable = agg.computeIfAbsent(key, ignored -> new AggMutable(event.sqlType()));
            mutable.count++;
            mutable.sumLatency += event.durationMs();
            mutable.maxLatency = Math.max(mutable.maxLatency, event.durationMs());
            if (event.status() == SqlStatus.ERROR) {
                mutable.errorCount++;
            }
        }

        List<SqlAggregate> output = new ArrayList<>();
        for (Map.Entry<String, AggMutable> entry : agg.entrySet()) {
            AggMutable mutable = entry.getValue();
            output.add(new SqlAggregate(
                    entry.getKey(),
                    mutable.type,
                    mutable.count,
                    mutable.errorCount,
                    mutable.count == 0 ? 0 : (double) mutable.sumLatency / mutable.count,
                    mutable.maxLatency
            ));
        }
        return output;
    }

    private List<TimelinePoint> buildTimeline(List<SqlEvent> source) {
        long bucketMs = 10_000L;
        Map<Long, Bucket> buckets = new HashMap<>();

        for (SqlEvent event : source) {
            long bucket = (event.timestamp() / bucketMs) * bucketMs;
            Bucket value = buckets.computeIfAbsent(bucket, ignored -> new Bucket());
            value.count++;
            value.latency += event.durationMs();
            if (event.status() == SqlStatus.ERROR) {
                value.errorCount++;
            }
        }

        return buckets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    Bucket b = entry.getValue();
                    double avg = b.count == 0 ? 0D : (double) b.latency / b.count;
                    return new TimelinePoint(entry.getKey(), avg, b.count, b.errorCount);
                })
                .toList();
    }

    private void trim() {
        long keepAfter = Instant.now().toEpochMilli() - config.retentionMs();

        while (true) {
            SqlEvent head = events.peekFirst();
            if (head == null || head.timestamp() >= keepAfter) {
                break;
            }
            events.pollFirst();
        }

        while (events.size() > MAX_EVENTS) {
            events.pollFirst();
        }
    }

    private static final class AggMutable {
        private final SqlType type;
        private long count;
        private long errorCount;
        private long sumLatency;
        private long maxLatency;

        private AggMutable(SqlType type) {
            this.type = Objects.requireNonNullElse(type, SqlType.OTHER);
        }
    }

    private static final class Bucket {
        private long count;
        private long latency;
        private long errorCount;
    }
}
