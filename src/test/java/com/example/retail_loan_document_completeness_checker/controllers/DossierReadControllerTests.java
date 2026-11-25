package com.example.retail_loan_document_completeness_checker.controllers;

import com.example.retail_loan_document_completeness_checker.dto.DocumentContent;
import com.example.retail_loan_document_completeness_checker.dto.DocumentResponse;
import com.example.retail_loan_document_completeness_checker.dto.DossierResponse;
import com.example.retail_loan_document_completeness_checker.exceptions.DossierNotFoundException;
import com.example.retail_loan_document_completeness_checker.models.CompletenessStatus;
import com.example.retail_loan_document_completeness_checker.models.DocStatus;
import com.example.retail_loan_document_completeness_checker.models.DocType;
import com.example.retail_loan_document_completeness_checker.services.DossierService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DossierReadControllerTests {

    @Mock
    private DossierService dossierService;

    @InjectMocks
    private DossierController dossierController;

    @Test
    void getAllDossiersReturnsLatestDossierList() {
        DossierResponse sample = new DossierResponse(
                "APP-2026-001",
                "UNSECURED_PERSONAL_LOAN",
                "Nguyen Van A",
                LocalDate.of(2026, 11, 20),
                2,
                Instant.parse("2026-11-20T01:00:00Z"),
                CompletenessStatus.REVIEW_NEEDED,
                List.of(new DocumentResponse(
                        "file-001",
                        DocType.UNKNOWN,
                        "cccd.pdf",
                        "application/pdf",
                        1024L,
                        "a".repeat(64),
                        DocStatus.VALID,
                        null,
                        2,
                        1
                ))
        );
        when(dossierService.getAllDossiers()).thenReturn(List.of(sample));

        List<DossierResponse> result = dossierController.getAllDossiers();

        assertEquals(1, result.size());
        assertEquals("APP-2026-001", result.get(0).applicationId());
        assertEquals(2, result.get(0).version());
    }

    @Test
    void getFileContentReturnsInlinePdfResource() {
        byte[] pdfBytes = "%PDF-1.4 test content".getBytes();
        Resource resource = new ByteArrayResource(pdfBytes);
        when(dossierService.getFileContent("APP-2026-001", "file-001"))
                .thenReturn(new DocumentContent(resource, "ho_so_vay.pdf", "application/pdf"));

        ResponseEntity<Resource> response = dossierController.getFileContent("APP-2026-001", "file-001");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertTrue(disposition.startsWith("inline"));
        assertEquals(resource, response.getBody());
    }

    @Test
    void getFileContentPropagatesNotFoundWhenFileDoesNotBelongToApplication() {
        when(dossierService.getFileContent("APP-2026-001", "wrong-file"))
                .thenThrow(new DossierNotFoundException("APP-2026-001"));

        assertThrows(
                DossierNotFoundException.class,
                () -> dossierController.getFileContent("APP-2026-001", "wrong-file")
        );
    }
}