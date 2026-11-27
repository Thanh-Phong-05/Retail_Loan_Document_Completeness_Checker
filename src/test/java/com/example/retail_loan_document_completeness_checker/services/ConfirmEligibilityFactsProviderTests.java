package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.models.Application;
import com.example.retail_loan_document_completeness_checker.models.CompletenessResult;
import com.example.retail_loan_document_completeness_checker.models.DocStatus;
import com.example.retail_loan_document_completeness_checker.models.DocType;
import com.example.retail_loan_document_completeness_checker.models.DocumentFile;
import com.example.retail_loan_document_completeness_checker.models.DossierVersion;
import com.example.retail_loan_document_completeness_checker.repositories.ApplicationRepository;
import com.example.retail_loan_document_completeness_checker.repositories.DossierVersionRepository;
import com.example.retail_loan_document_completeness_checker.services.ConfirmEligibilityFactsProvider.ReadStatus;
import com.example.retail_loan_document_completeness_checker.services.ConfirmEligibilityService.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ConfirmEligibilityFactsProviderTests {

    @Mock ApplicationRepository applicationRepository;
    @Mock DossierVersionRepository dossierVersionRepository;

    private final ConfirmEligibilityService evaluator = new ConfirmEligibilityService();

    @Test
    void readsPersistedApplicationAndCurrentVersionWithoutInventingEvaluationVersion() {
        Application application = application("APP-001");
        DossierVersion current = version(application, 2);
        stubCurrent(application, current);

        var result = provider().read("APP-001", 2L);

        assertThat(result.status()).isEqualTo(ReadStatus.CURRENT_VERSION_READ);
        assertThat(result.versions().applicationId()).isEqualTo("APP-001");
        assertThat(result.versions().dossierVersion()).isEqualTo(2L);
        assertThat(result.versions().latestVersion()).isEqualTo(2L);
        assertThat(result.versions().evaluationVersion()).isNull();
        assertThat(evaluate(result)).isEqualTo(Decision.INDETERMINATE);
    }

    @Test
    void nonexistentApplicationProducesNoTrustedFacts() {
        given(applicationRepository.findById("MISSING")).willReturn(Optional.empty());

        var result = provider().read("MISSING", 1L);

        assertThat(result.status()).isEqualTo(ReadStatus.APPLICATION_NOT_FOUND);
        assertThat(result.versions()).isNull();
        assertThat(result.files()).isEmpty();
        assertThat(evaluate(result)).isEqualTo(Decision.INDETERMINATE);
        verifyNoInteractions(dossierVersionRepository);
    }

    @Test
    void missingCurrentVersionProducesNoTrustedFacts() {
        Application application = application("APP-001");
        given(applicationRepository.findById("APP-001")).willReturn(Optional.of(application));
        given(dossierVersionRepository.findFirstByApplicationApplicationIdOrderByVersionNumberDesc("APP-001"))
                .willReturn(Optional.empty());

        var result = provider().read("APP-001", 1L);

        assertThat(result.status()).isEqualTo(ReadStatus.NO_CURRENT_VERSION);
        assertThat(result.versions()).isNull();
        assertThat(result.files()).isEmpty();
    }

    @Test
    void staleExpectedVersionIsDetectedWithoutTreatingItAsPersistedEvaluation() {
        Application application = application("APP-001");
        DossierVersion current = version(application, 3);
        stubCurrent(application, current);

        var result = provider().read("APP-001", 2L);

        assertThat(result.status()).isEqualTo(ReadStatus.STALE_EXPECTED_VERSION);
        assertThat(result.versions().dossierVersion()).isEqualTo(3L);
        assertThat(result.versions().latestVersion()).isEqualTo(3L);
        assertThat(result.versions().evaluationVersion()).isNull();
        assertThat(result.files()).isEmpty();
        assertThat(evaluate(result)).isEqualTo(Decision.INDETERMINATE);
    }

    @Test
    void readsOnlyFilesBoundToTheCurrentApplicationVersion() {
        Application application = application("APP-001");
        DossierVersion old = version(application, 1);
        old.addDocument(file("OLD", DocType.UNKNOWN, DocStatus.PENDING));
        DossierVersion current = version(application, 2);
        current.addDocument(file("CURRENT", DocType.BANK_STATEMENT, DocStatus.VALID));
        stubCurrent(application, current);

        var result = provider().read("APP-001", 2L);

        assertThat(result.status()).isEqualTo(ReadStatus.CURRENT_VERSION_READ);
        assertThat(result.files()).hasSize(1);
        assertThat(result.files().get(0).fileId()).isEqualTo("CURRENT");
        assertThat(result.files().get(0).sha256()).isEqualTo("a".repeat(64));
        assertThat(result.files().get(0).docType()).isEqualTo(DocType.BANK_STATEMENT);
        assertThat(result.files().get(0).pageCount()).isEqualTo(2);
        assertThat(result.files().get(0).processingStatus()).isEqualTo(DocStatus.VALID);
    }

    @Test
    void pendingAndUnknownFileStateStayUnresolved() {
        Application application = application("APP-001");
        DossierVersion current = version(application, 1);
        current.addDocument(file("PENDING", DocType.UNKNOWN, DocStatus.PENDING));
        current.addDocument(file("UNKNOWN", DocType.UNKNOWN, null));
        stubCurrent(application, current);

        var result = provider().read("APP-001", 1L);

        assertThat(result.files()).extracting(fact -> fact.processingStatus())
                .containsExactly(DocStatus.PENDING, null);
        assertThat(result.files()).extracting(fact -> fact.docType())
                .containsExactly(DocType.UNKNOWN, DocType.UNKNOWN);
        assertThat(result.checklist()).isNull();
        assertThat(evaluate(result)).isEqualTo(Decision.INDETERMINATE);
    }

    @Test
    void unusableProcessingStatusAndEvenValidMachineResultDoNotBecomeAcceptedEvidence() {
        Application application = application("APP-001");
        DossierVersion current = version(application, 1);
        current.addDocument(file("BLURRY", DocType.NATIONAL_ID, DocStatus.BLURRY));
        DocumentFile machineValid = file("MACHINE-VALID", DocType.NATIONAL_ID, DocStatus.VALID);
        CompletenessResult machineResult = new CompletenessResult();
        machineResult.setResultId("RESULT-1");
        machineResult.setStatus(DocStatus.VALID);
        machineResult.setDocumentFile(machineValid);
        machineResult.setCreatedAt(Instant.parse("2026-11-20T08:00:00Z"));
        machineValid.getCompletenessResults().add(machineResult);
        current.addDocument(machineValid);
        stubCurrent(application, current);

        var result = provider().read("APP-001", 1L);

        assertThat(result.files()).extracting(fact -> fact.processingStatus())
                .containsExactly(DocStatus.BLURRY, DocStatus.VALID);
        assertThat(result.checklist()).isNull();
        assertThat(evaluate(result)).isEqualTo(Decision.INDETERMINATE);
    }

    @Test
    void missingChecklistReviewerAcceptanceAndAttestationStayUnknown() {
        Application application = application("APP-001");
        DossierVersion current = version(application, 1);
        current.addDocument(file("FILE-1", DocType.NATIONAL_ID, DocStatus.VALID));
        stubCurrent(application, current);

        var result = provider().read("APP-001", 1L);

        assertThat(result.snapshot()).isNull();
        assertThat(result.checklist()).isNull();
        assertThat(evaluate(result)).isEqualTo(Decision.INDETERMINATE);
    }

    @Test
    void invalidInputDoesNotReachPersistence() {
        var result = provider().read(" ", 0L);

        assertThat(result.status()).isEqualTo(ReadStatus.INVALID_INPUT);
        assertThat(evaluate(result)).isEqualTo(Decision.INDETERMINATE);
        verifyNoInteractions(applicationRepository, dossierVersionRepository);
    }

    @Test
    void mismatchedVersionOwnershipFailsClosed() {
        Application application = application("APP-001");
        DossierVersion wrongOwner = version(application("OTHER"), 1);
        stubCurrent(application, wrongOwner);

        var result = provider().read("APP-001", 1L);

        assertThat(result.status()).isEqualTo(ReadStatus.INCONSISTENT_ASSOCIATION);
        assertThat(result.versions()).isNull();
        assertThat(result.files()).isEmpty();
        assertThat(evaluate(result)).isEqualTo(Decision.INDETERMINATE);
    }

    @Test
    void fileBoundToAnotherVersionFailsClosed() {
        Application application = application("APP-001");
        DossierVersion current = version(application, 2);
        DossierVersion old = version(application, 1);
        current.getDocuments().add(file("WRONG", DocType.UNKNOWN, DocStatus.PENDING));
        current.getDocuments().get(0).setDossierVersion(old);
        stubCurrent(application, current);

        var result = provider().read("APP-001", 2L);

        assertThat(result.status()).isEqualTo(ReadStatus.INCONSISTENT_ASSOCIATION);
        assertThat(result.versions()).isNull();
        assertThat(result.files()).isEmpty();
    }

    @Test
    void readNeverWritesOrTransitionsDossierState() {
        Application application = application("APP-001");
        DossierVersion current = version(application, 1);
        current.addDocument(file("FILE-1", DocType.UNKNOWN, DocStatus.PENDING));
        stubCurrent(application, current);

        var result = provider().read("APP-001", 1L);

        assertThat(result.status()).isEqualTo(ReadStatus.CURRENT_VERSION_READ);
        assertThat(current.getDocuments().get(0).getStatus()).isEqualTo(DocStatus.PENDING);
        assertThat(application.getCompletenessStatus()).isNull();
        verify(applicationRepository).findById("APP-001");
        verify(dossierVersionRepository).findFirstByApplicationApplicationIdOrderByVersionNumberDesc("APP-001");
        verifyNoMoreInteractions(applicationRepository, dossierVersionRepository);
    }

    private ConfirmEligibilityFactsProvider provider() {
        return new ConfirmEligibilityFactsProvider(applicationRepository, dossierVersionRepository);
    }

    private Decision evaluate(ConfirmEligibilityFactsProvider.ReadResult result) {
        return evaluator.evaluate(result.versions(), result.snapshot(), result.checklist());
    }

    private void stubCurrent(Application application, DossierVersion current) {
        given(applicationRepository.findById("APP-001")).willReturn(Optional.of(application));
        given(dossierVersionRepository.findFirstByApplicationApplicationIdOrderByVersionNumberDesc("APP-001"))
                .willReturn(Optional.of(current));
    }

    private Application application(String id) {
        Application application = new Application();
        application.setApplicationId(id);
        application.setProductType("UNSECURED_SALARIED_LOAN");
        application.setApplicantName("Dao Quoc Thanh");
        return application;
    }

    private DossierVersion version(Application application, int number) {
        DossierVersion version = new DossierVersion();
        version.setDossierVersionId("VERSION-" + number);
        version.setVersionNumber(number);
        version.setCreatedAt(Instant.parse("2026-11-20T08:00:00Z"));
        application.addDossierVersion(version);
        return version;
    }

    private DocumentFile file(String id, DocType type, DocStatus status) {
        DocumentFile file = new DocumentFile();
        file.setFileId(id);
        file.setFileName(id + ".pdf");
        file.setStoredFileName(id + ".pdf");
        file.setStoragePath("uploads/" + id + ".pdf");
        file.setContentType("application/pdf");
        file.setFileSize(100);
        file.setFileHashSha256("a".repeat(64));
        file.setDocType(type);
        file.setStatus(status);
        file.setPageCount(2);
        return file;
    }
}
