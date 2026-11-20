
package com.example.retail_loan_document_completeness_checker.dto;

import java.util.List;

public record OcrAnalyzeResponse(
        String applicationId,
        int dossierVersion,
        List<OcrFileResult> results
) {
}

