package com.agentgate.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.execution.domain.Execution;
import com.agentgate.execution.repository.ExecutionRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * An installation from before Flyway: tables made by Hibernate's ddl-auto (with an executions
 * status check that predates STOPPED), existing data, and the runtime's LangGraph tables.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExistingDatabaseMigrationTest {

    private static final PostgreSQLContainer POSTGRES = PostgresSchema.start();

    static {
        createLegacySchema();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresSchema.register(registry, POSTGRES);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private AgentRepository agentRepository;

    private static void createLegacySchema() {
        String baseline;
        try (InputStream in = ExistingDatabaseMigrationTest.class.getResourceAsStream("/db/migration/V1__baseline.sql")) {
            baseline = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // What ddl-auto left behind: the same tables, but the status check from before STOPPED existed.
        String legacy = baseline.replace("'COMPLETED','STOPPED','FAILED'", "'COMPLETED','FAILED'");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(legacy);
            statement.execute("create table checkpoints (thread_id text primary key)");
            statement.execute("insert into agents (agent_id, name, api_key_hash) values ('old-agent', 'Old', 'h')");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void upgradesInPlaceKeepingDataAndAcceptingNewValues() {
        List<String> applied = jdbc.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class);
        assertThat(applied).containsExactly("0", "1", "2");
        assertThat(agentRepository.findByAgentId("old-agent")).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from checkpoints", Integer.class)).isZero();

        Execution stopped = new Execution("legacy-1", "wf", 1, "task");
        stopped.stopped("report: blocked by AgentGate", Instant.now());
        executionRepository.saveAndFlush(stopped);

        assertThat(executionRepository.findByExecutionId("legacy-1").orElseThrow().getStatus().name())
                .isEqualTo("STOPPED");
    }
}
