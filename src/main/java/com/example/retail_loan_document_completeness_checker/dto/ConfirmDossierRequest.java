package com.example.retail_loan_document_completeness_checker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ConfirmDossierRequest(
        @NotBlank
        @Size(max = 200)
        String reviewerName,

        @Size(max = 1000)
        String note,

        @NotNull
        @Positive
        Long expectedVersion
) {
}
