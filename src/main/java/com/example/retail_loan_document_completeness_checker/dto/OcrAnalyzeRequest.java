package com.example.retail_loan_document_completeness_checker.dto;

import java.util.List;

public record OcrAnalyzeRequest(
        String applicationId,
        int dossierVersion,
        List<OcrFileRequest> files
) {
}
