package com.example.retail_loan_document_completeness_checker.dto;

import com.example.retail_loan_document_completeness_checker.models.ConfirmOperation;
import com.example.retail_loan_document_completeness_checker.models.CompletenessStatus;

public record ConfirmOperationStatus(String operationId, String applicationId, long version,
                                     ConfirmOperation.State state, String statusUrl,
                                     String auditLogId, CompletenessStatus completenessStatus) {}
