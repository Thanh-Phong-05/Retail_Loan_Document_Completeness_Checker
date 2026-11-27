package com.example.retail_loan_document_completeness_checker;

import com.example.retail_loan_document_completeness_checker.dto.OcrAnalyzeResponse;
import com.example.retail_loan_document_completeness_checker.dto.OcrPageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OcrResponseTest {

    @Test
    void shouldConvertJsonToOcrResponse() throws Exception {

        String json = """
                {
                    "applicationId": "APP001",
                    "dossierVersion": 1,
                    "results": [
                        {
                            "fileId": "FILE001",
                            "fileName": "cccd.pdf",
                            "sha256": "abc123",
                            "status": "PENDING",
                            "missingPeriod": null,
                            "pages": [
                                {
                                    "pageAnchor": 1,
                                    "documentClass": "NATIONAL_ID",
                                    "confidence": 0.98,
                                    "matchedKeywords": ["national id", "identity"],
                                    "ocrUsed": true,
                                    "needsReview": false
                                }
                            ],
                            "error": null
                        }
                    ]
                }
                """;

        ObjectMapper objectMapper = new ObjectMapper();

        OcrAnalyzeResponse response = objectMapper.readValue(
                json,
                OcrAnalyzeResponse.class
        );

        assertEquals("APP001", response.applicationId());
        assertEquals(1, response.dossierVersion());

        assertNotNull(response.results());
        assertEquals(1, response.results().size());

        var fileResult = response.results().get(0);

        assertEquals("FILE001", fileResult.fileId());
        assertEquals("cccd.pdf", fileResult.fileName());
        assertEquals("abc123", fileResult.sha256());
        assertEquals("PENDING", fileResult.status());
        assertNull(fileResult.missingPeriod());
        assertNull(fileResult.error());

        assertNotNull(fileResult.pages());
        assertEquals(1, fileResult.pages().size());

        OcrPageResult page = fileResult.pages().get(0);

        assertEquals(1, page.pageAnchor());
        assertEquals("NATIONAL_ID", page.documentClass());
        assertEquals(0.98, page.confidence());
        assertEquals(
                java.util.List.of("national id", "identity"),
                page.matchedKeywords()
        );
        assertTrue(page.ocrUsed());
        assertFalse(page.needsReview());

        System.out.println("OCR Response JSON converted successfully!");
        System.out.println(response);
    }
}
