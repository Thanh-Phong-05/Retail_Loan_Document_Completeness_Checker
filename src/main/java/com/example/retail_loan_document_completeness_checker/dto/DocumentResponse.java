package com.example.retail_loan_document_completeness_checker.dto;

import com.example.retail_loan_document_completeness_checker.models.DocStatus;
import com.example.retail_loan_document_completeness_checker.models.DocType;

public record DocumentResponse(
        String fileId,
        DocType docType,
        String fileName,
        String contentType,
        long fileSize,
        String fileHashSha256,
        DocStatus status,
        String missingPeriod,
        int pageCount,
        int pageAnchor
) {
}
