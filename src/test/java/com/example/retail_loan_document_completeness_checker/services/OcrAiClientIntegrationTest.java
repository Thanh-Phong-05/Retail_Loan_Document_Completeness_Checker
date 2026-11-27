package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.dto.OcrAnalyzeRequest;
import com.example.retail_loan_document_completeness_checker.dto.OcrAnalyzeResponse;
import com.example.retail_loan_document_completeness_checker.dto.OcrFileRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OcrAiClientIntegrationTest {

    @Test
    void shouldCallPythonAiApiSuccessfully() {

        OcrAiClient client = new OcrAiClient("http://localhost:8000");

        OcrAnalyzeRequest request = new OcrAnalyzeRequest(
                "APP001",
                1,
                List.of(new OcrFileRequest(
                        "FILE001",
                        "bo1_ho_so_day_du.pdf",
                        "F:\\Retail_Loan_Document_Completeness_Checker\\ai\\classifier\\sample\\bo1_ho_so_day_du.pdf"
                ))
        );

        OcrAnalyzeResponse response = client.analyze(request);

        assertNotNull(response);
        assertEquals("APP001", response.applicationId());
        assertEquals(1, response.dossierVersion());
        assertNotNull(response.results());
        assertEquals(1, response.results().size());

        assertNull(response.results().get(0).error());
        assertEquals(5, response.results().get(0).pages().size());
        assertEquals(
                "NATIONAL_ID",
                response.results().get(0).pages().get(0).documentClass()
        );
    }
}