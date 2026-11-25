
package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.dto.OcrAnalyzeResponse;
import com.example.retail_loan_document_completeness_checker.dto.OcrFileResult;
import com.example.retail_loan_document_completeness_checker.dto.OcrPageResult;
import com.example.retail_loan_document_completeness_checker.models.CompletenessResult;
import com.example.retail_loan_document_completeness_checker.models.DocStatus;
import com.example.retail_loan_document_completeness_checker.models.DocType;
import com.example.retail_loan_document_completeness_checker.models.DocumentFile;
import com.example.retail_loan_document_completeness_checker.models.DossierVersion;
import com.example.retail_loan_document_completeness_checker.repositories.DossierVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OcrResultPersistenceService {

    private final DossierVersionRepository dossierVersionRepository;

    public OcrResultPersistenceService(
            DossierVersionRepository dossierVersionRepository
    ) {
        this.dossierVersionRepository = dossierVersionRepository;
    }

    @Transactional
    public void saveResults(OcrAnalyzeResponse response) {

        // 1. Validate response
        if (response == null) {
            throw new IllegalArgumentException("OCR response must not be null");
        }

        if (response.applicationId() == null
                || response.applicationId().isBlank()) {
            throw new IllegalArgumentException("Application ID must not be blank");
        }

        if (response.dossierVersion() <= 0) {
            throw new IllegalArgumentException("Dossier version must be positive");
        }

        if (response.results() == null || response.results().isEmpty()) {
            throw new IllegalArgumentException("OCR results must not be empty");
        }

        // 2. Find dossier version
        DossierVersion version = dossierVersionRepository
                .findByApplicationApplicationIdAndVersionNumber(
                        response.applicationId(),
                        response.dossierVersion()
                )
                .orElseThrow(() -> new IllegalArgumentException(
                        "Dossier version not found: "
                                + response.applicationId()
                                + ", version "
                                + response.dossierVersion()
                ));

        if (version.getDocuments() == null
                || version.getDocuments().isEmpty()) {
            throw new IllegalArgumentException(
                    "Dossier version has no documents"
            );
        }

        // 3. Build expected document map
        Map<String, DocumentFile> expectedDocuments =
                new LinkedHashMap<>();

        for (DocumentFile document : version.getDocuments()) {

            if (document == null
                    || document.getFileId() == null
                    || document.getFileId().isBlank()) {
                throw new IllegalArgumentException(
                        "Dossier contains a document with invalid file ID"
                );
            }

            if (expectedDocuments.putIfAbsent(
                    document.getFileId(), document
            ) != null) {
                throw new IllegalArgumentException(
                        "Duplicate document in dossier: "
                                + document.getFileId()
                );
            }
        }

        // 4. Validate and prepare all OCR results before updating entities
        Map<String, PreparedFileResult> preparedResults =
                new LinkedHashMap<>();

        for (OcrFileResult fileResult : response.results()) {

            if (fileResult == null
                    || fileResult.fileId() == null
                    || fileResult.fileId().isBlank()) {
                throw new IllegalArgumentException(
                        "OCR result contains an invalid file ID"
                );
            }

            String fileId = fileResult.fileId();

            DocumentFile document = expectedDocuments.get(fileId);

            if (document == null) {
                throw new IllegalArgumentException(
                        "Document does not belong to dossier: " + fileId
                );
            }

            if (preparedResults.containsKey(fileId)) {
                throw new IllegalArgumentException(
                        "Duplicate OCR result for file: " + fileId
                );
            }

            if (fileResult.error() != null
                    && !fileResult.error().isBlank()) {
                throw new IllegalStateException(
                        "OCR processing failed for file "
                                + fileId + ": " + fileResult.error()
                );
            }

            List<OcrPageResult> pages = fileResult.pages();

            if (pages == null || pages.isEmpty()) {
                throw new IllegalArgumentException(
                        "OCR returned no pages for file: " + fileId
                );
            }

            if (pages.stream().anyMatch(page -> page == null)) {
                throw new IllegalArgumentException(
                        "OCR returned a null page for file: " + fileId
                );
            }

            // Parse file-level status
            DocStatus fileStatus = DocStatus.PENDING;

            if (fileResult.status() != null
                    && !fileResult.status().isBlank()) {
                try {
                    fileStatus = DocStatus.valueOf(fileResult.status());
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException(
                            "Unsupported OCR status: "
                                    + fileResult.status(),
                            ex
                    );
                }
            }

            // Determine document type from page classifications
            Map<String, String> documentClasses = pages.stream()
                    .map(OcrPageResult::documentClass)
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .collect(Collectors.toMap(
                            value -> value,
                            value -> value
                    ));

            DocType docType = DocType.UNKNOWN;

            if (documentClasses.size() == 1) {
                try {
                    docType = DocType.valueOf(
                            documentClasses.keySet().iterator().next()
                    );
                } catch (IllegalArgumentException ignored) {
                    docType = DocType.UNKNOWN;
                }
            }

            preparedResults.put(
                    fileId,
                    new PreparedFileResult(
                            document,
                            fileResult,
                            fileStatus,
                            docType
                    )
            );
        }

        // 5. Ensure every dossier document has an OCR result
        if (!preparedResults.keySet().equals(expectedDocuments.keySet())) {

            throw new IllegalArgumentException(
                    "OCR results do not cover all dossier documents. "
                            + "Expected: " + expectedDocuments.keySet()
                            + ", received: " + preparedResults.keySet()
            );
        }

        // 6. All validations passed. Now update documents and save page results.
        for (PreparedFileResult prepared : preparedResults.values()) {

            DocumentFile document = prepared.document();
            OcrFileResult fileResult = prepared.fileResult();

            if (fileResult.sha256() != null
                    && !fileResult.sha256().isBlank()) {
                document.setFileHashSha256(fileResult.sha256());
            }

            document.setPageCount(fileResult.pages().size());
            document.setDocType(prepared.docType());
            document.setStatus(prepared.status());
            document.setMissingPeriod(fileResult.missingPeriod());

            // Remove old page results
            document.getCompletenessResults().clear();

            // Save new page results
            for (OcrPageResult page : fileResult.pages()) {

                CompletenessResult result = new CompletenessResult();

                result.setResultId(UUID.randomUUID().toString());
                result.setStatus(prepared.status());
                result.setMissingPeriod(fileResult.missingPeriod());
                result.setPageAnchor(page.pageAnchor());
                result.setDocumentClass(page.documentClass());
                result.setConfidence(page.confidence());

                result.setMatchedKeywords(
                        page.matchedKeywords() == null
                                ? null
                                : String.join(
                                ", ",
                                page.matchedKeywords()
                        )
                );

                result.setOcrUsed(page.ocrUsed());
                result.setNeedsReview(page.needsReview());
                result.setCreatedAt(Instant.now());
                result.setDocumentFile(document);

                document.getCompletenessResults().add(result);
            }
        }
    }

    private record PreparedFileResult(
            DocumentFile document,
            OcrFileResult fileResult,
            DocStatus status,
            DocType docType
    ) {}
}
