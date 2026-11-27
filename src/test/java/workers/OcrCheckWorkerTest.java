
package com.example.retail_loan_document_completeness_checker.workers;

import com.example.retail_loan_document_completeness_checker.dto.OcrAnalyzeRequest;
import com.example.retail_loan_document_completeness_checker.dto.OcrAnalyzeResponse;
import com.example.retail_loan_document_completeness_checker.dto.OcrFileResult;
import com.example.retail_loan_document_completeness_checker.dto.OcrPageResult;
import com.example.retail_loan_document_completeness_checker.models.DocumentFile;
import com.example.retail_loan_document_completeness_checker.models.DossierVersion;
import com.example.retail_loan_document_completeness_checker.repositories.DossierVersionRepository;
import com.example.retail_loan_document_completeness_checker.services.OcrAiClient;
import com.example.retail_loan_document_completeness_checker.services.OcrResultPersistenceService;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OcrCheckWorkerTest {

    private DossierVersionRepository repository;
    private OcrAiClient aiClient;
    private OcrResultPersistenceService persistenceService;
    private JobClient jobClient;
    private ActivatedJob job;

    private OcrCheckWorker worker;

    @BeforeEach
    void setUp() {
        repository = mock(DossierVersionRepository.class);
        aiClient = mock(OcrAiClient.class);
        persistenceService = mock(OcrResultPersistenceService.class);

        jobClient = mock(JobClient.class, RETURNS_DEEP_STUBS);
        job = mock(ActivatedJob.class);

        worker = new OcrCheckWorker(
                repository,
                aiClient,
                persistenceService,
                jobClient
        );

        when(job.getKey()).thenReturn(123L);

        when(job.getVariablesAsMap()).thenReturn(Map.of(
                "applicationId", "APP001",
                "dossierVersion", 1
        ));
    }

    @Test
    void shouldProcessOcrAndCompleteCamundaJob() {

        DossierVersion version = createDossierWithDocuments();

        when(repository.findByApplicationApplicationIdAndVersionNumber(
                "APP001", 1
        )).thenReturn(Optional.of(version));

        OcrAnalyzeResponse response = createOcrResponse("PENDING");

        when(aiClient.analyze(any(OcrAnalyzeRequest.class)))
                .thenReturn(response);

        assertDoesNotThrow(() -> worker.handleOcrCheck(job));

        verify(aiClient).analyze(any(OcrAnalyzeRequest.class));
        verify(persistenceService).saveResults(response);
        verify(jobClient).newCompleteCommand(123L);
    }

    @Test
    void shouldPassPendingStatusesToDmn() {

        DossierVersion version = createDossierWithDocuments();

        when(repository.findByApplicationApplicationIdAndVersionNumber(
                "APP001", 1
        )).thenReturn(Optional.of(version));

        OcrAnalyzeResponse response = createOcrResponse("PENDING");

        when(aiClient.analyze(any(OcrAnalyzeRequest.class)))
                .thenReturn(response);

        worker.handleOcrCheck(job);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass(Map.class);

        verify(jobClient.newCompleteCommand(123L))
                .variables(captor.capture());

        Map<String, Object> variables = captor.getValue();

        assertEquals("PENDING", variables.get("nationalIdStatus"));
        assertEquals("PENDING", variables.get("laborContractStatus"));
        assertEquals("PENDING", variables.get("bankStatementStatus"));
        assertEquals("PENDING", variables.get("loanApplicationStatus"));
    }

    @Test
    void shouldRejectMissingApplicationId() {

        when(job.getVariablesAsMap()).thenReturn(Map.of(
                "dossierVersion", 1
        ));

        assertThrows(
                IllegalArgumentException.class,
                () -> worker.handleOcrCheck(job)
        );

        verifyNoInteractions(repository, aiClient, persistenceService);
    }

    @Test
    void shouldRejectInvalidDossierVersion() {

        when(job.getVariablesAsMap()).thenReturn(Map.of(
                "applicationId", "APP001",
                "dossierVersion", "INVALID"
        ));

        assertThrows(
                IllegalArgumentException.class,
                () -> worker.handleOcrCheck(job)
        );

        verifyNoInteractions(repository, aiClient, persistenceService);
    }

    @Test
    void shouldRejectDossierVersionNotFound() {

        when(repository.findByApplicationApplicationIdAndVersionNumber(
                "APP001", 1
        )).thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> worker.handleOcrCheck(job)
        );

        verifyNoInteractions(aiClient, persistenceService);
    }

    @Test
    void shouldNotCompleteJobWhenPythonApiFails() {

        DossierVersion version = createDossierWithDocuments();

        when(repository.findByApplicationApplicationIdAndVersionNumber(
                "APP001", 1
        )).thenReturn(Optional.of(version));

        when(aiClient.analyze(any(OcrAnalyzeRequest.class)))
                .thenThrow(new RuntimeException("Python API unavailable"));

        assertThrows(
                RuntimeException.class,
                () -> worker.handleOcrCheck(job)
        );

        verifyNoInteractions(persistenceService);
        verify(jobClient, never()).newCompleteCommand(anyLong());
    }

    @Test
    void shouldRejectResponseForDifferentApplication() {

        DossierVersion version = createDossierWithDocuments();

        when(repository.findByApplicationApplicationIdAndVersionNumber(
                "APP001", 1
        )).thenReturn(Optional.of(version));

        OcrAnalyzeResponse wrongResponse = new OcrAnalyzeResponse(
                "APP999",
                1,
                List.of()
        );

        when(aiClient.analyze(any(OcrAnalyzeRequest.class)))
                .thenReturn(wrongResponse);

        assertThrows(
                IllegalStateException.class,
                () -> worker.handleOcrCheck(job)
        );

        verifyNoInteractions(persistenceService);
        verify(jobClient, never()).newCompleteCommand(anyLong());
    }

    private DossierVersion createDossierWithDocuments() {

        DossierVersion version = new DossierVersion();
        version.setVersionNumber(1);

        version.addDocument(createDocument(
                "FILE001", "cccd.pdf", "uploads/cccd.pdf"
        ));

        version.addDocument(createDocument(
                "FILE002", "contract.pdf", "uploads/contract.pdf"
        ));

        version.addDocument(createDocument(
                "FILE003", "bank.pdf", "uploads/bank.pdf"
        ));

        version.addDocument(createDocument(
                "FILE004", "application.pdf", "uploads/application.pdf"
        ));

        return version;
    }

    private DocumentFile createDocument(
            String fileId,
            String fileName,
            String storagePath
    ) {
        DocumentFile document = new DocumentFile();
        document.setFileId(fileId);
        document.setFileName(fileName);
        document.setStoragePath(storagePath);
        return document;
    }

    private OcrAnalyzeResponse createOcrResponse(String status) {

        return new OcrAnalyzeResponse(
                "APP001",
                1,
                List.of(
                        createFileResult("FILE001", "NATIONAL_ID", status),
                        createFileResult("FILE002", "LABOR_CONTRACT", status),
                        createFileResult("FILE003", "BANK_STATEMENT", status),
                        createFileResult("FILE004", "LOAN_APPLICATION", status)
                )
        );
    }

    private OcrFileResult createFileResult(
            String fileId,
            String documentClass,
            String status
    ) {
        OcrPageResult page = new OcrPageResult(
                1,
                documentClass,
                0.95,
                List.of("test"),
                true,
                false
        );

        return new OcrFileResult(
                fileId,
                fileId + ".pdf",
                "test-sha256",
                status,
                null,
                List.of(page),
                null
        );
    }
}
