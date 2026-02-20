package com.pulse.app.core;

import com.pulse.app.model.HttpRequestContext;
import com.pulse.app.model.SqlSnapshot;
import com.pulse.app.model.SqlStatus;
import com.pulse.app.model.SqlType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlCollectorServiceTest {

    @Test
    void snapshotShouldContainRecentAndAggregates() {
        PulseConfig config = new PulseConfig(17321, 60_000, 1.0, 100, 200, "127.0.0.1", "test-app");
        SqlCollectorService service = new SqlCollectorService(config);

        service.record("h2", "SELECT * FROM users WHERE id=?", SqlType.SELECT, 20, (String) null,
                "main", new HttpRequestContext("GET /users/1", "/users/{id}", 200, "trace-1"));
        service.record("h2", "SELECT * FROM users WHERE id=?", SqlType.SELECT, 80, (String) null,
                "main", new HttpRequestContext("GET /users/1", "/users/{id}", 200, "trace-1"));
        service.record("h2", "UPDATE users SET name=? WHERE id=?", SqlType.UPDATE, 150, "SQLException",
                "main", new HttpRequestContext("PUT /users/1", "/users/{id}", 500, "trace-2"));

        SqlSnapshot snapshot = service.snapshot();

        assertEquals(3, snapshot.totalEvents());
        assertFalse(snapshot.recent().isEmpty());
        assertFalse(snapshot.mostFrequent().isEmpty());
        assertFalse(snapshot.slowest().isEmpty());
        assertTrue(snapshot.recent().stream().anyMatch(event -> event.status() == SqlStatus.ERROR));
        assertTrue(snapshot.mostFrequent().stream().anyMatch(agg -> agg.count() == 2));
    }
}
