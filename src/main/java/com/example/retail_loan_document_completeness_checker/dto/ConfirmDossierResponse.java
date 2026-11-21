package com.example.retail_loan_document_completeness_checker.dto;

import com.example.retail_loan_document_completeness_checker.models.CompletenessStatus;

import java.time.Instant;

public record ConfirmDossierResponse(String applicationId, long version,
                                     CompletenessStatus completenessStatus,
                                     String auditLogId, Instant confirmedAt,
                                     String workflowState) {}
