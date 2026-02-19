package com.pulse.app.model;

import java.util.List;

public record SqlSnapshot(
        long generatedAt,
        long totalEvents,
        List<SqlEvent> recent,
        List<SqlAggregate> slowest,
        List<SqlAggregate> mostFrequent,
        List<TimelinePoint> timeline
) {
}
