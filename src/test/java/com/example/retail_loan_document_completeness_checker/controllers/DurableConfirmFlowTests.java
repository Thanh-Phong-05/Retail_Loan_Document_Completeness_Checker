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
import com.example.retail_loan_document_completeness_checker.services.Camunda85UserTaskClient;
import com.example.retail_loan_document_completeness_checker.services.ConfirmEligibilityService;
import com.example.retail_loan_document_completeness_checker.services.ConfirmReviewFactsSource;
import com.example.retail_loan_document_completeness_checker.services.DossierPersistenceService;
import com.example.retail_loan_document_completeness_checker.services.DurableConfirmCoordinator;
import com.example.retail_loan_document_completeness_checker.dto.UploadDossierRequest;
import com.example.retail_loan_document_completeness_checker.services.StoredFile;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.camunda.enabled=true", "app.camunda.confirm-recovery-ms=600000",
        "spring.datasource.url=jdbc:h2:mem:durable-confirm;MODE=MSSQLServer;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DurableConfirmFlowTests {
    private static final AtomicLong KEYS = new AtomicLong(1000);
    @Autowired MockMvc mvc;
    @Autowired ApplicationRepository applications;
    @Autowired WorkflowCorrelationRepository correlations;
    @Autowired ConfirmOperationRepository operations;
    @Autowired AuditLogRepository audits;
    @Autowired DurableConfirmCoordinator coordinator;
    @Autowired DossierPersistenceService persistence;
    @Autowired EntityManager entityManager;
    @MockitoBean ConfirmCaseScope scope;
    @MockitoBean ConfirmReviewFactsSource reviewFacts;
    @MockitoBean Camunda85UserTaskClient tasks;
    @MockitoSpyBean AuditLogRepository auditSpy;

    @Test
    void httpToPersistedOperationToVerifiedTaskToReadyAndOneAudit() throws Exception {
        String app = seed();
        given(tasks.lookup(any())).willReturn(new Camunda85UserTaskClient.Lookup(
                Camunda85UserTaskClient.LookupState.OPEN, 300L));
        given(tasks.complete(any(), eq(300L), any())).willReturn(Camunda85UserTaskClient.CompletionState.COMPLETED);
        given(tasks.completedBy(any(), eq(300L), anyString())).willReturn(true);

        mvc.perform(confirm(app)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.completenessStatus").value("READY_FOR_CREDIT_REVIEW"))
                .andExpect(jsonPath("$.auditLogId").isNotEmpty());

        ConfirmOperation operation = operations.findByApplicationIdAndDossierVersion(app, 1).orElseThrow();
        assertThat(operation.getState()).isEqualTo(ConfirmOperation.State.COMPLETED);
        assertThat(operation.getUserTaskKey()).isEqualTo(300L);
        assertThat(operation.getActorId()).isEqualTo("reviewer-test");
        assertThat(applications.findById(app).orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.READY_FOR_CREDIT_REVIEW);
        assertThat(audits.countByApplicationApplicationIdAndAction(app, "DOSSIER_CONFIRMED")).isEqualTo(1);
        coordinator.recover(operation.getId());
        assertThat(audits.countByApplicationApplicationIdAndAction(app, "DOSSIER_CONFIRMED")).isEqualTo(1);
    }

    @Test
    void unknownCommandResultReturns202AndLaterRecoveryFinishesWithoutSecondCompletion() throws Exception {
        String app = seed();
        given(tasks.lookup(any())).willReturn(new Camunda85UserTaskClient.Lookup(
                Camunda85UserTaskClient.LookupState.OPEN, 300L));
        given(tasks.complete(any(), eq(300L), any())).willReturn(Camunda85UserTaskClient.CompletionState.UNKNOWN);
        given(tasks.completedBy(any(), eq(300L), anyString())).willReturn(false);

        mvc.perform(confirm(app)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("RECONCILING"))
                .andExpect(jsonPath("$.completenessStatus").doesNotExist());
        ConfirmOperation operation = operations.findByApplicationIdAndDossierVersion(app, 1).orElseThrow();
        assertThat(operations.findById(operation.getId()).orElseThrow().getState())
                .isEqualTo(ConfirmOperation.State.RECONCILING);
        assertThat(applications.findById(app).orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.REVIEW_NEEDED);
        assertThat(audits.countByApplicationApplicationIdAndAction(app, "DOSSIER_CONFIRMED")).isZero();

        given(tasks.completedBy(any(), eq(300L), eq(operation.getId()))).willReturn(true);
        entityManager.clear(); // Recovery reads the committed row, not the admission object's in-memory state.
        coordinator.recover(operation.getId());
        assertThat(operations.findById(operation.getId()).orElseThrow().getState())
                .isEqualTo(ConfirmOperation.State.COMPLETED);
        assertThat(audits.countByApplicationApplicationIdAndAction(app, "DOSSIER_CONFIRMED")).isEqualTo(1);
        mvc.perform(get("/api/v1/dossiers/" + app + "/confirm-operations/" + operation.getId())
                        .with(user("reviewer-test").roles("REVIEWER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"));
    }

    @Test
    void pendingOperationBlocksUploadAndDuplicateConfirm() throws Exception {
        String app = seed();
        given(tasks.lookup(any())).willReturn(new Camunda85UserTaskClient.Lookup(
                Camunda85UserTaskClient.LookupState.UNKNOWN, null));
        mvc.perform(confirm(app)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("PENDING"));
        mvc.perform(confirm(app)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIRM_IN_PROGRESS"));
        assertThatThrownBy(() -> persistence.saveUpload(new UploadDossierRequest(app,
                "SYNTHETIC_PRODUCT", "Synthetic Applicant"), List.of(new StoredFile(
                UUID.randomUUID().toString(), "synthetic.pdf", "synthetic.pdf",
                Path.of("synthetic-tests", "synthetic.pdf"), "application/pdf", 100,
                "b".repeat(64))))).hasMessageContaining("Upload is blocked");
        assertThat(applications.findById(app).orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.REVIEW_NEEDED);
    }

    @Test
    void taskWithWrongCorrelationCannotReachReady() throws Exception {
        String app = seed();
        given(tasks.lookup(any())).willReturn(new Camunda85UserTaskClient.Lookup(
                Camunda85UserTaskClient.LookupState.WRONG_CASE_OR_VERSION, null));
        mvc.perform(confirm(app)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("REQUIRES_INTERVENTION"));
        assertThat(audits.countByApplicationApplicationIdAndAction(app, "DOSSIER_CONFIRMED")).isZero();
    }

    @Test
    void taskAlreadyCompletedBeforeAdmissionCannotBeClaimedAsThisConfirmation() throws Exception {
        String app = seed();
        given(tasks.lookup(any())).willReturn(new Camunda85UserTaskClient.Lookup(
                Camunda85UserTaskClient.LookupState.ALREADY_COMPLETED, 300L));
        mvc.perform(confirm(app)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("REQUIRES_INTERVENTION"));
        assertThat(applications.findById(app).orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.REVIEW_NEEDED);
        assertThat(audits.countByApplicationApplicationIdAndAction(app, "DOSSIER_CONFIRMED")).isZero();
    }

    @Test
    void unauthenticatedAndWrongCaseStatusDoNotRevealOperation() throws Exception {
        String app = seed();
        mvc.perform(post("/api/v1/dossiers/" + app + "/confirm").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewerName\":\"Self\",\"expectedVersion\":1}"))
                .andExpect(status().isUnauthorized());
        assertThat(operations.findByApplicationIdAndDossierVersion(app, 1)).isEmpty();
        mvc.perform(get("/api/v1/dossiers/" + app + "/confirm-operations/" + UUID.randomUUID())
                        .with(user("other-reviewer").roles("REVIEWER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void staleVersionAndMissingReviewFactsFailBeforeOperationIsPersisted() throws Exception {
        String app = seed();
        mvc.perform(post("/api/v1/dossiers/" + app + "/confirm")
                        .with(user("reviewer-test").roles("REVIEWER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewerName\":\"Display Name\",\"expectedVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_DOSSIER_VERSION"));
        given(reviewFacts.read(app, 1)).willReturn(null);
        mvc.perform(confirm(app)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CONFIRM_FACTS_UNAVAILABLE"));
        assertThat(operations.findByApplicationIdAndDossierVersion(app, 1)).isEmpty();
    }

    @Test
    void auditFailureAfterTaskCompletionKeepsOperationRecoverable() throws Exception {
        String app = seed();
        given(tasks.lookup(any())).willReturn(new Camunda85UserTaskClient.Lookup(
                Camunda85UserTaskClient.LookupState.OPEN, 300L));
        given(tasks.complete(any(), eq(300L), any())).willReturn(Camunda85UserTaskClient.CompletionState.COMPLETED);
        given(tasks.completedBy(any(), eq(300L), anyString())).willReturn(true);
        doThrow(new IllegalStateException("synthetic audit outage"))
                .when(auditSpy).saveAndFlush(any(AuditLog.class));

        mvc.perform(confirm(app)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("RECONCILING"));
        ConfirmOperation operation = operations.findByApplicationIdAndDossierVersion(app, 1).orElseThrow();
        assertThat(operation.getLastError()).isEqualTo("DB_FINALIZATION_FAILED");
        assertThat(applications.findById(app).orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.REVIEW_NEEDED);
        assertThat(audits.countByApplicationApplicationIdAndAction(app, "DOSSIER_CONFIRMED")).isZero();

        reset(auditSpy);
        coordinator.recover(operation.getId());
        assertThat(operations.findById(operation.getId()).orElseThrow().getState())
                .isEqualTo(ConfirmOperation.State.COMPLETED);
        assertThat(audits.countByApplicationApplicationIdAndAction(app, "DOSSIER_CONFIRMED")).isEqualTo(1);
    }

    @Test
    void changedReviewEvidenceAfterTaskCompletionRequiresInterventionWithoutReady() throws Exception {
        String app = seed();
        given(tasks.lookup(any())).willReturn(new Camunda85UserTaskClient.Lookup(
                Camunda85UserTaskClient.LookupState.OPEN, 300L));
        given(tasks.complete(any(), eq(300L), any())).willReturn(Camunda85UserTaskClient.CompletionState.COMPLETED);
        given(tasks.completedBy(any(), eq(300L), anyString())).willReturn(false);
        mvc.perform(confirm(app)).andExpect(status().isAccepted());
        ConfirmOperation operation = operations.findByApplicationIdAndDossierVersion(app, 1).orElseThrow();
        var prior = reviewFacts.read(app, 1);
        var changed = new ConfirmEligibilityService.SnapshotFacts(app, 1L,
                "changed-manifest", prior.snapshot().contextVersion(),
                prior.snapshot().importTime(), prior.snapshot().asOf(),
                prior.snapshot().evidenceValidUntil(), true, true, true, true);
        given(reviewFacts.read(app, 1)).willReturn(new ConfirmReviewFactsSource.ReviewFacts(
                1L, changed, prior.checklist()));
        given(tasks.completedBy(any(), eq(300L), eq(operation.getId()))).willReturn(true);
        coordinator.recover(operation.getId());
        assertThat(operations.findById(operation.getId()).orElseThrow().getState())
                .isEqualTo(ConfirmOperation.State.REQUIRES_INTERVENTION);
        assertThat(applications.findById(app).orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.REVIEW_NEEDED);
        assertThat(audits.countByApplicationApplicationIdAndAction(app, "DOSSIER_CONFIRMED")).isZero();
    }

    @Test
    void unchangedNanosecondEvidenceSurvivesPersistedRecovery() throws Exception {
        String app = seed();
        var prior = reviewFacts.read(app, 1);
        Instant preciseExpiry = Instant.parse("2099-01-01T00:00:00.123456789Z");
        var snapshot = new ConfirmEligibilityService.SnapshotFacts(app, 1L,
                prior.snapshot().manifestHash(), prior.snapshot().contextVersion(),
                prior.snapshot().importTime(), prior.snapshot().asOf(), preciseExpiry,
                true, true, true, true);
        given(reviewFacts.read(app, 1)).willReturn(new ConfirmReviewFactsSource.ReviewFacts(
                prior.evaluationVersion(), snapshot, prior.checklist()));
        given(tasks.lookup(any())).willReturn(new Camunda85UserTaskClient.Lookup(
                Camunda85UserTaskClient.LookupState.OPEN, 300L));
        given(tasks.complete(any(), eq(300L), any())).willReturn(Camunda85UserTaskClient.CompletionState.COMPLETED);
        given(tasks.completedBy(any(), eq(300L), anyString())).willReturn(false);

        mvc.perform(confirm(app)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("RECONCILING"));
        ConfirmOperation operation = operations.findByApplicationIdAndDossierVersion(app, 1).orElseThrow();
        entityManager.clear();
        Instant persistedExpiry = operations.findById(operation.getId()).orElseThrow().getEvidenceValidUntil();
        assertThat(persistedExpiry).isNotEqualTo(preciseExpiry);
        assertThat(persistedExpiry.getNano() % 1_000).isZero();

        given(tasks.completedBy(any(), eq(300L), eq(operation.getId()))).willReturn(true);
        coordinator.recover(operation.getId());
        assertThat(operations.findById(operation.getId()).orElseThrow().getState())
                .isEqualTo(ConfirmOperation.State.COMPLETED);
        assertThat(applications.findById(app).orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.READY_FOR_CREDIT_REVIEW);
        assertThat(audits.countByApplicationApplicationIdAndAction(app, "DOSSIER_CONFIRMED")).isEqualTo(1);
    }

    @Test
    void subMicrosecondEvidenceChangeStillRequiresIntervention() throws Exception {
        String app = seed();
        given(tasks.lookup(any())).willReturn(new Camunda85UserTaskClient.Lookup(
                Camunda85UserTaskClient.LookupState.OPEN, 300L));
        given(tasks.complete(any(), eq(300L), any())).willReturn(Camunda85UserTaskClient.CompletionState.COMPLETED);
        given(tasks.completedBy(any(), eq(300L), anyString())).willReturn(false);
        mvc.perform(confirm(app)).andExpect(status().isAccepted());

        ConfirmOperation operation = operations.findByApplicationIdAndDossierVersion(app, 1).orElseThrow();
        var prior = reviewFacts.read(app, 1);
        var changed = new ConfirmEligibilityService.SnapshotFacts(app, 1L,
                prior.snapshot().manifestHash(), prior.snapshot().contextVersion(),
                prior.snapshot().importTime(), prior.snapshot().asOf(),
                prior.snapshot().evidenceValidUntil().plusNanos(1), true, true, true, true);
        given(reviewFacts.read(app, 1)).willReturn(new ConfirmReviewFactsSource.ReviewFacts(
                prior.evaluationVersion(), changed, prior.checklist()));
        given(tasks.completedBy(any(), eq(300L), eq(operation.getId()))).willReturn(true);

        coordinator.recover(operation.getId());
        ConfirmOperation recovered = operations.findById(operation.getId()).orElseThrow();
        assertThat(recovered.getState()).isEqualTo(ConfirmOperation.State.REQUIRES_INTERVENTION);
        assertThat(recovered.getLastError()).isEqualTo("STALE_REVIEW_EVIDENCE");
        assertThat(applications.findById(app).orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.REVIEW_NEEDED);
        assertThat(audits.countByApplicationApplicationIdAndAction(app, "DOSSIER_CONFIRMED")).isZero();
    }

    private String seed() {
        String app = "APP-Q-" + UUID.randomUUID().toString().substring(0, 8);
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
        correlation.setProcessInstanceKey(KEYS.incrementAndGet());
        correlation.setProcessDefinitionKey(200L);
        correlation.setProcessDefinitionVersion(1);
        correlation.setProcessId("retail-loan-document-completeness");
        correlation.setConfirmElementId("Activity_03e0s8k");
        correlation.setTenantId("<default>");
        correlation.setCreatedAt(Instant.now());
        correlation.setState(WorkflowCorrelation.State.STARTED);
        correlations.saveAndFlush(correlation);
        given(scope.mayConfirm(any(), eq(app))).willAnswer(invocation ->
                "reviewer-test".equals(((org.springframework.security.core.Authentication)
                        invocation.getArgument(0)).getName()));
        given(reviewFacts.read(app, 1)).willReturn(acceptedFacts(app, file.getFileId()));
        return app;
    }

    private static ConfirmReviewFactsSource.ReviewFacts acceptedFacts(String app, String fileId) {
        var snapshot = new ConfirmEligibilityService.SnapshotFacts(app, 1L,
                "synthetic-manifest", "synthetic-context", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"), Instant.parse("2099-01-01T00:00:00Z"),
                true, true, true, true);
        var requirement = new ConfirmEligibilityService.RequirementFact("SYNTHETIC-REQUIREMENT",
                ConfirmEligibilityService.Applicability.APPLICABLE, null,
                ConfirmEligibilityService.EvidenceState.ACCEPTED, List.of(fileId),
                ConfirmEligibilityService.ExceptionState.NONE);
        var checklist = new ConfirmEligibilityService.ChecklistFacts(app, 1L,
                "synthetic-manifest", "synthetic-context", "synthetic-policy", true,
                List.of(requirement));
        return new ConfirmReviewFactsSource.ReviewFacts(1L, snapshot, checklist);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirm(String app) {
        return post("/api/v1/dossiers/" + app + "/confirm")
                .with(user("reviewer-test").roles("REVIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewerName\":\"Display Name\",\"note\":\"Reviewed\",\"expectedVersion\":1}");
    }
}
