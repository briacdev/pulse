package com.pulse.agent.instrumentation;

import com.pulse.app.core.HttpContextHolder;
import com.pulse.app.core.PulseConfig;
import com.pulse.app.core.PulseRuntime;
import com.pulse.app.model.HttpRequestContext;
import com.pulse.app.model.SqlStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlInstrumentationH2IntegrationTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        PulseRuntime.initialize(new PulseConfig(17321, 120_000, 1.0, 100, 100, "127.0.0.1", "test-app"));
        connection = DriverManager.getConnection("jdbc:h2:mem:pulse-it;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table users(id int primary key, name varchar(64))");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        HttpContextHolder.clear();
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void shouldCaptureInsertSelectAndErrorWithHttpContextCorrelation() throws Exception {
        HttpContextHolder.set(new HttpRequestContext("POST /users", "/users", 200, "trace-h2-1"));

        String insertSql = "insert into users(id, name) values(?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
            ps.setInt(1, 1);
            ps.setString(2, "alice");

            PrepareStatementAdvice.onExit(insertSql, ps);
            var state = StatementExecutionAdvice.onEnter(ps, new Object[]{});
            Throwable thrown = null;
            try {
                ps.executeUpdate();
            } catch (Throwable t) {
                thrown = t;
            }
            StatementExecutionAdvice.onExit(ps, state, thrown);
        }

        HttpContextHolder.set(new HttpRequestContext("GET /users/1", "/users/{id}", 200, "trace-h2-2"));
        try (Statement statement = connection.createStatement()) {
            String selectSql = "select * from users where id = 1";
            var state = StatementExecutionAdvice.onEnter(statement, new Object[]{selectSql});
            Throwable thrown = null;
            try {
                statement.executeQuery(selectSql).close();
            } catch (Throwable t) {
                thrown = t;
            }
            StatementExecutionAdvice.onExit(statement, state, thrown);
        }

        HttpContextHolder.set(new HttpRequestContext("GET /boom", "/boom", 500, "trace-h2-3"));
        try (Statement statement = connection.createStatement()) {
            String badSql = "select * from missing_table";
            var state = StatementExecutionAdvice.onEnter(statement, new Object[]{badSql});
            Throwable thrown = null;
            try {
                statement.executeQuery(badSql);
            } catch (Throwable t) {
                thrown = t;
            }
            StatementExecutionAdvice.onExit(statement, state, thrown);
        }

        var snapshot = PulseRuntime.getCollector().snapshot();
        assertTrue(snapshot.totalEvents() >= 3);
        assertTrue(snapshot.recent().stream().anyMatch(event -> "POST /users".equals(event.endpoint())));
        assertTrue(snapshot.recent().stream().anyMatch(event -> "GET /users/1".equals(event.endpoint())));
        assertTrue(snapshot.recent().stream().anyMatch(event -> event.status() == SqlStatus.ERROR));
    }
}
