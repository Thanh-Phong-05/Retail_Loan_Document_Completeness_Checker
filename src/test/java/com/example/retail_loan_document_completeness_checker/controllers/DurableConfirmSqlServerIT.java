package com.example.retail_loan_document_completeness_checker.controllers;

import com.example.retail_loan_document_completeness_checker.models.Application;
import com.example.retail_loan_document_completeness_checker.models.AuditLog;
import com.example.retail_loan_document_completeness_checker.models.CompletenessStatus;
import com.example.retail_loan_document_completeness_checker.models.ConfirmOperation;
import com.example.retail_loan_document_completeness_checker.models.DocStatus;
import com.example.retail_loan_document_completeness_checker.models.DocType;
import com.example.retail_loan_document_completeness_checker.models.DocumentFile;
import com.example.retail_loan_document_completeness_checker.models.DossierVersion;
import com.example.retail_loan_document_completeness_checker.models.WorkflowCorrelation;
import com.example.retail_loan_document_completeness_checker.repositories.ApplicationRepository;
import com.example.retail_loan_document_completeness_checker.repositories.AuditLogRepository;
import com.example.retail_loan_document_completeness_checker.repositories.ConfirmOperationRepository;
import com.example.retail_loan_document_completeness_checker.repositories.WorkflowCorrelationRepository;
import com.example.retail_loan_document_completeness_checker.security.ConfirmCaseScope;
import com.example.retail_loan_document_completeness_checker.services.Camunda810UserTaskClient;
import com.example.retail_loan_document_completeness_checker.services.ConfirmEligibilityService;
import com.example.retail_loan_document_completeness_checker.services.ConfirmReviewFactsSource;
import com.example.retail_loan_document_completeness_checker.services.DurableConfirmCoordinator;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Opt-in SQL Server persistence test; never points at the application's default database. */
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "CONFIRM_SQLSERVER_IT_ENABLED", matches = "true"),
        @EnabledIfEnvironmentVariable(named = "CONFIRM_SQLSERVER_IT_JDBC_URL", matches =
                "jdbc:sqlserver://127\\.0\\.0\\.1:11433;databaseName=RetailLoanConfirm02Q_(?:IT|MigrationIT|UnicodeIT);encrypt=false")
})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-it")
class DurableConfirmSqlServerIT {
    private static final Instant EXPIRY = Instant.parse("2099-01-01T00:00:00.123456789Z");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationRepository applications;
    @Autowired WorkflowCorrelationRepository correlations;
    @Autowired ConfirmOperationRepository operations;
    @Autowired AuditLogRepository audits;
    @Autowired DurableConfirmCoordinator coordinator;
    @MockitoBean ConfirmCaseScope scope;
    @MockitoBean ConfirmReviewFactsSource reviewFacts;
    @MockitoBean Camunda810UserTaskClient tasks;
    @MockitoSpyBean AuditLogRepository auditSpy;

    @Test
    void schemaValidationAndWorkflowCorrelationRoundTrip() {
        assertThat(jdbc.queryForObject("SELECT DB_NAME()", String.class))
                .isIn("RetailLoanConfirm02Q_IT", "RetailLoanConfirm02Q_MigrationIT",
                        "RetailLoanConfirm02Q_UnicodeIT");
        assertThat(jdbc.queryForObject("SELECT DATETIME_PRECISION FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME='confirm_operations' "
                + "AND COLUMN_NAME='evidence_valid_until'", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT DATETIME_PRECISION FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME='workflow_correlations' "
                + "AND COLUMN_NAME='created_at'", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME='confirm_operations' "
                + "AND COLUMN_NAME='evidence_valid_until'", String.class)).isEqualTo("datetimeoffset");

        String app = seed();
        entityManager.clear();
        WorkflowCorrelation loaded = correlations.findByApplicationIdAndDossierVersion(app, 1).orElseThrow();
        assertThat(loaded.getState()).isEqualTo(WorkflowCorrelation.State.STARTED);
        assertThat(loaded.getProcessInstanceKey()).isPositive();
        assertThat(loaded.getCreatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
        assertThat(jdbc.queryForObject("SELECT DATEPART(TZOFFSET, created_at) "
                + "FROM dbo.workflow_correlations WHERE correlation_id=?", Integer.class, loaded.getId()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT CONVERT(varchar(33), CAST(created_at AS datetime2(6)), 126) "
                + "FROM dbo.workflow_correlations WHERE correlation_id=?", String.class, loaded.getId()))
                .isEqualTo("2026-01-01T00:00:00.123456");
    }

    @Test
    void unchangedNanosecondEvidenceCompletesOnceAfterDatabaseReload() throws Exception {
        String app = seed();
        ConfirmOperation admitted = admitReconciling(app);
        entityManager.clear();
        ConfirmOperation persisted = operations.findById(admitted.getId()).orElseThrow();
        assertThat(persisted.getEvidenceValidUntil()).isEqualTo(EXPIRY.truncatedTo(ChronoUnit.MICROS));
        assertThat(jdbc.queryForObject("SELECT DATEPART(TZOFFSET, evidence_valid_until) "
                + "FROM dbo.confirm_operations WHERE operation_id=?", Integer.class, admitted.getId()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT CONVERT(varchar(33), CAST(evidence_valid_until AS datetime2(6)), 126) "
                + "FROM dbo.confirm_operations WHERE operation_id=?", String.class, admitted.getId()))
                .isEqualTo("2099-01-01T00:00:00.123456");

        given(tasks.completedBy(any(), eq(300L), eq(admitted.getId()))).willReturn(true);
        coordinator.recover(admitted.getId());
        ConfirmOperation completed = operations.findById(admitted.getId()).orElseThrow();
        assertThat(completed.getState()).isEqualTo(ConfirmOperation.State.COMPLETED);
        assertThat(completed.getAuditLogId()).isNotBlank();
        assertThat(applications.findById(app).orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.READY_FOR_CREDIT_REVIEW);
        assertThat(audits.countByApplicationApplicationIdAndAction(app, "DOSSIER_CONFIRMED")).isEqualTo(1);

        coordinator.recover(admitted.getId());
        mvc.perform(confirm(app, 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOSSIER_ALREADY_CONFIRMED"));
        assertThat(operations.findByApplicationIdAndDossierVersion(app, 1).orElseThrow().getId())
                .isEqualTo(completed.getId());
        assertThat(audits.countByApplicationApplicationIdAndAction(app, "DOSSIER_CONFIRMED")).isEqualTo(1);
    }

    @Test
    void changedFingerprintAfterAdmissionCannotFinalize() throws Exception {
        String app = seed();
        ConfirmOperation admitted = admitReconciling(app);
        var prior = reviewFacts.read(app, 1);
        var changed = snapshot(app, EXPIRY.plusNanos(1));
        given(reviewFacts.read(app, 1)).willReturn(new ConfirmReviewFactsSource.ReviewFacts(
                prior.evaluationVersion(), changed, prior.checklist()));
        given(tasks.completedBy(any(), eq(300L), eq(admitted.getId()))).willReturn(true);

        coordinator.recover(admitted.getId());
        ConfirmOperation rejected = operations.findById(admitted.getId()).orElseThrow();
        assertThat(rejected.getState()).isEqualTo(ConfirmOperation.State.REQUIRES_INTERVENTION);
        assertThat(rejected.getLastError()).isEqualTo("STALE_REVIEW_EVIDENCE");
        assertNotReady(app);
    }

    @Test
    void expiredEvidenceAndStaleVersionCannotCreateOperation() throws Exception {
        String app = seed();
        mvc.perform(confirm(app, 2)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_DOSSIER_VERSION"));
        var prior = reviewFacts.read(app, 1);
        given(reviewFacts.read(app, 1)).willReturn(new ConfirmReviewFactsSource.ReviewFacts(
                prior.evaluationVersion(), snapshot(app, Instant.parse("2026-01-01T00:00:00Z")),
                prior.checklist()));
        mvc.perform(confirm(app, 1)).andExpect(status().isConflict());
        assertThat(operations.findByApplicationIdAndDossierVersion(app, 1)).isEmpty();
        assertNotReady(app);
    }

    @Test
    void auditFailureRollsBackReadyAndRetryFinalizesOnce() throws Exception {
        String app = seed();
        ConfirmOperation admitted = admitReconciling(app);
        given(tasks.completedBy(any(), eq(300L), eq(admitted.getId()))).willReturn(true);
        doThrow(new IllegalStateException("synthetic audit outage"))
                .when(auditSpy).saveAndFlush(any(AuditLog.class));

        coordinator.recover(admitted.getId());
        assertThat(operations.findById(admitted.getId()).orElseThrow().getState())
                .isEqualTo(ConfirmOperation.State.RECONCILING);
        assertNotReady(app);

        reset(auditSpy);
        coordinator.recover(admitted.getId());
        assertThat(operations.findById(admitted.getId()).orElseThrow().getState())
                .isEqualTo(ConfirmOperation.State.COMPLETED);
        assertThat(applications.findById(app).orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.READY_FOR_CREDIT_REVIEW);
        assertThat(audits.countByApplicationApplicationIdAndAction(app, "DOSSIER_CONFIRMED")).isEqualTo(1);
    }

    @Test
    void vietnameseConfirmTextSurvivesCommitReloadAndAudit() throws Exception {
        String app = seed();
        String reviewer = "Nguyễn Thị Ánh, Đặng Quốc Hưng";
        String note = "Thiếu chữ ký trên hợp đồng lao động. Đã kiểm tra CCCD, sao kê lương và đơn đề nghị vay vốn.";

        mvc.perform(confirmWithText(app, reviewer, "x".repeat(1001)))
                .andExpect(status().isBadRequest());
        assertThat(operations.findByApplicationIdAndDossierVersion(app, 1)).isEmpty();

        given(tasks.lookup(any())).willReturn(new Camunda810UserTaskClient.Lookup(
                Camunda810UserTaskClient.LookupState.OPEN, 300L));
        given(tasks.complete(any(), eq(300L), any()))
                .willReturn(Camunda810UserTaskClient.CompletionState.COMPLETED);
        given(tasks.completedBy(any(), eq(300L), anyString())).willReturn(false);
        mvc.perform(confirmWithText(app, reviewer, note))
                .andExpect(status().isAccepted());

        entityManager.clear();
        ConfirmOperation operation = operations.findByApplicationIdAndDossierVersion(app, 1).orElseThrow();
        assertThat(operation.getReviewerName()).isEqualTo(reviewer);
        assertThat(operation.getNote()).isEqualTo(note);
        assertThat(jdbc.queryForObject("SELECT note FROM dbo.confirm_operations WHERE operation_id=?",
                String.class, operation.getId())).isEqualTo(note);

        given(tasks.completedBy(any(), eq(300L), eq(operation.getId()))).willReturn(true);
        coordinator.recover(operation.getId());
        entityManager.clear();
        ConfirmOperation completed = operations.findById(operation.getId()).orElseThrow();
        assertThat(completed.getState()).isEqualTo(ConfirmOperation.State.COMPLETED);
        AuditLog audit = audits.findById(completed.getAuditLogId()).orElseThrow();
        assertThat(audit.getDetails()).contains(reviewer, note);
        assertThat(jdbc.queryForObject("SELECT details FROM dbo.audit_logs WHERE audit_log_id=?",
                String.class, audit.getAuditLogId())).isEqualTo(audit.getDetails());
    }

    private ConfirmOperation admitReconciling(String app) throws Exception {
        given(tasks.lookup(any())).willReturn(new Camunda810UserTaskClient.Lookup(
                Camunda810UserTaskClient.LookupState.OPEN, 300L));
        given(tasks.complete(any(), eq(300L), any())).willReturn(Camunda810UserTaskClient.CompletionState.COMPLETED);
        given(tasks.completedBy(any(), eq(300L), anyString())).willReturn(false);
        mvc.perform(confirm(app, 1)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("RECONCILING"));
        return operations.findByApplicationIdAndDossierVersion(app, 1).orElseThrow();
    }

    private void assertNotReady(String app) {
        assertThat(applications.findById(app).orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.REVIEW_NEEDED);
        assertThat(audits.countByApplicationApplicationIdAndAction(app, "DOSSIER_CONFIRMED")).isZero();
    }

    private String seed() {
        String app = "SQLIT-" + UUID.randomUUID().toString().substring(0, 8);
        Application application = new Application();
        application.setApplicationId(app);
        application.setProductType("SYNTHETIC_PRODUCT");
        application.setApplicantName("Synthetic Applicant");
        application.setCompletenessStatus(CompletenessStatus.REVIEW_NEEDED);
        DossierVersion version = new DossierVersion();
        version.setDossierVersionId(UUID.randomUUID().toString());
        version.setVersionNumber(1);
        version.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        DocumentFile file = new DocumentFile();
        file.setFileId(UUID.randomUUID().toString());
        file.setDocType(DocType.NATIONAL_ID);
        file.setFileName("synthetic.pdf");
        file.setStoredFileName("synthetic.pdf");
        file.setStoragePath("synthetic-tests/synthetic.pdf");
        file.setContentType("application/pdf");
        file.setFileSize(100);
        file.setFileHashSha256("a".repeat(64));
        file.setStatus(DocStatus.VALID);
        version.addDocument(file);
        application.addDossierVersion(version);
        applications.saveAndFlush(application);

        WorkflowCorrelation correlation = new WorkflowCorrelation();
        correlation.setId(UUID.randomUUID().toString());
        correlation.setApplicationId(app);
        correlation.setDossierVersion(1);
        correlation.setProcessInstanceKey(1000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
        correlation.setProcessDefinitionKey(200L);
        correlation.setProcessDefinitionVersion(1);
        correlation.setProcessId("retail-loan-document-completeness");
        correlation.setConfirmElementId("Activity_03e0s8k");
        correlation.setTenantId("<default>");
        correlation.setCreatedAt(Instant.parse("2026-01-01T00:00:00.123456Z"));
        correlation.setState(WorkflowCorrelation.State.STARTED);
        correlations.saveAndFlush(correlation);
        given(scope.mayConfirm(any(), eq(app))).willReturn(true);
        given(reviewFacts.read(app, 1)).willReturn(acceptedFacts(app, file.getFileId()));
        return app;
    }

    private static ConfirmReviewFactsSource.ReviewFacts acceptedFacts(String app, String fileId) {
        var requirement = new ConfirmEligibilityService.RequirementFact("SYNTHETIC-REQUIREMENT",
                ConfirmEligibilityService.Applicability.APPLICABLE, null,
                ConfirmEligibilityService.EvidenceState.ACCEPTED, List.of(fileId),
                ConfirmEligibilityService.ExceptionState.NONE);
        var checklist = new ConfirmEligibilityService.ChecklistFacts(app, 1L,
                "synthetic-manifest", "synthetic-context", "synthetic-policy", true, List.of(requirement));
        return new ConfirmReviewFactsSource.ReviewFacts(1L, snapshot(app, EXPIRY), checklist);
    }

    private static ConfirmEligibilityService.SnapshotFacts snapshot(String app, Instant expiry) {
        return new ConfirmEligibilityService.SnapshotFacts(app, 1L,
                "synthetic-manifest", "synthetic-context", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"), expiry, true, true, true, true);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirm(
            String app, int version) {
        return post("/api/v1/dossiers/" + app + "/confirm")
                .with(user("reviewer-sql-it").roles("REVIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewerName\":\"Synthetic Reviewer\",\"expectedVersion\":" + version + "}");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirmWithText(
            String app, String reviewer, String note) {
        return post("/api/v1/dossiers/" + app + "/confirm")
                .with(user("reviewer-sql-it").roles("REVIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"reviewerName\":\"" + reviewer + "\",\"note\":\"" + note
                        + "\",\"expectedVersion\":1}").getBytes(StandardCharsets.UTF_8));
    }
}
