package com.example.retail_loan_document_completeness_checker.controllers;

import com.example.retail_loan_document_completeness_checker.models.Application;
import com.example.retail_loan_document_completeness_checker.models.CompletenessStatus;
import com.example.retail_loan_document_completeness_checker.models.DocStatus;
import com.example.retail_loan_document_completeness_checker.models.DocType;
import com.example.retail_loan_document_completeness_checker.models.DocumentFile;
import com.example.retail_loan_document_completeness_checker.models.DossierVersion;
import com.example.retail_loan_document_completeness_checker.repositories.ApplicationRepository;
import com.example.retail_loan_document_completeness_checker.repositories.AuditLogRepository;
import com.example.retail_loan_document_completeness_checker.security.ConfirmCaseScope;
import com.example.retail_loan_document_completeness_checker.services.ConfirmEligibilityService;
import com.example.retail_loan_document_completeness_checker.services.ConfirmReviewFactsSource;
import com.example.retail_loan_document_completeness_checker.services.ConfirmWorkflowGateway;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "debug=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DossierConfirmFlowTests.SyntheticCaseScope.class)
@Transactional
class DossierConfirmFlowTests {

    private static final String PATH = "/api/v1/dossiers/APP-A/confirm";

    @Autowired MockMvc mvc;
    @Autowired ApplicationRepository applications;
    @Autowired AuditLogRepository audits;
    @Autowired EntityManager entityManager;
    @MockitoBean ConfirmReviewFactsSource reviewFacts;
    @MockitoBean ConfirmWorkflowGateway workflow;

    @Test
    void trustedScopedReviewerCanConfirmPersistedVersionWithVerifiedSyntheticFacts() throws Exception {
        persistDossier();
        given(reviewFacts.read("APP-A", 1L)).willReturn(acceptedFacts());
        given(workflow.inspect("APP-A", 1L)).willReturn(ConfirmWorkflowGateway.TaskState.VERIFIED_ABSENT);

        mvc.perform(post(PATH).with(user("reviewer-A").roles("REVIEWER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body("Display Name", "Reviewed", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value("APP-A"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.completenessStatus").value("READY_FOR_CREDIT_REVIEW"))
                .andExpect(jsonPath("$.workflowState").value("VERIFIED_NO_OPEN_TASK"))
                .andExpect(jsonPath("$.auditLogId").isNotEmpty());

        entityManager.flush();
        entityManager.clear();
        assertThat(applications.findById("APP-A").orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.READY_FOR_CREDIT_REVIEW);
        var audit = audits.findAll();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).getAction()).isEqualTo("DOSSIER_CONFIRMED");
        assertThat(audit.get(0).getApplication().getApplicationId()).isEqualTo("APP-A");
        assertThat(audit.get(0).getDetails()).contains("actor=10:reviewer-A", "reviewerName=12:Display Name",
                "version=1", "before=REVIEW_NEEDED", "after=READY_FOR_CREDIT_REVIEW", "note=8:Reviewed");
    }

    @Test
    void selfDeclaredReviewerNameCannotAuthenticateOrGrantAnotherCase() throws Exception {
        mvc.perform(post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("reviewer-A", "Reviewed", 1)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/dossiers/APP-B/confirm")
                        .with(user("reviewer-A").roles("REVIEWER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body("reviewer-A", "Reviewed", 1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void scopedReviewerGetsNotFoundForMissingApplication() throws Exception {
        mvc.perform(authorized(body("Display Name", "Reviewed", 1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingReviewFactsNeverPromoteUploadedValidFile() throws Exception {
        persistDossier();
        mvc.perform(authorized(body("Display Name", "Reviewed", 1)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CONFIRM_FACTS_UNAVAILABLE"));
        assertUnchanged();
    }

    @Test
    void unverifiedRequiredEvidenceBlocksConfirmation() throws Exception {
        persistDossier();
        var accepted = acceptedFacts();
        var unverified = new ConfirmEligibilityService.RequirementFact("SYNTHETIC-REQUIREMENT",
                ConfirmEligibilityService.Applicability.APPLICABLE, null,
                ConfirmEligibilityService.EvidenceState.UNVERIFIED, List.of(),
                ConfirmEligibilityService.ExceptionState.NONE);
        var checklist = new ConfirmEligibilityService.ChecklistFacts("APP-A", 1L,
                "synthetic-manifest", "synthetic-context", "synthetic-policy", true,
                List.of(unverified));
        given(reviewFacts.read("APP-A", 1L)).willReturn(
                new ConfirmReviewFactsSource.ReviewFacts(1L, accepted.snapshot(), checklist));

        mvc.perform(authorized(body("Display Name", "Reviewed", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOSSIER_NOT_ELIGIBLE"));
        assertUnchanged();
    }

    @Test
    void snapshotThatExpiredAfterEvaluationCannotBeConfirmedNow() throws Exception {
        persistDossier();
        var accepted = acceptedFacts();
        var old = accepted.snapshot();
        var expired = new ConfirmEligibilityService.SnapshotFacts(old.applicationId(),
                old.dossierVersion(), old.manifestHash(), old.contextVersion(), old.importTime(),
                old.asOf(), Instant.parse("2026-01-03T00:00:00Z"),
                old.manifestProcessingComplete(), old.criticalFieldsVerified(),
                old.snapshotAttested(), old.noKnownSourceChange());
        given(reviewFacts.read("APP-A", 1L)).willReturn(
                new ConfirmReviewFactsSource.ReviewFacts(1L, expired, accepted.checklist()));

        mvc.perform(authorized(body("Display Name", "Reviewed", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOSSIER_REVIEW_EXPIRED"));
        assertUnchanged();
    }

    @Test
    void staleVersionIsRejectedBeforeReviewOrAudit() throws Exception {
        persistDossier();
        mvc.perform(authorized(body("Display Name", "Reviewed", 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_DOSSIER_VERSION"));
        assertUnchanged();
    }

    @Test
    void duplicateConfirmationCreatesNoSecondAudit() throws Exception {
        persistDossier();
        given(reviewFacts.read("APP-A", 1L)).willReturn(acceptedFacts());
        given(workflow.inspect("APP-A", 1L)).willReturn(ConfirmWorkflowGateway.TaskState.VERIFIED_ABSENT);
        mvc.perform(authorized(body("Display Name", "Reviewed", 1))).andExpect(status().isOk());
        mvc.perform(authorized(body("Display Name", "Reviewed", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOSSIER_ALREADY_CONFIRMED"));
        assertThat(audits.count()).isEqualTo(1);
    }

    @Test
    void presentOrUnknownWorkflowTaskDoesNotCommitReady() throws Exception {
        persistDossier();
        given(reviewFacts.read("APP-A", 1L)).willReturn(acceptedFacts());
        given(workflow.inspect("APP-A", 1L)).willReturn(ConfirmWorkflowGateway.TaskState.PRESENT);
        mvc.perform(authorized(body("Display Name", "Reviewed", 1)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CONFIRM_WORKFLOW_UNAVAILABLE"));
        assertUnchanged();
        given(workflow.inspect("APP-A", 1L)).willReturn(ConfirmWorkflowGateway.TaskState.UNKNOWN);
        mvc.perform(authorized(body("Display Name", "Reviewed", 1)))
                .andExpect(status().isServiceUnavailable());
        assertUnchanged();
    }

    @Test
    void workflowLookupFailureDoesNotCommitReady() throws Exception {
        persistDossier();
        given(reviewFacts.read("APP-A", 1L)).willReturn(acceptedFacts());
        given(workflow.inspect("APP-A", 1L)).willThrow(new IllegalStateException("synthetic lookup outage"));

        mvc.perform(authorized(body("Display Name", "Reviewed", 1)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CONFIRM_WORKFLOW_UNAVAILABLE"));
        assertUnchanged();
    }

    @Test
    void auditPayloadTooLongIsRejectedWithoutTruncatingOrMutating() throws Exception {
        persistDossier();
        given(reviewFacts.read("APP-A", 1L)).willReturn(acceptedFacts());
        given(workflow.inspect("APP-A", 1L)).willReturn(ConfirmWorkflowGateway.TaskState.VERIFIED_ABSENT);
        mvc.perform(authorized(body("Display Name", "x".repeat(1000), 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIRM_AUDIT_TOO_LONG"));
        assertUnchanged();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorized(String json) {
        return post(PATH).with(user("reviewer-A").roles("REVIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private void assertUnchanged() {
        entityManager.flush();
        entityManager.clear();
        assertThat(applications.findById("APP-A").orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.REVIEW_NEEDED);
        assertThat(audits.count()).isZero();
    }

    private void persistDossier() {
        Application application = new Application();
        application.setApplicationId("APP-A");
        application.setProductType("SYNTHETIC_PRODUCT");
        application.setApplicantName("Synthetic Applicant");
        application.setCompletenessStatus(CompletenessStatus.REVIEW_NEEDED);
        DossierVersion version = new DossierVersion();
        version.setDossierVersionId("VERSION-A-1");
        version.setVersionNumber(1);
        version.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        DocumentFile file = new DocumentFile();
        file.setFileId("FILE-A-1");
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
        entityManager.clear();
    }

    private static ConfirmReviewFactsSource.ReviewFacts acceptedFacts() {
        var snapshot = new ConfirmEligibilityService.SnapshotFacts("APP-A", 1L, "synthetic-manifest",
                "synthetic-context", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"), Instant.parse("2099-01-01T00:00:00Z"),
                true, true, true, true);
        var requirement = new ConfirmEligibilityService.RequirementFact("SYNTHETIC-REQUIREMENT",
                ConfirmEligibilityService.Applicability.APPLICABLE, null,
                ConfirmEligibilityService.EvidenceState.ACCEPTED, List.of("FILE-A-1"),
                ConfirmEligibilityService.ExceptionState.NONE);
        var checklist = new ConfirmEligibilityService.ChecklistFacts("APP-A", 1L,
                "synthetic-manifest", "synthetic-context", "synthetic-policy", true,
                List.of(requirement));
        return new ConfirmReviewFactsSource.ReviewFacts(1L, snapshot, checklist);
    }

    private static String body(String reviewerName, String note, long version) {
        return "{\"reviewerName\":\"" + reviewerName + "\",\"note\":\"" + note
                + "\",\"expectedVersion\":" + version + "}";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SyntheticCaseScope {
        @Bean
        ConfirmCaseScope exactCaseOnly() {
            return (authentication, applicationId) -> "reviewer-A".equals(authentication.getName())
                    && "APP-A".equals(applicationId);
        }
    }
}
