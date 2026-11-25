package com.example.retail_loan_document_completeness_checker.controllers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** One-time synthetic schema bootstrap for the dedicated, empty R8 test database. */
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "CONFIRM_SQLSERVER_SCHEMA_BOOTSTRAP_ENABLED", matches = "true"),
        @EnabledIfEnvironmentVariable(named = "CONFIRM_SQLSERVER_IT_JDBC_URL", matches =
                "jdbc:sqlserver://127\\.0\\.0\\.1:11433;databaseName=RetailLoanConfirm02Q_IT;encrypt=false")
})
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-only",
        "app.camunda.enabled=false"
})
@ActiveProfiles("sqlserver-it")
class SqlServerSchemaBootstrapIT {
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsSyntheticSchemaOnlyInDedicatedDatabase() {
        assertThat(jdbc.queryForObject("SELECT DB_NAME()", String.class))
                .isEqualTo("RetailLoanConfirm02Q_IT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys.tables WHERE is_ms_shipped=0",
                Integer.class)).isEqualTo(7);
    }
}
