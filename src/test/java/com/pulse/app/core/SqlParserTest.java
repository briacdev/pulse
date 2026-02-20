package com.pulse.app.core;

import com.pulse.app.model.SqlType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlParserTest {

    @Test
    void normalizeShouldMaskLiteralsAndNumbers() {
        String sql = "SELECT * FROM users WHERE email='john@doe.com' AND id=42";
        String normalized = SqlParser.normalize(sql);
        assertEquals("SELECT * FROM users WHERE email=? AND id=?", normalized);
    }

    @Test
    void detectTypeShouldReturnExpectedSqlType() {
        assertEquals(SqlType.SELECT, SqlParser.detectType("select 1"));
        assertEquals(SqlType.INSERT, SqlParser.detectType("INSERT INTO x VALUES (1)"));
        assertEquals(SqlType.UPDATE, SqlParser.detectType(" UPDATE x set a=1"));
        assertEquals(SqlType.DELETE, SqlParser.detectType("delete from x"));
        assertEquals(SqlType.DDL, SqlParser.detectType("create table x(id int)"));
        assertEquals(SqlType.OTHER, SqlParser.detectType("begin"));
    }
}
