
package com.example.retail_loan_document_completeness_checker.workers;

import com.example.retail_loan_document_completeness_checker.dto.OcrAnalyzeRequest;
import com.example.retail_loan_document_completeness_checker.dto.OcrAnalyzeResponse;
import com.example.retail_loan_document_completeness_checker.dto.OcrFileRequest;
import com.example.retail_loan_document_completeness_checker.models.DocumentFile;
import com.example.retail_loan_document_completeness_checker.models.DossierVersion;
import com.example.retail_loan_document_completeness_checker.repositories.DossierVersionRepository;
import com.example.retail_loan_document_completeness_checker.services.OcrAiClient;
import com.example.retail_loan_document_completeness_checker.services.OcrResultPersistenceService;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class OcrCheckWorker {

    private static final Logger log =
            LoggerFactory.getLogger(OcrCheckWorker.class);

    private static final Set<String> REQUIRED_DOCUMENT_TYPES = Set.of(
            "NATIONAL_ID",
            "LABOR_CONTRACT",
            "BANK_STATEMENT",
            "LOAN_APPLICATION"
    );

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "MISSING",
            "MISSING_BACK_SIDE",
            "PERIOD_GAP",
            "BLURRY",
            "MISSING_SIGNATURE",
            "VALID",
            "PENDING"
    );

    private final DossierVersionRepository dossierVersionRepository;
    private final OcrAiClient ocrAiClient;
    private final OcrResultPersistenceService persistenceService;
    private final JobClient jobClient;

    public OcrCheckWorker(
            DossierVersionRepository dossierVersionRepository,
            OcrAiClient ocrAiClient,
            OcrResultPersistenceService persistenceService,
            JobClient jobClient
    ) {
        this.dossierVersionRepository = dossierVersionRepository;
        this.ocrAiClient = ocrAiClient;
        this.persistenceService = persistenceService;
        this.jobClient = jobClient;
    }

    @JobWorker(
            type = "ocr-check",
            fetchVariables = {"applicationId", "dossierVersion"},
            autoComplete = false,
            enabled = false
    )
    public void handleOcrCheck(ActivatedJob job) {

        Map<String, Object> variables = job.getVariablesAsMap();

        String applicationId =
                (String) variables.get("applicationId");

        Object versionValue = variables.get("dossierVersion");

        if (applicationId == null || applicationId.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing applicationId from Camunda"
            );
        }

        if (!(versionValue instanceof Number)) {
            throw new IllegalArgumentException(
                    "Missing or invalid dossierVersion from Camunda"
            );
        }

        int dossierVersion = ((Number) versionValue).intValue();

        if (dossierVersion <= 0) {
            throw new IllegalArgumentException(
                    "dossierVersion must be greater than zero"
            );
        }

        log.info(
                "Received OCR job: applicationId={}, dossierVersion={}, jobKey={}",
                applicationId,
                dossierVersion,
                job.getKey()
        );

        DossierVersion version = dossierVersionRepository
                .findByApplicationApplicationIdAndVersionNumber(
                        applicationId,
                        dossierVersion
                )
                .orElseThrow(() -> new IllegalArgumentException(
                        "Dossier version not found: "
                                + applicationId + ", version " + dossierVersion
                ));

        List<DocumentFile> documents = version.getDocuments();

        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException(
                    "No documents found for dossier: " + applicationId
            );
        }

        List<OcrFileRequest> files = documents.stream()
                .map(document -> new OcrFileRequest(
                        document.getFileId(),
                        document.getFileName(),
                        document.getStoragePath()
                ))
                .toList();

        OcrAnalyzeRequest request = new OcrAnalyzeRequest(
                applicationId,
                dossierVersion,
                files
        );

        log.info("Prepared OCR request with {} file(s)", files.size());

        OcrAnalyzeResponse response = ocrAiClient.analyze(request);

        if (response == null
                || !applicationId.equals(response.applicationId())
                || dossierVersion != response.dossierVersion()) {
            throw new IllegalStateException(
                    "OCR response does not match requested dossier"
            );
        }

        if (response.results() == null || response.results().isEmpty()) {
            throw new IllegalStateException(
                    "OCR response contains no file results"
            );
        }

        persistenceService.saveResults(response);

        log.info(
                "OCR processing completed: applicationId={}, dossierVersion={}, resultCount={}",
                applicationId,
                dossierVersion,
                response.results().size()
        );

        Map<String, Object> dmnVariables = buildDmnVariables(response);

        log.info("Prepared DMN variables: {}", dmnVariables);

        jobClient.newCompleteCommand(job.getKey())
                .variables(dmnVariables)
                .send()
                .join();

        log.info("Camunda job completed: jobKey={}", job.getKey());
    }

    private Map<String, Object> buildDmnVariables(
            OcrAnalyzeResponse response
    ) {
        Map<String, String> documentStatuses = new HashMap<>();

        for (var fileResult : response.results()) {

            if (fileResult == null || fileResult.pages() == null) {
                continue;
            }

            String fileStatus = normalizeStatus(fileResult.status());

            for (var page : fileResult.pages()) {

                if (page == null || page.documentClass() == null) {
                    continue;
                }

                String documentType = page.documentClass();

                if (!REQUIRED_DOCUMENT_TYPES.contains(documentType)) {
                    continue;
                }

                documentStatuses.merge(
                        documentType,
                        fileStatus,
                        this::selectWorseStatus
                );
            }
        }

        Map<String, Object> dmnVariables = new HashMap<>();

        dmnVariables.put(
                "nationalIdStatus",
                documentStatuses.getOrDefault("NATIONAL_ID", "MISSING")
        );

        dmnVariables.put(
                "laborContractStatus",
                documentStatuses.getOrDefault("LABOR_CONTRACT", "MISSING")
        );

        dmnVariables.put(
                "bankStatementStatus",
                documentStatuses.getOrDefault("BANK_STATEMENT", "MISSING")
        );

        dmnVariables.put(
                "loanApplicationStatus",
                documentStatuses.getOrDefault("LOAN_APPLICATION", "MISSING")
        );

        return dmnVariables;
    }

    private String normalizeStatus(String status) {

        if (status == null || status.isBlank()) {
            return "PENDING";
        }

        return ALLOWED_STATUSES.contains(status)
                ? status
                : "PENDING";
    }

    private String selectWorseStatus(
            String current,
            String incoming
    ) {
        Map<String, Integer> priority = Map.of(
                "VALID", 0,
                "PENDING", 1,
                "MISSING_SIGNATURE", 2,
                "BLURRY", 3,
                "PERIOD_GAP", 4,
                "MISSING_BACK_SIDE", 5,
                "MISSING", 6
        );

        return priority.get(incoming) > priority.get(current)
                ? incoming
                : current;
    }
}
