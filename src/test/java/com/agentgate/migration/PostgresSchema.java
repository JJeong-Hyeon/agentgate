package com.agentgate.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A real PostgreSQL for migration tests, which the H2 used elsewhere cannot stand in for. */
final class PostgresSchema {

    private PostgresSchema() {
    }

    static PostgreSQLContainer start() {
        PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();
        return postgres;
    }

    /** Points the application at {@code postgres} with Flyway on and Hibernate only validating. */
    static void register(DynamicPropertyRegistry registry, PostgreSQLContainer postgres) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        // As in src/main/resources/application.yaml, which the test application.yaml replaces.
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "0");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
    /** Versions of the migrations shipped on the classpath, in order. */
    static List<String> shippedVersions() {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql");
            return Arrays.stream(files)
                    .map(f -> f.getFilename().substring(1, f.getFilename().indexOf("__")))
                    .sorted(Comparator.comparingInt(Integer::parseInt))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
