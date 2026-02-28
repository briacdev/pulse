package com.pulse.agent.instrumentation;

import com.pulse.app.core.SqlParser;
import com.pulse.app.model.SqlType;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class PreparedStatementRegistry {
    private static final Map<Object, SqlMeta> SQL_BY_STATEMENT = Collections.synchronizedMap(new WeakHashMap<>());

    public static void register(Object statement, String sql) {
        if (statement == null || sql == null || sql.isBlank()) {
            return;
        }
        String normalized = SqlParser.normalize(sql);
        SQL_BY_STATEMENT.put(statement, new SqlMeta(normalized, SqlParser.detectType(normalized)));
    }

    public static SqlMeta find(Object statement) {
        return SQL_BY_STATEMENT.get(statement);
    }

    public record SqlMeta(String normalizedSql, SqlType type) {
    }
}
