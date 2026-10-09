package com.agentgate.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.execution.domain.Execution;
import com.agentgate.execution.repository.ExecutionRepository;
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

/** A new installation: Flyway builds the schema, and Hibernate finds it matches the entities. */
@SpringBootTest
@ActiveProfiles("test")
class FreshDatabaseMigrationTest {

    private static final PostgreSQLContainer POSTGRES = PostgresSchema.start();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresSchema.register(registry, POSTGRES);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExecutionRepository executionRepository;

    @Test
    void migratesAnEmptyDatabaseToASchemaMatchingTheEntities() {
        // Starting the context already ran the migrations and Hibernate's validation.
        List<String> applied = jdbc.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class);
        assertThat(applied).isEqualTo(PostgresSchema.shippedVersions());

        Execution stopped = new Execution("fresh-1", "wf", 1, "task");
        stopped.stopped("report: rejected by an approver", Instant.now());
        executionRepository.saveAndFlush(stopped);

        assertThat(executionRepository.findByExecutionId("fresh-1")).isPresent();
    }
}
