package com.pulse.app.core;

import com.pulse.app.model.SqlEvent;
import com.pulse.app.model.SqlSnapshot;
import com.pulse.app.model.SqlStatus;
import com.pulse.app.model.SqlType;
import com.pulse.app.model.http.HttpRequestEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SqlEndpointCorrelationServiceTest {

    @Test
    void shouldCorrelateByTraceIdWhenSqlContextIsMissing() {
        long now = System.currentTimeMillis();
        SqlEvent sql = new SqlEvent(
                now,
                42,
                SqlType.SELECT,
                "h2",
                SqlStatus.OK,
                null,
                "select * from users where id=?",
                "http-nio-8080-exec-1",
                null,
                null,
                null,
                "trace-1"
        );

        HttpRequestEvent http = new HttpRequestEvent(
                "trace-1",
                now + 50,
                80,
                "GET /users/1",
                "/users/{id}",
                200,
                "trace-1",
                "http-nio-8080-exec-1",
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null
        );

        SqlSnapshot correlated = SqlEndpointCorrelationService.correlate(
                new SqlSnapshot(now, 1, List.of(sql), List.of(), List.of(), List.of()),
                List.of(http)
        );

        SqlEvent enriched = correlated.recent().getFirst();
        assertEquals("GET /users/1", enriched.endpoint());
        assertEquals("/users/{id}", enriched.handler());
        assertEquals(200, enriched.httpStatus());
        assertEquals("trace-1", enriched.traceId());
    }

    @Test
    void shouldFallbackToThreadAndTimeWhenTraceIdIsMissing() {
        long base = System.currentTimeMillis();
        SqlEvent sql = new SqlEvent(
                base,
                30,
                SqlType.UPDATE,
                "h2",
                SqlStatus.OK,
                null,
                "update users set active=? where id=?",
                "http-nio-8080-exec-2",
                null,
                null,
                null,
                null
        );

        HttpRequestEvent http = new HttpRequestEvent(
                "http-2",
                base + 80,
                90,
                "PUT /users/1",
                "/users/{id}",
                204,
                "trace-2",
                "http-nio-8080-exec-2",
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null
        );

        SqlSnapshot correlated = SqlEndpointCorrelationService.correlate(
                new SqlSnapshot(base, 1, List.of(sql), List.of(), List.of(), List.of()),
                List.of(http)
        );

        SqlEvent enriched = correlated.recent().getFirst();
        assertEquals("PUT /users/1", enriched.endpoint());
        assertEquals("/users/{id}", enriched.handler());
        assertEquals(204, enriched.httpStatus());
        assertEquals("trace-2", enriched.traceId());
    }

    @Test
    void shouldKeepSqlEventUnchangedWhenNoMatchExists() {
        long base = System.currentTimeMillis();
        SqlEvent sql = new SqlEvent(
                base,
                14,
                SqlType.SELECT,
                "h2",
                SqlStatus.OK,
                null,
                "select 1",
                "pool-1-thread-1",
                null,
                null,
                null,
                null
        );

        HttpRequestEvent http = new HttpRequestEvent(
                "http-x",
                base + 100_000,
                12,
                "GET /x",
                "/x",
                200,
                "trace-x",
                "http-nio-8080-exec-9",
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null
        );

        SqlSnapshot correlated = SqlEndpointCorrelationService.correlate(
                new SqlSnapshot(base, 1, List.of(sql), List.of(), List.of(), List.of()),
                List.of(http)
        );

        SqlEvent enriched = correlated.recent().getFirst();
        assertNull(enriched.endpoint());
        assertNull(enriched.handler());
        assertNull(enriched.httpStatus());
        assertNull(enriched.traceId());
    }
}
