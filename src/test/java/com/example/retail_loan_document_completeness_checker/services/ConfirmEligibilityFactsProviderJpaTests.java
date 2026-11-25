package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.models.Application;
import com.example.retail_loan_document_completeness_checker.models.CompletenessStatus;
import com.example.retail_loan_document_completeness_checker.models.DocStatus;
import com.example.retail_loan_document_completeness_checker.models.DocType;
import com.example.retail_loan_document_completeness_checker.models.DocumentFile;
import com.example.retail_loan_document_completeness_checker.models.DossierVersion;
import com.example.retail_loan_document_completeness_checker.repositories.ApplicationRepository;
import com.example.retail_loan_document_completeness_checker.repositories.AuditLogRepository;
import com.example.retail_loan_document_completeness_checker.repositories.DocumentFileRepository;
import com.example.retail_loan_document_completeness_checker.repositories.DossierVersionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "debug=false")
@Import(ConfirmEligibilityFactsProvider.class)
class ConfirmEligibilityFactsProviderJpaTests {

    @Autowired ConfirmEligibilityFactsProvider provider;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired DossierVersionRepository dossierVersionRepository;
    @Autowired DocumentFileRepository documentFileRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired EntityManager entityManager;

    @Test
    void readsLatestVersionAndOnlyItsFilesWithinTheRequestedApplication() {
        Application first = application("APP-A");
        DossierVersion old = version(first, "ZZ-A-OLD", 1);
        old.addDocument(file("FILE-A-OLD", DocType.NATIONAL_ID, DocStatus.VALID));
        DossierVersion current = version(first, "AA-A-CURRENT", 2);
        current.addDocument(file("FILE-A-VALID", DocType.LABOR_CONTRACT, DocStatus.VALID));
        current.addDocument(file("FILE-A-PENDING", DocType.BANK_STATEMENT, DocStatus.PENDING));
        current.addDocument(file("FILE-A-UNKNOWN", DocType.UNKNOWN, null));
        Application second = application("APP-B");
        DossierVersion other = version(second, "BB-B-NEWER", 9);
        other.addDocument(file("FILE-B", DocType.LOAN_APPLICATION, DocStatus.VALID));
        persistAndClear(first);
        persistAndClear(second);

        var result = provider.read("APP-A", 2L);

        assertThat(result.status()).isEqualTo(ConfirmEligibilityFactsProvider.ReadStatus.CURRENT_VERSION_READ);
        assertThat(result.versions().applicationId()).isEqualTo("APP-A");
        assertThat(result.versions().dossierVersion()).isEqualTo(2L);
        assertThat(result.versions().latestVersion()).isEqualTo(2L);
        assertThat(result.versions().evaluationVersion()).isNull();
        assertThat(result.files()).extracting(ConfirmEligibilityFactsProvider.StoredFileFact::fileId)
                .containsExactlyInAnyOrder("FILE-A-VALID", "FILE-A-PENDING", "FILE-A-UNKNOWN");
        assertThat(result.files()).anySatisfy(file -> {
            assertThat(file.fileId()).isEqualTo("FILE-A-VALID");
            assertThat(file.processingStatus()).isEqualTo(DocStatus.VALID);
        });
        assertThat(result.files()).anySatisfy(file -> {
            assertThat(file.fileId()).isEqualTo("FILE-A-PENDING");
            assertThat(file.processingStatus()).isEqualTo(DocStatus.PENDING);
        });
        assertThat(result.files()).anySatisfy(file -> {
            assertThat(file.fileId()).isEqualTo("FILE-A-UNKNOWN");
            assertThat(file.docType()).isEqualTo(DocType.UNKNOWN);
            assertThat(file.processingStatus()).isNull();
        });
        assertThat(result.snapshot()).isNull();
        assertThat(result.checklist()).isNull();
        assertThat(new ConfirmEligibilityService().evaluate(
                result.versions(), result.snapshot(), result.checklist()))
                .isEqualTo(ConfirmEligibilityService.Decision.INDETERMINATE);
    }

    @Test
    void olderExpectedVersionIsStaleAndDoesNotExposeAnyFiles() {
        Application application = application("APP-STALE");
        version(application, "ZZ-OLD", 1).addDocument(file("OLD", DocType.NATIONAL_ID, DocStatus.VALID));
        version(application, "AA-CURRENT", 3).addDocument(file("CURRENT", DocType.BANK_STATEMENT, DocStatus.VALID));
        persistAndClear(application);

        var result = provider.read("APP-STALE", 1L);

        assertThat(result.status()).isEqualTo(ConfirmEligibilityFactsProvider.ReadStatus.STALE_EXPECTED_VERSION);
        assertThat(result.versions().dossierVersion()).isEqualTo(3L);
        assertThat(result.versions().latestVersion()).isEqualTo(3L);
        assertThat(result.versions().evaluationVersion()).isNull();
        assertThat(result.files()).isEmpty();
        assertThat(result.snapshot()).isNull();
        assertThat(result.checklist()).isNull();
    }

    @Test
    void missingApplicationAndMissingVersionReturnNoTrustedFacts() {
        persistAndClear(application("APP-EMPTY"));

        var absent = provider.read("APP-MISSING", 1L);
        var empty = provider.read("APP-EMPTY", 1L);

        assertThat(absent.status()).isEqualTo(ConfirmEligibilityFactsProvider.ReadStatus.APPLICATION_NOT_FOUND);
        assertThat(absent.versions()).isNull();
        assertThat(absent.files()).isEmpty();
        assertThat(empty.status()).isEqualTo(ConfirmEligibilityFactsProvider.ReadStatus.NO_CURRENT_VERSION);
        assertThat(empty.versions()).isNull();
        assertThat(empty.files()).isEmpty();
    }

    @Test
    void readingFactsDoesNotChangePersistedStatusFilesVersionsOrAudit() {
        Application application = application("APP-READ-ONLY");
        application.setCompletenessStatus(CompletenessStatus.REVIEW_NEEDED);
        version(application, "VERSION-1", 1)
                .addDocument(file("FILE-1", DocType.NATIONAL_ID, DocStatus.VALID));
        persistAndClear(application);
        long auditCountBefore = auditLogRepository.count();
        long versionCountBefore = dossierVersionRepository.count();
        long fileCountBefore = documentFileRepository.count();

        var result = provider.read("APP-READ-ONLY", 1L);
        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(ConfirmEligibilityFactsProvider.ReadStatus.CURRENT_VERSION_READ);
        assertThat(result.snapshot()).isNull();
        assertThat(result.checklist()).isNull();
        assertThat(applicationRepository.findById("APP-READ-ONLY").orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.REVIEW_NEEDED);
        assertThat(documentFileRepository.findById("FILE-1").orElseThrow().getStatus())
                .isEqualTo(DocStatus.VALID);
        assertThat(dossierVersionRepository.count()).isEqualTo(versionCountBefore);
        assertThat(documentFileRepository.count()).isEqualTo(fileCountBefore);
        assertThat(auditLogRepository.count()).isEqualTo(auditCountBefore);
    }

    private void persistAndClear(Application application) {
        applicationRepository.saveAndFlush(application);
        entityManager.clear();
    }

    private static Application application(String id) {
        Application application = new Application();
        application.setApplicationId(id);
        application.setProductType("SYNTHETIC_TEST_PRODUCT");
        application.setApplicantName("Synthetic Applicant");
        return application;
    }

    private static DossierVersion version(Application application, String id, int number) {
        DossierVersion version = new DossierVersion();
        version.setDossierVersionId(id);
        version.setVersionNumber(number);
        version.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        application.addDossierVersion(version);
        return version;
    }

    private static DocumentFile file(String id, DocType type, DocStatus status) {
        DocumentFile file = new DocumentFile();
        file.setFileId(id);
        file.setDocType(type);
        file.setFileName(id + ".pdf");
        file.setStoredFileName(id + ".pdf");
        file.setStoragePath("synthetic-tests/" + id + ".pdf");
        file.setContentType("application/pdf");
        file.setFileSize(100L);
        file.setFileHashSha256("a".repeat(64));
        file.setStatus(status);
        file.setPageCount(1);
        file.setPageAnchor(1);
        return file;
    }
}
