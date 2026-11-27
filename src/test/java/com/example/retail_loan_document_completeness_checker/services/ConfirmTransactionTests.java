package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.dto.ConfirmDossierRequest;
import com.example.retail_loan_document_completeness_checker.dto.UploadDossierRequest;
import com.example.retail_loan_document_completeness_checker.models.Application;
import com.example.retail_loan_document_completeness_checker.models.AuditLog;
import com.example.retail_loan_document_completeness_checker.models.CompletenessStatus;
import com.example.retail_loan_document_completeness_checker.models.DocStatus;
import com.example.retail_loan_document_completeness_checker.models.DocType;
import com.example.retail_loan_document_completeness_checker.models.DocumentFile;
import com.example.retail_loan_document_completeness_checker.models.DossierVersion;
import com.example.retail_loan_document_completeness_checker.repositories.ApplicationRepository;
import com.example.retail_loan_document_completeness_checker.repositories.AuditLogRepository;
import com.example.retail_loan_document_completeness_checker.security.ConfirmCaseScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import com.example.retail_loan_document_completeness_checker.exceptions.ConfirmException;

@SpringBootTest(properties = {
        "debug=false",
        "spring.datasource.url=jdbc:h2:mem:confirm-rollback;MODE=MSSQLServer;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class ConfirmTransactionTests {

    @Autowired ConfirmService confirm;
    @Autowired ApplicationRepository applications;
    @Autowired DossierPersistenceService persistence;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean AuditLogRepository audits;
    @MockitoBean ConfirmCaseScope caseScope;
    @MockitoBean ConfirmReviewFactsSource reviewFacts;
    @MockitoBean ConfirmWorkflowGateway workflow;

    @Test
    void auditInsertFailureRollsBackReadyInTheDatabase() {
        persistDossier("APP-ROLLBACK");
        given(caseScope.mayConfirm(any(), eq("APP-ROLLBACK"))).willReturn(true);
        given(reviewFacts.read("APP-ROLLBACK", 1L)).willReturn(acceptedFacts("APP-ROLLBACK"));
        given(workflow.inspect("APP-ROLLBACK", 1L))
                .willReturn(ConfirmWorkflowGateway.TaskState.VERIFIED_ABSENT);
        doThrow(new IllegalStateException("synthetic audit insert failure"))
                .when(audits).saveAndFlush(any(AuditLog.class));
        var actor = actor();

        assertThatThrownBy(() -> confirm.confirm("APP-ROLLBACK",
                new ConfirmDossierRequest("Display Name", "Reviewed", 1L), actor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("synthetic audit insert failure");

        assertThat(applications.findById("APP-ROLLBACK").orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.REVIEW_NEEDED);
        assertThat(audits.countByApplicationApplicationIdAndAction("APP-ROLLBACK", "DOSSIER_CONFIRMED"))
                .isZero();
    }

    @Test
    void simultaneousConfirmationsHaveOneWinnerAndOneAudit() throws Exception {
        persistDossier("APP-CONCURRENT");
        given(caseScope.mayConfirm(any(), eq("APP-CONCURRENT"))).willReturn(true);
        given(reviewFacts.read("APP-CONCURRENT", 1L)).willReturn(acceptedFacts("APP-CONCURRENT"));
        given(workflow.inspect("APP-CONCURRENT", 1L))
                .willReturn(ConfirmWorkflowGateway.TaskState.VERIFIED_ABSENT);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var request = new ConfirmDossierRequest("Display Name", "Reviewed", 1L);
            var first = executor.submit(() -> {
                start.await();
                try { confirm.confirm("APP-CONCURRENT", request, actor()); return "SUCCESS"; }
                catch (ConfirmException exception) { return exception.code(); }
            });
            var second = executor.submit(() -> {
                start.await();
                try { confirm.confirm("APP-CONCURRENT", request, actor()); return "SUCCESS"; }
                catch (ConfirmException exception) { return exception.code(); }
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "DOSSIER_ALREADY_CONFIRMED");
        } finally {
            executor.shutdownNow();
        }
        assertThat(applications.findById("APP-CONCURRENT").orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.READY_FOR_CREDIT_REVIEW);
        assertThat(audits.countByApplicationApplicationIdAndAction("APP-CONCURRENT", "DOSSIER_CONFIRMED"))
                .isEqualTo(1);
    }

    @Test
    void uploadCommittedBeforeConfirmLockMakesOldExpectedVersionStale() throws Exception {
        persistDossier("APP-UPLOAD-RACE");
        given(caseScope.mayConfirm(any(), eq("APP-UPLOAD-RACE"))).willReturn(true);
        var uploadReady = new CountDownLatch(1);
        var releaseUpload = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var upload = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                persistence.saveUpload(new UploadDossierRequest("APP-UPLOAD-RACE", "SYNTHETIC_PRODUCT",
                        "Synthetic Applicant"), List.of(new StoredFile("FILE-UPLOADED-RACE",
                        "synthetic.pdf", "synthetic.pdf", Path.of("synthetic-tests", "synthetic.pdf"),
                        "application/pdf", 100, "b".repeat(64))));
                uploadReady.countDown();
                try {
                    if (!releaseUpload.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Upload release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return null;
            }));
            assertThat(uploadReady.await(10, TimeUnit.SECONDS)).isTrue();
            var confirmation = executor.submit(() -> {
                try {
                    confirm.confirm("APP-UPLOAD-RACE",
                            new ConfirmDossierRequest("Display Name", "Reviewed", 1L), actor());
                    return "SUCCESS";
                } catch (ConfirmException exception) {
                    return exception.code();
                }
            });
            releaseUpload.countDown();
            upload.get(10, TimeUnit.SECONDS);
            assertThat(confirmation.get(10, TimeUnit.SECONDS)).isEqualTo("STALE_DOSSIER_VERSION");
        } finally {
            releaseUpload.countDown();
            executor.shutdownNow();
        }
        assertThat(applications.findById("APP-UPLOAD-RACE").orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.REVIEW_NEEDED);
        assertThat(audits.countByApplicationApplicationIdAndAction("APP-UPLOAD-RACE", "DOSSIER_CONFIRMED"))
                .isZero();
    }

    private void persistDossier(String applicationId) {
        Application application = new Application();
        application.setApplicationId(applicationId);
        application.setProductType("SYNTHETIC_PRODUCT");
        application.setApplicantName("Synthetic Applicant");
        application.setCompletenessStatus(CompletenessStatus.REVIEW_NEEDED);
        DossierVersion version = new DossierVersion();
        version.setDossierVersionId("VERSION-" + applicationId + "-1");
        version.setVersionNumber(1);
        version.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        DocumentFile file = new DocumentFile();
        file.setFileId("FILE-" + applicationId);
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
    }

    private static UsernamePasswordAuthenticationToken actor() {
        return new UsernamePasswordAuthenticationToken("reviewer-R", null,
                List.of(new SimpleGrantedAuthority("ROLE_REVIEWER")));
    }

    private static ConfirmReviewFactsSource.ReviewFacts acceptedFacts(String applicationId) {
        var snapshot = new ConfirmEligibilityService.SnapshotFacts(applicationId, 1L,
                "synthetic-manifest", "synthetic-context",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"),
                Instant.parse("2099-01-01T00:00:00Z"), true, true, true, true);
        var requirement = new ConfirmEligibilityService.RequirementFact("SYNTHETIC-REQUIREMENT",
                ConfirmEligibilityService.Applicability.APPLICABLE, null,
                ConfirmEligibilityService.EvidenceState.ACCEPTED,
                List.of("FILE-" + applicationId), ConfirmEligibilityService.ExceptionState.NONE);
        var checklist = new ConfirmEligibilityService.ChecklistFacts(applicationId, 1L,
                "synthetic-manifest", "synthetic-context", "synthetic-policy", true,
                List.of(requirement));
        return new ConfirmReviewFactsSource.ReviewFacts(1L, snapshot, checklist);
    }
}
