
package com.example.retail_loan_document_completeness_checker.dto;

import java.util.List;

public record OcrFileResult(
        String fileId,
        String fileName,
        String sha256,
        String status,
        String missingPeriod,
        List<OcrPageResult> pages,
        String error
) {
}
