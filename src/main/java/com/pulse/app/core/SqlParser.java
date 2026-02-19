package com.pulse.app.core;

import com.pulse.app.model.SqlType;

import java.util.Locale;
import java.util.regex.Pattern;

public final class SqlParser {

    private static final Pattern NUMERIC = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final Pattern SINGLE_QUOTED = Pattern.compile("'([^']|'')*'");
    private static final Pattern MULTISPACE = Pattern.compile("\\s+");

    private SqlParser() {
    }

    public static String normalize(String sql) {
        if (sql == null) {
            return null;
        }
        String compact = MULTISPACE.matcher(sql.trim()).replaceAll(" ");
        compact = SINGLE_QUOTED.matcher(compact).replaceAll("?");
        compact = NUMERIC.matcher(compact).replaceAll("?");
        return compact;
    }

    public static SqlType detectType(String sql) {
        if (sql == null || sql.isBlank()) {
            return SqlType.OTHER;
        }
        String first = sql.trim().split("\\s+")[0].toUpperCase(Locale.ROOT);
        return switch (first) {
            case "SELECT" -> SqlType.SELECT;
            case "INSERT" -> SqlType.INSERT;
            case "UPDATE" -> SqlType.UPDATE;
            case "DELETE" -> SqlType.DELETE;
            case "CREATE", "ALTER", "DROP", "TRUNCATE" -> SqlType.DDL;
            default -> SqlType.OTHER;
        };
    }
}
