package com.example.retail_loan_document_completeness_checker.controllers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** One-time JPA schema source for a new, empty, disposable migration/Unicode test database. */
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "CONFIRM_SQLSERVER_MIGRATION_BOOTSTRAP_ENABLED", matches = "true"),
        @EnabledIfEnvironmentVariable(named = "CONFIRM_SQLSERVER_IT_JDBC_URL", matches =
                "jdbc:sqlserver://127\\.0\\.0\\.1:11433;databaseName=RetailLoanConfirm02Q_(?:MigrationIT|UnicodeIT);encrypt=false")
})
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-only",
        "app.camunda.enabled=false"
})
@ActiveProfiles("sqlserver-it")
class SqlServerMigrationBootstrapIT {
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsJpaDerivedSchemaOnlyInDedicatedMigrationDatabase() {
        assertThat(jdbc.queryForObject("SELECT DB_NAME()", String.class))
                .isIn("RetailLoanConfirm02Q_MigrationIT", "RetailLoanConfirm02Q_UnicodeIT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys.tables WHERE is_ms_shipped=0",
                Integer.class)).isEqualTo(7);
    }
}
