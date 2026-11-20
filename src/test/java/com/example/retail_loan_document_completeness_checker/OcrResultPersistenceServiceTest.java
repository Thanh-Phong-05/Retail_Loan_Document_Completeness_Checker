package com.example.retail_loan_document_completeness_checker;

import com.example.retail_loan_document_completeness_checker.dto.OcrAnalyzeResponse;
import com.example.retail_loan_document_completeness_checker.dto.OcrFileResult;
import com.example.retail_loan_document_completeness_checker.dto.OcrPageResult;
import com.example.retail_loan_document_completeness_checker.models.CompletenessResult;
import com.example.retail_loan_document_completeness_checker.models.DocStatus;
import com.example.retail_loan_document_completeness_checker.models.DocumentFile;
import com.example.retail_loan_document_completeness_checker.models.DossierVersion;
import com.example.retail_loan_document_completeness_checker.repositories.DossierVersionRepository;
import com.example.retail_loan_document_completeness_checker.services.OcrResultPersistenceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OcrResultPersistenceServiceTest {

    @Test
    void shouldSaveOcrResultsForMultiplePages() {

        // Arrange
        DossierVersionRepository repository =
                mock(DossierVersionRepository.class);

        OcrResultPersistenceService service =
                new OcrResultPersistenceService(repository);

        DossierVersion version = new DossierVersion();
        version.setVersionNumber(1);

        DocumentFile document = new DocumentFile();
        document.setFileId("FILE001");

        version.addDocument(document);

        when(repository.findByApplicationApplicationIdAndVersionNumber(
                "APP001", 1
        )).thenReturn(Optional.of(version));

        OcrPageResult page1 = new OcrPageResult(
                1,
                "NATIONAL_ID",
                0.98,
                List.of("national id", "identity"),
                true,
                false
        );

        OcrPageResult page2 = new OcrPageResult(
                2,
                "NATIONAL_ID",
                0.95,
                List.of("identity number"),
                true,
                false
        );

        OcrFileResult fileResult = new OcrFileResult(
                "FILE001",
                "cccd.pdf",
                "abc123",
                "PENDING",
                null,
                List.of(page1, page2),
                null
        );

        OcrAnalyzeResponse response = new OcrAnalyzeResponse(
                "APP001",
                1,
                List.of(fileResult)
        );

        // Act
        service.saveResults(response);

        // Assert
        assertEquals(2, document.getPageCount());
        assertEquals(2, document.getCompletenessResults().size());

        CompletenessResult savedPage1 =
                document.getCompletenessResults().get(0);

        CompletenessResult savedPage2 =
                document.getCompletenessResults().get(1);

        assertEquals(1, savedPage1.getPageAnchor());
        assertEquals("NATIONAL_ID", savedPage1.getDocumentClass());
        assertEquals(0.98, savedPage1.getConfidence());
        assertEquals("national id, identity", savedPage1.getMatchedKeywords());
        assertTrue(savedPage1.getOcrUsed());
        assertFalse(savedPage1.getNeedsReview());
        assertEquals(DocStatus.PENDING, savedPage1.getStatus());

        assertEquals(2, savedPage2.getPageAnchor());
        assertEquals("NATIONAL_ID", savedPage2.getDocumentClass());
        assertEquals(0.95, savedPage2.getConfidence());
        assertEquals("identity number", savedPage2.getMatchedKeywords());
        assertTrue(savedPage2.getOcrUsed());
        assertFalse(savedPage2.getNeedsReview());
        assertEquals(DocStatus.PENDING, savedPage2.getStatus());

        assertNotNull(savedPage1.getResultId());
        assertNotNull(savedPage1.getCreatedAt());
        assertSame(document, savedPage1.getDocumentFile());

        assertNotNull(savedPage2.getResultId());
        assertNotNull(savedPage2.getCreatedAt());
        assertSame(document, savedPage2.getDocumentFile());

        assertEquals(DocStatus.PENDING, document.getStatus());
        assertNull(document.getMissingPeriod());

        assertEquals(DocStatus.PENDING, savedPage1.getStatus());
        assertNull(savedPage1.getMissingPeriod());

        assertEquals(DocStatus.PENDING, savedPage2.getStatus());
        assertNull(savedPage2.getMissingPeriod());
    }

    @Test
    void shouldRejectMissingFileResult() {
        DossierVersionRepository repository = mock(DossierVersionRepository.class);
        OcrResultPersistenceService service = new OcrResultPersistenceService(repository);

        DossierVersion version = new DossierVersion();
        version.setVersionNumber(1);

        DocumentFile document1 = new DocumentFile();
        document1.setFileId("FILE001");

        DocumentFile document2 = new DocumentFile();
        document2.setFileId("FILE002");

        version.addDocument(document1);
        version.addDocument(document2);

        when(repository.findByApplicationApplicationIdAndVersionNumber(
                "APP001", 1
        )).thenReturn(Optional.of(version));

        OcrPageResult page = new OcrPageResult(
                1, "NATIONAL_ID", 0.98, List.of("identity"), true, false
        );

        OcrFileResult fileResult = new OcrFileResult(
                "FILE001", "cccd.pdf", "abc123",
                "PENDING", null, List.of(page), null
        );

        OcrAnalyzeResponse response = new OcrAnalyzeResponse(
                "APP001", 1, List.of(fileResult)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.saveResults(response)
        );

        assertEquals(0, document1.getPageCount());
        assertEquals(0, document2.getPageCount());
    }

    @Test
    void shouldRejectDuplicateFileResults() {
        DossierVersionRepository repository = mock(DossierVersionRepository.class);
        OcrResultPersistenceService service = new OcrResultPersistenceService(repository);

        DossierVersion version = new DossierVersion();
        version.setVersionNumber(1);

        DocumentFile document = new DocumentFile();
        document.setFileId("FILE001");
        version.addDocument(document);

        when(repository.findByApplicationApplicationIdAndVersionNumber(
                "APP001", 1
        )).thenReturn(Optional.of(version));

        OcrPageResult page = new OcrPageResult(
                1, "NATIONAL_ID", 0.98, List.of("identity"), true, false
        );

        OcrFileResult fileResult = new OcrFileResult(
                "FILE001", "cccd.pdf", "abc123",
                "PENDING", null, List.of(page), null
        );

        OcrAnalyzeResponse response = new OcrAnalyzeResponse(
                "APP001", 1, List.of(fileResult, fileResult)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.saveResults(response)
        );

        assertEquals(0, document.getPageCount());
    }

    @Test
    void shouldRejectFileNotBelongingToDossier() {
        DossierVersionRepository repository = mock(DossierVersionRepository.class);
        OcrResultPersistenceService service = new OcrResultPersistenceService(repository);

        DossierVersion version = new DossierVersion();
        version.setVersionNumber(1);

        DocumentFile document = new DocumentFile();
        document.setFileId("FILE001");
        version.addDocument(document);

        when(repository.findByApplicationApplicationIdAndVersionNumber(
                "APP001", 1
        )).thenReturn(Optional.of(version));

        OcrPageResult page = new OcrPageResult(
                1, "NATIONAL_ID", 0.98, List.of("identity"), true, false
        );

        OcrFileResult unknownFile = new OcrFileResult(
                "FILE999", "unknown.pdf", "abc123",
                "PENDING", null, List.of(page), null
        );

        OcrAnalyzeResponse response = new OcrAnalyzeResponse(
                "APP001", 1, List.of(unknownFile)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.saveResults(response)
        );

        assertEquals(0, document.getPageCount());
    }

    @Test
    void shouldRejectUnsupportedStatus() {
        DossierVersionRepository repository = mock(DossierVersionRepository.class);
        OcrResultPersistenceService service = new OcrResultPersistenceService(repository);

        DossierVersion version = new DossierVersion();
        version.setVersionNumber(1);

        DocumentFile document = new DocumentFile();
        document.setFileId("FILE001");
        version.addDocument(document);

        when(repository.findByApplicationApplicationIdAndVersionNumber(
                "APP001", 1
        )).thenReturn(Optional.of(version));

        OcrPageResult page = new OcrPageResult(
                1, "NATIONAL_ID", 0.98, List.of("identity"), true, false
        );

        OcrFileResult fileResult = new OcrFileResult(
                "FILE001", "cccd.pdf", "abc123",
                "INVALID_STATUS", null, List.of(page), null
        );

        OcrAnalyzeResponse response = new OcrAnalyzeResponse(
                "APP001", 1, List.of(fileResult)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.saveResults(response)
        );

        assertEquals(0, document.getPageCount());
    }

    @Test
    void shouldRejectOcrErrorWithoutUpdatingDocument() {
        DossierVersionRepository repository = mock(DossierVersionRepository.class);
        OcrResultPersistenceService service = new OcrResultPersistenceService(repository);

        DossierVersion version = new DossierVersion();
        version.setVersionNumber(1);

        DocumentFile document = new DocumentFile();
        document.setFileId("FILE001");
        version.addDocument(document);

        when(repository.findByApplicationApplicationIdAndVersionNumber(
                "APP001", 1
        )).thenReturn(Optional.of(version));

        OcrFileResult fileResult = new OcrFileResult(
                "FILE001", "cccd.pdf", null,
                "PENDING", null, List.of(),
                "File not found"
        );

        OcrAnalyzeResponse response = new OcrAnalyzeResponse(
                "APP001", 1, List.of(fileResult)
        );

        assertThrows(
                IllegalStateException.class,
                () -> service.saveResults(response)
        );

        assertEquals(0, document.getPageCount());
    }

}
