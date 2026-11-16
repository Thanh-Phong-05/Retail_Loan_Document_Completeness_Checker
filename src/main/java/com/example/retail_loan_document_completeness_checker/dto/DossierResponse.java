package com.example.retail_loan_document_completeness_checker.dto;

import com.example.retail_loan_document_completeness_checker.models.CompletenessStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DossierResponse(
        String applicationId,
        String productType,
        String applicantName,
        LocalDate submissionDate,
        int version,
        Instant versionCreatedAt,
        CompletenessStatus completenessStatus,
        List<DocumentResponse> documents
) {
}
